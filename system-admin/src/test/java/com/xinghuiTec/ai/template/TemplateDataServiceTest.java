package com.xinghuiTec.ai.template;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.xinghuiTec.ai.contract.*;
import com.xinghuiTec.domain.entity.*;
import com.xinghuiTec.lowcode.*;
import com.xinghuiTec.exception.ServiceException;
import org.junit.jupiter.api.*;
import org.springframework.beans.factory.support.DefaultListableBeanFactory;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.*;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.transaction.support.TransactionTemplate;
import java.nio.file.*;
import java.time.Instant;
import java.util.*;
import java.util.function.Supplier;
import static com.xinghuiTec.ai.contract.AiTypes.*;
import static com.xinghuiTec.ai.template.TemplateDefinition.*;
import static org.junit.jupiter.api.Assertions.*;

public class TemplateDataServiceTest {
    private JdbcTemplate jdbc;
    private TemplateDataService service;
    private LowcodeResourceReader reader;
    private TransactionTemplate transaction;
    private ObjectMapper mapper;
    private final String schema = "{\"fields\":[{\"fieldKey\":\"title\",\"label\":\"标题\",\"type\":\"text\",\"maxLength\":2000},{\"fieldKey\":\"private_note\",\"label\":\"内部备注\",\"type\":\"text\",\"maxLength\":2000}]}";
    @BeforeEach void setup() throws Exception {
        var ds = new DriverManagerDataSource("jdbc:h2:mem:template_" + UUID.randomUUID() + ";MODE=MySQL;DATABASE_TO_LOWER=TRUE;DB_CLOSE_DELAY=-1", "sa", "");
        jdbc = new JdbcTemplate(ds); var manager = new DataSourceTransactionManager(ds); transaction = new TransactionTemplate(manager);
        jdbc.execute("CREATE ALIAS JSON_EXTRACT FOR 'com.xinghuiTec.ai.template.TemplateDataServiceTest.jsonExtract'");
        jdbc.execute("CREATE ALIAS JSON_UNQUOTE FOR 'com.xinghuiTec.ai.template.TemplateDataServiceTest.jsonUnquote'");
        jdbc.execute("CREATE TABLE sys_user(user_id BIGINT PRIMARY KEY,tenant_id VARCHAR(64),status INT,is_deleted INT)");
        jdbc.update("INSERT INTO sys_user VALUES(101,'tenant_a',1,0),(102,'tenant_b',1,0)");
        String lowcode = Files.readString(Path.of("src/main/resources/db/migration/V20261012_160__lowcode_forms.sql")).split("-- Prefer reserved IDs")[0];
        apply(lowcode); apply(Files.readString(Path.of("src/main/resources/db/migration/V20261016_190__ai_template_data_v04.sql")));
        jdbc.update("INSERT INTO sys_lowcode_form(id,form_key,name,status,draft_schema,published_version,creator) VALUES(1,'neutral','表单','published',?,2,'999')", schema);
        jdbc.update("INSERT INTO sys_lowcode_form_version(form_id,schema_version,schema_json,creator) VALUES(1,1,?,'999'),(1,2,?,'999')", schema, "{\"fields\":[]}");
        jdbc.update("INSERT INTO sys_lowcode_record(id,form_id,schema_version,tenant_id,creator,data_json) VALUES(11,1,1,'tenant_a','999',?), (12,1,1,'tenant_b','999',?)", "{\"title\":\"真实 {{note}} $1\",\"private_note\":\"private-never-read\"}", "{\"title\":\"other-tenant\"}");
        mapper = new ObjectMapper(); var identity = new LowcodeIdentity(true);
        reader = new LowcodeResourceReader(new LowcodeService(jdbc, new LowcodeSchema(mapper), identity), identity, jdbc, manager);
        var factory = new DefaultListableBeanFactory();
        factory.registerSingleton("testReferences", new AiApplicationReferences() {
            public List<ApplicationReference> modelReferences(ModelVersionRef r) { return List.of(); }
            public List<ApplicationReference> templateReferences(TemplateVersionRef r) { return List.of(new ApplicationReference("201", "301", "测试应用")); }
            public List<ApplicationReference> resourceReferences(ResourceVersionRef r) { return List.of(); }
            public <T> T withReferenceGuard(Supplier<T> operation) { return transaction.execute(status -> operation.get()); }
        });
        service = new TemplateDataService(jdbc, mapper, reader, factory.getBeanProvider(AiApplicationReferences.class));
        login("101", "tenant_a", Set.of("lowcode:record:query"));
    }
    @AfterEach void close() { reader.close(); SecurityContextHolder.clearContext(); }
    public static String jsonExtract(String json, String path) throws Exception { return new ObjectMapper().readTree(json).get(path.substring(2)).toString(); }
    public static String jsonUnquote(String json) throws Exception { return new ObjectMapper().readTree(json).asText(); }
    private void apply(String sql) {
        sql = sql.lines().filter(l -> !l.stripLeading().startsWith("--")).collect(java.util.stream.Collectors.joining("\n"));
        for (String statement : sql.split(";")) if (!statement.isBlank()) jdbc.execute(statement);
    }
    private void login(String id, String tenant, Set<String> permissions) {
        var user = new SysUser(); user.setUserId(Long.valueOf(id)); user.setTenantId(tenant); user.setStatus(1);
        var principal = new LoginUser(user, new ArrayList<>(permissions)); principal.setTenantId(tenant);
        SecurityContextHolder.getContext().setAuthentication(new UsernamePasswordAuthenticationToken(principal, null, principal.getAuthorities()));
    }
    private ResourceDefinition resource(List<String> fields) { return new ResourceDefinition("表单资源", "测试业务", "负责人", "lowcode.record", "1", fields, Set.of("101"), Set.of("201", "202"), 1000); }
    private long approvedResource() {
        long id = Long.parseLong(service.createResource(resource(List.of("title"))));
        assertEquals("SUCCESS", service.testResource(id, new TemplateDataService.Test(1, "201", "1:11", Map.of())).outcome());
        service.submit(id, 2); service.approve(id, new TemplateDataService.Approval(3, true)); return id;
    }
    private TemplateDefinition definition(long resource, boolean required) {
        String version = service.versions(true, resource).get(0).get("id").toString();
        var binding = new Binding("record", Long.toString(resource), version, required, !required,
                Map.of("formId", new Parameter(ParameterSource.BUSINESS_REF, "formId"), "recordId", new Parameter(ParameterSource.BUSINESS_REF, "recordId")), Map.of("title", "recordTitle"));
        return new TemplateDefinition("摘要", "记录摘要测试", "负责人", List.of(new Message(Section.INSTRUCTION, "请总结业务数据"), new Message(Section.INPUT, "{{note}}"), new Message(Section.DATA, "{{recordTitle}}")),
                List.of(new Variable("note", Type.STRING, true, false, null, 100, true, false), new Variable("recordTitle", Type.STRING, required, false, null, 2000, true, false)), List.of(binding), 8000);
    }
    private TemplateVersionRef publishedTemplate(TemplateDefinition d) {
        long id = Long.parseLong(service.createTemplate(d));
        var result = service.testTemplate(id, new TemplateDataService.Test(1, "201", "1:11", Map.of("note", mapper.valueToTree("只作摘要"))), true);
        assertFalse(result.preview().messages().toString().contains("真实"));
        service.publish(id, 2); return new TemplateVersionRef(Long.toString(id), service.versions(false, id).get(0).get("id").toString());
    }
    private Invocation invocation(String app, String tenant, String record, Set<String> permissions) { return new Invocation(new TrustedActor("101", tenant, permissions, null), app, "301", new BusinessRef("lowcode.record", record), "request1"); }
    private InvocationControl control() { final var deadline = Instant.now().plusSeconds(5); return new InvocationControl() { public Instant deadline() { return deadline; } public boolean cancelled() { return false; } }; }
    private AiTemplateRuntime.RenderRequest request(TemplateVersionRef version, String app, String tenant, String record) { return new AiTemplateRuntime.RenderRequest(version, new AiInputs(Map.of("note", "只作摘要")), invocation(app, tenant, record, Set.of("lowcode:record:query"))); }
    @Test void realLowcodeReadUsesOriginalSchemaAndOnlyApprovedFieldsForTwoApplications() {
        long resource = approvedResource(); var version = publishedTemplate(definition(resource, true));
        for (String app : List.of("201", "202")) {
            var rendered = service.renderPublished(request(version, app, "tenant_a", "1:11"), control());
            assertEquals("真实 {{note}} $1", rendered.messages().get(2).content());
            assertEquals(MessageRole.SYSTEM, rendered.messages().get(0).role());
            assertFalse(rendered.messages().stream().anyMatch(m -> m.content().contains("private-never-read")));
        }
        assertEquals(1, jdbc.queryForObject("SELECT COUNT(*) FROM sys_lowcode_record WHERE id=11 AND creator='999'", Integer.class));
        assertEquals("101", reader.currentActor().subjectId());
    }
    @Test void editingInvalidatesApprovalAndCasRejectsStaleWrites() {
        long id = approvedResource();
        service.saveResource(id, new TemplateDataService.Draft<>(4, resource(List.of("title", "private_note"))));
        assertEquals("DRAFT", service.get(true, id).get("state"));
        assertThrows(ServiceException.class, () -> service.approve(id, new TemplateDataService.Approval(5, true)));
        assertThrows(ServiceException.class, () -> service.submit(id, 5));
        assertThrows(ServiceException.class, () -> service.saveResource(id, new TemplateDataService.Draft<>(4, resource(List.of("title")))));
        assertEquals(1, service.versions(true, id).size());
    }
    @Test void pinnedSnapshotsRemainImmutableAndDisableBlocksLaterRequests() {
        long resource = approvedResource(); var d = definition(resource, true); var exact = publishedTemplate(d); long id = Long.parseLong(exact.templateId());
        var changed = new TemplateDefinition("新模板名称", d.purpose(), d.owner(), d.messages(), d.variables(), d.resources(), d.maxChars());
        service.saveTemplate(id, new TemplateDataService.Draft<>(3, changed));
        assertEquals("摘要", ((TemplateDefinition) service.version(false, id, Long.parseLong(exact.templateVersionId())).get("definition")).name());
        assertEquals(1, service.references(false, id, Long.parseLong(exact.templateVersionId())).size());
        service.enabled(true, resource, new TemplateDataService.Enabled(4, false));
        assertEquals(ErrorCode.RESOURCE_UNAVAILABLE, assertThrows(AiRuntimeException.class, () -> service.renderPublished(request(exact, "201", "tenant_a", "1:11"), control())).code());
    }
    @Test void deniesApplicationTenantAuthorityAndCrossRecordSpoofingWithoutPartialOutput() {
        var version = publishedTemplate(definition(approvedResource(), true));
        assertEquals(ErrorCode.RESOURCE_FORBIDDEN, assertThrows(AiRuntimeException.class, () -> service.renderPublished(request(version, "999", "tenant_a", "1:11"), control())).code());
        assertEquals(ErrorCode.RESOURCE_FORBIDDEN, assertThrows(AiRuntimeException.class, () -> service.renderPublished(request(version, "201", "tenant_b", "1:12"), control())).code());
        assertEquals(ErrorCode.RESOURCE_UNAVAILABLE, assertThrows(AiRuntimeException.class, () -> service.renderPublished(request(version, "201", "tenant_a", "1:12"), control())).code());
        var noAuthority = new AiTemplateRuntime.RenderRequest(version, new AiInputs(Map.of("note", "value")), invocation("201", "tenant_a", "1:11", Set.of("ai:data:edit")));
        assertEquals(ErrorCode.RESOURCE_FORBIDDEN, assertThrows(AiRuntimeException.class, () -> service.renderPublished(noAuthority, control())).code());
    }
    @Test void optionalMissingHasSafeReasonButDoesNotSwallowDenial() {
        var version = publishedTemplate(definition(approvedResource(), false));
        var result = service.renderPublished(request(version, "201", "tenant_a", "1:999"), control());
        assertEquals("", result.messages().get(2).content());
        assertEquals("record:NOT_FOUND", result.notices().get(0).reference());
        assertThrows(AiRuntimeException.class, () -> service.renderPublished(request(version, "999", "tenant_a", "1:999"), control()));
        jdbc.update("DELETE FROM sys_lowcode_form_version WHERE form_id=1 AND schema_version=1");
        assertEquals(ErrorCode.RESOURCE_UNAVAILABLE, assertThrows(AiRuntimeException.class, () -> service.renderPublished(request(version, "201", "tenant_a", "1:11"), control())).code());
    }
    @Test void missingReferenceServiceFailsClosedAndPreventsPublishing() {
        var factory = new DefaultListableBeanFactory();
        var absent = new TemplateDataService(jdbc, mapper, reader, factory.getBeanProvider(AiApplicationReferences.class));
        long id = Long.parseLong(absent.createResource(resource(List.of("title"))));
        assertThrows(ServiceException.class, () -> absent.approve(id, new TemplateDataService.Approval(1, true)));
        assertEquals(0, jdbc.queryForObject("SELECT COUNT(*) FROM sys_ai_data_resource_version", Integer.class));
    }
    @Test void untestedResourceAndUnapprovedVersionCannotBind() {
        long id = Long.parseLong(service.createResource(resource(List.of("title"))));
        assertThrows(ServiceException.class, () -> service.submit(id, 1));
        assertEquals(0, service.versions(true, id).size());
        login("101", "tenant_a", Set.of("ai:data:test"));
        assertEquals("FORBIDDEN", service.testResource(id, new TemplateDataService.Test(1, "201", "1:11", Map.of())).outcome());
        assertEquals("DRAFT", service.get(true, id).get("state"));
    }
    @Test void cancelledAndExpiredControlsStopBeforeReading() {
        var version = publishedTemplate(definition(approvedResource(), true));
        var cancelled = new InvocationControl() { public Instant deadline() { return Instant.now().plusSeconds(2); } public boolean cancelled() { return true; } };
        assertEquals(ErrorCode.CANCELLED, assertThrows(AiRuntimeException.class, () -> service.renderPublished(request(version, "201", "tenant_a", "1:11"), cancelled)).code());
        var expired = new InvocationControl() { public Instant deadline() { return Instant.EPOCH; } public boolean cancelled() { return false; } };
        assertEquals(ErrorCode.DEADLINE_EXCEEDED, assertThrows(AiRuntimeException.class, () -> service.renderPublished(request(version, "201", "tenant_a", "1:11"), expired)).code());
    }
    @Test void legacySceneMappingIsReadOnlyAndDoesNotInventHistory() {
        jdbc.execute("CREATE TABLE sys_ai_prompt_scene(scene_id BIGINT,app_key VARCHAR(64),scene_key VARCHAR(96),published_model_id BIGINT,published_revision INT,enabled INT)");
        jdbc.update("INSERT INTO sys_ai_prompt_scene VALUES(9007199254740993,'legacy_app','summary',9007199254740994,7,1)");
        var page = service.legacyScenes(1, 20);
        assertEquals("1", page.total()); assertEquals("9007199254740993", page.records().get(0).get("scene_id"));
        assertEquals("9007199254740994", page.records().get(0).get("published_model_id"));
        assertNull(page.records().get(0).get("templateVersionId")); assertEquals(true, page.records().get(0).get("compatibilityOnly"));
        assertEquals(7, jdbc.queryForObject("SELECT published_revision FROM sys_ai_prompt_scene", Integer.class));
        assertEquals(0, jdbc.queryForObject("SELECT COUNT(*) FROM sys_ai_prompt_template_version", Integer.class));
    }
    @Test void optionsContainAllExactPublishedVersionsAndExcludeSensitiveConfiguration() throws Exception {
        long resource = approvedResource(); var d = definition(resource, true); var first = publishedTemplate(d); long id = Long.parseLong(first.templateId());
        var next = new TemplateDefinition("历史新版_100%", d.purpose(), d.owner(), d.messages(), d.variables(), d.resources(), d.maxChars());
        service.saveTemplate(id, new TemplateDataService.Draft<>(3, next));
        service.testTemplate(id, new TemplateDataService.Test(4, "201", "1:11", Map.of("note", mapper.valueToTree("secret-input"))), true);
        service.publish(id, 5);
        service.createTemplate(new TemplateDefinition("未发布草稿", d.purpose(), d.owner(), d.messages(), d.variables(), d.resources(), d.maxChars()));
        var page = service.options(1, 1, null, "201");
        assertEquals("2", page.total()); assertEquals(1, page.records().size()); assertEquals("历史新版_100%", page.records().get(0).name());
        var old = service.options(2, 1, null, "201").records().get(0);
        assertEquals(first.templateVersionId(), old.templateVersionId()); assertEquals("摘要", old.name()); assertTrue(old.available());
        assertEquals(List.of("note"), old.inputVariables().stream().map(AiTemplateRuntime.Variable::name).toList());
        assertEquals(1, old.resources().size());
        String serialized = mapper.writeValueAsString(page);
        for (String forbidden : List.of("defaultValue", "messages", "config_json", "private-never-read", "secret-input", "请总结业务数据")) assertFalse(serialized.contains(forbidden));
        assertEquals("1", service.options(1, 20, "_100%", null).total());
        assertEquals("0", service.options(1, 20, "未发布草稿", null).total());
    }
    @Test void optionAvailabilityRechecksExactResourceProofDisableAndApplicationScope() {
        long resource = approvedResource(); var exact = publishedTemplate(definition(resource, true));
        assertTrue(service.options(1, 20, null, null).records().get(0).available());
        assertEquals(List.of(TemplateDataService.OptionUnavailableReason.APP_NOT_ALLOWED), service.options(1, 20, null, "999").records().get(0).unavailableReasons());
        service.enabled(true, resource, new TemplateDataService.Enabled(4, false));
        assertTrue(service.options(1, 20, null, "201").records().get(0).unavailableReasons().contains(TemplateDataService.OptionUnavailableReason.RESOURCE_DISABLED));
        service.enabled(false, Long.parseLong(exact.templateId()), new TemplateDataService.Enabled(3, false));
        assertTrue(service.options(1, 20, null, "201").records().get(0).unavailableReasons().contains(TemplateDataService.OptionUnavailableReason.TEMPLATE_DISABLED));
        jdbc.update("UPDATE sys_ai_data_resource_version SET test_check_id=9999 WHERE resource_id=?", resource);
        assertTrue(service.options(1, 20, null, "201").records().get(0).unavailableReasons().contains(TemplateDataService.OptionUnavailableReason.RESOURCE_VERSION_UNAPPROVED));
        for (String invalid : List.of("0", "01", "1:2", "9223372036854775808", " 201")) assertThrows(IllegalArgumentException.class, () -> service.options(1, 20, null, invalid));
        assertThrows(IllegalArgumentException.class, () -> service.options(0, 20, null, null));
    }
    @Test void publishedPreviewStaysOnV1AndMasksAllInputsDefaultsAndBusinessValuesWithoutMutation() throws Exception {
        var base = definition(approvedResource(), true);
        var published = new TemplateDefinition("preview-v1", base.purpose(), base.owner(), List.of(new Message(Section.INSTRUCTION,"V1 instruction"),
                new Message(Section.INPUT,"V1 {{note}} {{private_default}}"),new Message(Section.DATA,"V1 {{recordTitle}}")),
                List.of(new Variable("note",Type.STRING,true,false,null,100,false,false),
                        new Variable("private_default",Type.STRING,false,true,mapper.valueToTree("private-default-value"),100,false,false),
                        new Variable("recordTitle",Type.STRING,true,false,null,2000,true,false)),base.resources(),8000);
        var version = publishedTemplate(published); long id = Long.parseLong(version.templateId());
        var draft = new TemplateDefinition("preview-v2",base.purpose(),base.owner(),List.of(new Message(Section.INSTRUCTION,"V2 draft instruction"),new Message(Section.INPUT,"V2 {{note}} {{private_default}}"),new Message(Section.DATA,"V2 {{recordTitle}}")),published.variables(),base.resources(),8000);
        service.saveTemplate(id,new TemplateDataService.Draft<>(3,draft));
        long checks = jdbc.queryForObject("SELECT COUNT(*) FROM sys_ai_data_resource_check",Long.class);
        String headBefore = mapper.writeValueAsString(service.get(false,id));
        var input = new AiTemplateRuntime.RenderRequest(version,new AiInputs(Map.of("note","runtime-visible-input")),invocation("201","tenant_a","1:11",Set.of("lowcode:record:query")));
        var preview = service.previewPublished(input,control());
        assertEquals(version,preview.version()); assertEquals("V1 instruction",preview.originalMessages().get(0).content());
        assertEquals("V1 {{note}} {{private_default}}",preview.originalMessages().get(1).content());
        assertEquals("V1 [已脱敏] [已脱敏]",preview.renderedMessages().get(1).content());
        assertEquals("V1 [已脱敏]",preview.renderedMessages().get(2).content());
        assertEquals(Map.of("note","SUPPLIED_MASKED","private_default","DEFAULT_MASKED"),preview.inputSummary());
        assertEquals(Map.of("record","AUTHORIZED_FIELDS_MASKED"),preview.resourceSummary());
        String response = mapper.writeValueAsString(preview);
        for (String secret : List.of("V2", "runtime-visible-input", "private-default-value", "真实 {{note}}", "private-never-read")) assertFalse(response.contains(secret));
        assertEquals("PublishedPreview[REDACTED]",preview.toString());
        assertEquals(checks,jdbc.queryForObject("SELECT COUNT(*) FROM sys_ai_data_resource_check",Long.class));
        assertEquals(headBefore,mapper.writeValueAsString(service.get(false,id)));
        // This fixture has no provider, request or attempt tables/beans. Preview succeeds without them.
        var actual = service.renderPublished(input,control());
        assertEquals("V1 runtime-visible-input private-default-value",actual.messages().get(1).content());
        assertEquals("V1 真实 {{note}} $1",actual.messages().get(2).content());
    }
    @Test void publishedPreviewAppliesBusinessPermissionsAndCurrentDisableBeforeReturningAnything() {
        var version = publishedTemplate(definition(approvedResource(),true));
        assertEquals(ErrorCode.RESOURCE_FORBIDDEN,assertThrows(AiRuntimeException.class,()->service.previewPublished(request(version,"999","tenant_a","1:11"),control())).code());
        assertEquals(ErrorCode.RESOURCE_FORBIDDEN,assertThrows(AiRuntimeException.class,()->service.previewPublished(request(version,"201","tenant_b","1:12"),control())).code());
        var manager = new AiTemplateRuntime.RenderRequest(version,new AiInputs(Map.of("note","input")),invocation("201","tenant_a","1:11",Set.of("ai:application:test")));
        assertEquals(ErrorCode.RESOURCE_FORBIDDEN,assertThrows(AiRuntimeException.class,()->service.previewPublished(manager,control())).code());
        service.enabled(false,Long.parseLong(version.templateId()),new TemplateDataService.Enabled(3,false));
        assertEquals(ErrorCode.TEMPLATE_UNAVAILABLE,assertThrows(AiRuntimeException.class,()->service.previewPublished(request(version,"201","tenant_a","1:11"),control())).code());
    }
    @Test void publishedPreviewHasOnlySafeOptionalOmissionAndRespectsCancellation() {
        var version = publishedTemplate(definition(approvedResource(),false));
        var preview = service.previewPublished(request(version,"201","tenant_a","1:999"),control());
        assertEquals("OPTIONAL_NOT_FOUND",preview.resourceSummary().get("record"));
        assertEquals(AiTemplateRuntime.RenderNoticeKind.OPTIONAL_RESOURCE_MISSING,preview.notices().get(0).kind());
        assertEquals("",preview.renderedMessages().get(2).content());
        var cancelled = new InvocationControl(){public Instant deadline(){return Instant.now().plusSeconds(1);}public boolean cancelled(){return true;}};
        assertEquals(ErrorCode.CANCELLED,assertThrows(AiRuntimeException.class,()->service.previewPublished(request(version,"201","tenant_a","1:11"),cancelled)).code());
    }
}
