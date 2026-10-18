package com.xinghuiTec.ai.scenedata;

import com.fasterxml.jackson.databind.*;
import com.xinghuiTec.ai.contract.*;
import com.xinghuiTec.ai.contract.AiSceneDataRuntime.*;
import com.xinghuiTec.ai.contract.AiTypes.*;
import com.xinghuiTec.lowcode.*;
import org.junit.jupiter.api.*;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.*;
import org.springframework.security.core.context.SecurityContextHolder;
import java.nio.file.*;
import java.time.Instant;
import java.util.*;
import static org.junit.jupiter.api.Assertions.*;

public class SceneDataServiceTest {
    private JdbcTemplate jdbc; private SceneDataService service; private ObjectMapper json;
    private static final String SCHEMA="{\"fields\":[{\"fieldKey\":\"subject\",\"label\":\"主题\",\"type\":\"text\",\"maxLength\":2000},{\"fieldKey\":\"summary\",\"label\":\"摘要值\",\"type\":\"number\",\"maxLength\":2000},{\"fieldKey\":\"internal_note\",\"label\":\"隐藏元数据\",\"type\":\"text\",\"maxLength\":2000}]}";
    @BeforeEach void setup() throws Exception {
        var ds=new DriverManagerDataSource("jdbc:h2:mem:scene_"+UUID.randomUUID()+";MODE=MySQL;DATABASE_TO_LOWER=TRUE;DB_CLOSE_DELAY=-1","sa","");
        jdbc=new JdbcTemplate(ds);json=new ObjectMapper();
        jdbc.execute("CREATE ALIAS JSON_EXTRACT FOR 'com.xinghuiTec.ai.scenedata.SceneDataServiceTest.extract'");
        jdbc.execute("CREATE ALIAS JSON_UNQUOTE FOR 'com.xinghuiTec.ai.scenedata.SceneDataServiceTest.unquote'");
        jdbc.execute("CREATE ALIAS JSON_TYPE FOR 'com.xinghuiTec.ai.scenedata.SceneDataServiceTest.type'");
        jdbc.execute("CREATE ALIAS JSON_SEARCH FOR 'com.xinghuiTec.ai.scenedata.SceneDataServiceTest.search'");
        jdbc.execute("CREATE TABLE sys_user(user_id BIGINT PRIMARY KEY,tenant_id VARCHAR(64),status INT,is_deleted INT)");
        jdbc.update("INSERT INTO sys_user VALUES(101,'tenant_a',1,0),(102,'tenant_b',1,0)");
        String ddl=Files.readString(Path.of("src/main/resources/db/migration/V20261012_160__lowcode_forms.sql")).split("-- Prefer reserved IDs")[0];
        for(String statement:ddl.split(";")) if(!statement.isBlank())jdbc.execute(statement);
        jdbc.update("INSERT INTO sys_lowcode_form(id,form_key,name,status,draft_schema,published_version,creator) VALUES(1,'neutral','公开表单','enabled',?,2,'999'),(2,'disabled','不可见草稿','draft',?,0,'999')",SCHEMA,SCHEMA);
        jdbc.update("INSERT INTO sys_lowcode_form_version(form_id,schema_version,schema_json,creator) VALUES(1,1,?,'999'),(1,2,?,'999')",SCHEMA,SCHEMA);
        jdbc.update("INSERT INTO sys_lowcode_record(id,form_id,schema_version,tenant_id,creator,data_json) VALUES(11,1,1,'tenant_a','999',?),(12,1,1,'tenant_a','999',?),(13,1,1,'tenant_b','999',?)",
                "{\"subject\":\"业务 {{user_request}} $1\",\"summary\":null,\"internal_note\":\"never-model\"}","{\"subject\":null,\"summary\":7.25}","{\"subject\":\"other-tenant\"}");
        var identity=new LowcodeIdentity(true);var schema=new LowcodeSchema(json);
        service=new SceneDataService(jdbc,new LowcodeService(jdbc,schema,identity),identity,schema,json,new DataSourceTransactionManager(ds));
    }
    @AfterEach void close(){service.close();SecurityContextHolder.clearContext();}
    private TrustedActor actor(){return new TrustedActor("101","tenant_a",Set.of("lowcode:record:query","lowcode:record:list"),null);}
    private InvocationControl control(){return new InvocationControl(){public Instant deadline(){return Instant.now().plusSeconds(5);}public boolean cancelled(){return false;}};}
    private EditorInput editor(String text,String... fields){return new EditorInput(text,"",fields.length==0?List.of():List.of(new Selection("lowcode.record","1",List.of(fields))));}
    private SceneContext context(String record){return new SceneContext("scene.server","version.1",actor(),new BusinessRef("lowcode.record","1:"+record),"7");}

    @Test void namedTokenUsesStableIdentityAndRetainsLegacyTokensWithSingleReplacement() {
        var input = editor("{{user_request:用户要求}} / {{user_request}} / {{record_subject:主题说明}}", "subject");
        var compiled = service.compile(input, actor(), control());
        var output = service.render(compiled, context("11"), new AiInputs(Map.of("user_request", "本次输入")), control());
        assertTrue(output.messages().stream().anyMatch(message -> message.content().contains("本次输入 / 本次输入 / 业务 {{user_request}} $1")));
        assertTrue(compiled.inputVariables().get(0).required());
        assertThrows(AiRuntimeException.class, () -> service.render(compiled, context("11"), new AiInputs(Map.of()), control()));
    }

    @Test void namedTokenCannotAuthorizeUnknownIdentityOrMalformedLabels() {
        for (String text : List.of("{{not_allowed:主题}}", "{{user_request:}}", "{{user_request:含:冒号}}",
                "{{user_request:换\n行}}", "{{user_request:   }}", "{{user_request:控制\u0001符}}"))
            assertThrows(InvalidConfiguration.class, () -> service.compile(editor(text), actor(), control()));
        assertDoesNotThrow(() -> service.compile(editor("{{user_request:可更改的显示说明}}"), actor(), control()));
    }

    @Test void catalogMetadataAndSamplesExposeOnlyAllowedIdentities(){
        assertEquals("lowcode.record",service.catalog(actor(),control()).objects().get(0).objectKey());
        var forms=service.forms(new OptionQuery(null,1,20),actor(),control());assertEquals("1",forms.total());assertEquals("公开表单",forms.records().get(0).name());
        var fields=service.fields("lowcode.record","1",actor(),control());assertEquals(List.of("subject","summary","internal_note"),fields.fields().stream().map(FieldOption::fieldKey).toList());
        var records=service.samples(new SampleQuery("lowcode.record","1",null,1,20),actor(),control());
        assertEquals("2",records.total());assertEquals(Set.of("11","12"),new HashSet<>(records.records().stream().map(SampleOption::recordId).toList()));
        assertTrue(records.records().stream().anyMatch(r->r.label().contains("业务")));assertFalse(records.toString().contains("never-model"));
        assertEquals("1",service.samples(new SampleQuery("lowcode.record","1","业务",1,20),actor(),control()).total());
        assertEquals("0",service.samples(new SampleQuery("lowcode.record","1","never-model",1,20),actor(),control()).total());
        assertEquals("0",service.forms(new OptionQuery("%",1,20),actor(),control()).total());
    }
    @Test void metadataPermissionsDoNotLeakTotalsOrNames(){
        var denied=new TrustedActor("101","tenant_a",Set.of(),null);
        assertTrue(service.catalog(denied,control()).objects().isEmpty());
        assertEquals(ErrorCode.RESOURCE_FORBIDDEN,assertThrows(AiRuntimeException.class,()->service.forms(new OptionQuery(null,1,20),denied,control())).code());
        var noList=new TrustedActor("101","tenant_a",Set.of("lowcode:record:query"),null);
        assertEquals(ErrorCode.RESOURCE_FORBIDDEN,assertThrows(AiRuntimeException.class,()->service.samples(new SampleQuery("lowcode.record","1",null,1,20),noList,control())).code());
    }
    @Test void compilationNeedsNoRecordAndNeverWritesApproval(){
        jdbc.update("DELETE FROM sys_lowcode_record");
        var compiled=service.compile(editor("概括 {{user_request}} {{record_subject}}","subject"),actor(),control());
        assertTrue(compiled.inputVariables().get(0).required());assertEquals(2,compiled.selections().get(0).schemaVersion());
        assertEquals(0,jdbc.queryForObject("SELECT COUNT(*) FROM sys_lowcode_record",Integer.class));
        assertFalse(jdbc.queryForList("SELECT TABLE_NAME FROM INFORMATION_SCHEMA.TABLES WHERE TABLE_NAME='sys_ai_data_resource_check'").size()>0);
    }
    @Test void invalidVariablesObjectsFieldsAndIntermediateSyntaxRejected(){
        for(String text:List.of("", "{{unknown}}", "{{bad.field}}", "{{user_request}"))assertThrows(InvalidConfiguration.class,()->service.compile(editor(text),actor(),control()));
        assertThrows(InvalidConfiguration.class,()->service.compile(editor("概括","api_secret"),actor(),control()));
        assertDoesNotThrow(()->service.compile(editor("获权备注 {{record_internal_note}}","internal_note"),actor(),control()));
        assertThrows(InvalidConfiguration.class,()->service.compile(editor("概括","subject","subject"),actor(),control()));
        assertThrows(InvalidConfiguration.class,()->service.compile(new EditorInput("概括","",List.of(new Selection("arbitrary.sql","1",List.of("subject")))),actor(),control()));
        assertThrows(InvalidConfiguration.class,()->service.compile(editor("{{record_subject}}"),actor(),control()));
    }
    @Test void originalSchemaNullAndSinglePassStayInDataMessages() throws Exception {
        var compiled=service.compile(editor("要求 {{user_request}}；看 {{record_subject}}","subject","summary"),actor(),control());
        var rendered=service.render(compiled,context("11"),new AiInputs(Map.of("user_request","保留 {{record_summary}} $1")),control());
        assertEquals(1,rendered.dataUses().get(0).schemaVersion());
        assertEquals("要求 保留 {{record_summary}} $1；看 业务 {{user_request}} $1",rendered.messages().get(1).content());
        String data=rendered.messages().get(rendered.messages().size()-1).content();
        var value=json.readTree(data.substring(data.indexOf('{'))).path("lowcode.record");assertTrue(value.get("summary").isNull());assertTrue(value.get("subject").asText().contains("{{user_request}}"));
        assertFalse(rendered.messages().stream().anyMatch(m->m.content().contains("never-model")));
        assertFalse(rendered.messages().get(0).content().contains("业务 {{"));
        var numeric=service.render(compiled,context("12"),new AiInputs(Map.of("user_request","简短")),control());
        var numbers=json.readTree(numeric.messages().get(numeric.messages().size()-1).content().substring(data.indexOf('{'))).path("lowcode.record");
        assertTrue(numbers.get("summary").isNumber());assertTrue(numbers.get("subject").isNull());
    }
    @Test void tenantWrongFormDeletionAndTypeMismatchFailBeforeAnyModel(){
        var compiled=service.compile(editor("概括","subject"),actor(),control());
        assertThrows(AiRuntimeException.class,()->service.render(compiled,context("13"),new AiInputs(Map.of()),control()));
        var wrong=new SceneContext("scene.server","version.1",actor(),new BusinessRef("lowcode.record","2:11"),"7");
        assertEquals(ErrorCode.RESOURCE_FORBIDDEN,assertThrows(AiRuntimeException.class,()->service.render(compiled,wrong,new AiInputs(Map.of()),control())).code());
        jdbc.update("UPDATE sys_lowcode_form_version SET schema_json=? WHERE form_id=1 AND schema_version=1",SCHEMA.replace("\"type\":\"text\"","\"type\":\"number\""));
        assertThrows(AiRuntimeException.class,()->service.render(compiled,context("11"),new AiInputs(Map.of()),control()));
        jdbc.update("UPDATE sys_user SET status=0 WHERE user_id=101");
        assertEquals(ErrorCode.RESOURCE_FORBIDDEN,assertThrows(AiRuntimeException.class,()->service.render(compiled,context("11"),new AiInputs(Map.of()),control())).code());
    }
    @Test void noSelectionsSupportsGeneralSceneAndUnknownInputsFail(){
        var compiled=service.compile(editor("固定要求"),new TrustedActor("101","tenant_a",Set.of(),null),control());
        assertTrue(service.render(compiled,context("11"),new AiInputs(Map.of()),control()).dataUses().isEmpty());
        assertEquals(ErrorCode.INVALID_INPUT,assertThrows(AiRuntimeException.class,()->service.render(compiled,context("11"),new AiInputs(Map.of("tenantId","tenant_b")),control())).code());
    }
    @Test void expiredControlStopsBeforeAnyDataAccess(){
        var expired=new InvocationControl(){public Instant deadline(){return Instant.EPOCH;}public boolean cancelled(){return false;}};
        assertEquals(ErrorCode.DEADLINE_EXCEEDED,assertThrows(AiRuntimeException.class,()->service.compile(editor("固定要求"),actor(),expired)).code());
    }
    @Test void inputFreeAuthorizationDoesNotRenderOrParseRecordBody(){
        var compiled=service.compile(editor("{{user_request}}","subject"),actor(),control());
        jdbc.update("UPDATE sys_lowcode_record SET data_json='not-json' WHERE id=11");
        assertDoesNotThrow(()->service.authorize(compiled,context("11"),control()));
        jdbc.update("UPDATE sys_lowcode_record SET is_deleted=1 WHERE id=11");
        assertEquals(ErrorCode.RESOURCE_FORBIDDEN,assertThrows(AiRuntimeException.class,()->service.authorize(compiled,context("11"),control())).code());
    }
    private com.xinghuiTec.ai.template.TemplateDefinition legacyFixture(boolean special) throws Exception {
        String ddl=Files.readString(Path.of("src/main/resources/db/migration/V20261016_190__ai_template_data_v04.sql"));
        for(String statement:ddl.split(";"))if(!statement.isBlank())jdbc.execute(statement);
        var r=new com.xinghuiTec.ai.template.ResourceDefinition("资源","业务","维护者","lowcode.record","1",List.of("subject"),Set.of("101"),Set.of("201"),1000);
        var canonical=canonical(json.valueToTree(r));var hash=java.util.HexFormat.of().formatHex(java.security.MessageDigest.getInstance("SHA-256").digest(json.writeValueAsBytes(canonical)));
        jdbc.update("INSERT INTO sys_ai_data_resource(id,name,draft_json,enabled) VALUES(21,'资源',?,1)",json.writeValueAsString(r));
        jdbc.update("INSERT INTO sys_ai_data_resource_check(id,resource_id,config_hash,scope_hash,action,outcome,subject_id,tenant_id) VALUES(51,21,?,?,'TEST','SUCCESS','101','tenant_a')",hash,"0".repeat(64));
        jdbc.update("INSERT INTO sys_ai_data_resource_version(id,resource_id,version_no,config_json,config_hash,approver,test_check_id) VALUES(61,21,1,?,?,'101',51)",json.writeValueAsString(r),hash);
        var d=new com.xinghuiTec.ai.template.TemplateDefinition("原摘要","业务","维护者",
                List.of(new com.xinghuiTec.ai.template.TemplateDefinition.Message(com.xinghuiTec.ai.template.TemplateDefinition.Section.INSTRUCTION,"固定摘要要求"),new com.xinghuiTec.ai.template.TemplateDefinition.Message(com.xinghuiTec.ai.template.TemplateDefinition.Section.INPUT,"{{user_request}}"),new com.xinghuiTec.ai.template.TemplateDefinition.Message(com.xinghuiTec.ai.template.TemplateDefinition.Section.DATA,"主题 {{record_subject}}")),
                List.of(new com.xinghuiTec.ai.template.TemplateDefinition.Variable("user_request",com.xinghuiTec.ai.template.TemplateDefinition.Type.STRING,true,special,special?json.getNodeFactory().textNode("原默认值"):null,2048,false,false),new com.xinghuiTec.ai.template.TemplateDefinition.Variable("record_subject",com.xinghuiTec.ai.template.TemplateDefinition.Type.STRING,true,false,null,2000,true,false)),
                List.of(new com.xinghuiTec.ai.template.TemplateDefinition.Binding("record","21","61",true,false,Map.of("formId",new com.xinghuiTec.ai.template.TemplateDefinition.Parameter(com.xinghuiTec.ai.template.TemplateDefinition.ParameterSource.BUSINESS_REF,"formId"),"recordId",new com.xinghuiTec.ai.template.TemplateDefinition.Parameter(com.xinghuiTec.ai.template.TemplateDefinition.ParameterSource.BUSINESS_REF,"recordId")),Map.of("subject","record_subject"))),8000);
        jdbc.update("INSERT INTO sys_ai_prompt_template(id,name,draft_json,enabled) VALUES(31,'原摘要',?,1)",json.writeValueAsString(d));
        jdbc.update("INSERT INTO sys_ai_prompt_template_version(id,template_id,version_no,config_json,config_hash,publisher) VALUES(41,31,1,?,?,'101')",json.writeValueAsString(d),"0".repeat(64));return d;
    }
    private JsonNode canonical(JsonNode n){if(n.isObject()){var result=json.createObjectNode();var names=new TreeSet<String>();n.fieldNames().forEachRemaining(names::add);names.forEach(k->result.set(k,canonical(n.get(k))));return result;}if(n.isArray()){var a=json.createArrayNode();n.forEach(v->a.add(canonical(v)));return a;}return n;}
    private Invocation legacyInvocation(){return new Invocation(new TrustedActor("101","tenant_a",Set.of("ai:scene:query","ai:scene:edit","lowcode:record:query"),null),"201","301",new BusinessRef("lowcode.record","1:11"),"7");}
    @Test void legacyInspectAndEditNeverMutateSharedTemplateOrBroadenApprovedFields() throws Exception {
        legacyFixture(false);String before=jdbc.queryForObject("SELECT config_json FROM sys_ai_prompt_template_version WHERE id=41",String.class);
        var bridge=new LegacySceneTemplates(jdbc,json,service);var ref=new TemplateVersionRef("31","41");var invocation=legacyInvocation();
        var view=bridge.inspect(ref,invocation,control());assertEquals(AiSceneLegacyTemplates.Compatibility.CONVERTIBLE,view.compatibility());assertEquals(3,view.originalMessages().size());assertTrue(view.requiresConfirmation());
        var compiled=bridge.compileEdited(ref,invocation,editor("新摘要 {{user_request}} {{record_subject}}","subject"),control());
        assertEquals(1,compiled.legacyScopes().size());assertEquals("201",compiled.legacyScopes().get(0).applicationId());
        assertThrows(AiRuntimeException.class,()->bridge.compileEdited(ref,invocation,editor("新摘要","subject","summary"),control()));
        assertEquals(before,jdbc.queryForObject("SELECT config_json FROM sys_ai_prompt_template_version WHERE id=41",String.class));
        assertDoesNotThrow(()->service.render(compiled,context("11"),new AiInputs(Map.of("user_request","简短")),control()));
        jdbc.update("UPDATE sys_ai_data_resource SET enabled=0 WHERE id=21");
        assertEquals(ErrorCode.RESOURCE_UNAVAILABLE,assertThrows(AiRuntimeException.class,()->service.authorize(compiled,context("11"),control())).code());
    }
    @Test void legacySpecialVariablesRemainReadOnlyWithOriginalDefinition() throws Exception {
        legacyFixture(true);var bridge=new LegacySceneTemplates(jdbc,json,service);
        var view=bridge.inspect(new TemplateVersionRef("31","41"),legacyInvocation(),control());
        assertEquals(AiSceneLegacyTemplates.Compatibility.READ_ONLY,view.compatibility());assertNull(view.editor());assertTrue(view.reasons().contains("SPECIAL_VARIABLE_SCHEMA"));
        assertTrue(view.originalDefinition().values().containsKey("variables"));assertFalse(view.toString().contains("原默认值"));
    }
    @Test void secondLegacySaveCannotAddSummaryAndScopeRemainsPinned() throws Exception {
        legacyFixture(false);var bridge=new LegacySceneTemplates(jdbc,json,service);var ref=new TemplateVersionRef("31","41");
        var once=bridge.compileEdited(ref,legacyInvocation(),editor("第一版","subject"),control());
        var twice=bridge.compileEdited(ref,legacyInvocation(),new EditorInput("第二版","",once.editor().selections()),control());
        assertEquals(once.legacyScopes(),twice.legacyScopes());
        assertThrows(AiRuntimeException.class,()->bridge.compileEdited(ref,legacyInvocation(),editor("第三版","subject","summary"),control()));
        assertEquals(1,jdbc.queryForObject("SELECT COUNT(*) FROM sys_ai_prompt_template_version WHERE template_id=31",Integer.class));
    }
    @Test void legacyScopeSurvivesSerializationAndRemoveThenReaddAcrossSaves() throws Exception {
        legacyFixture(false);var bridge=new LegacySceneTemplates(jdbc,json,service);var ref=new TemplateVersionRef("31","41");
        var first=bridge.compileEdited(ref,legacyInvocation(),editor("第一版 {{record_subject}}","subject"),control());
        var persisted=json.readValue(json.writeValueAsString(first),CompiledScene.class);
        assertEquals(first.legacyScopes(),persisted.legacyScopes());
        var removed=bridge.compileEdited(ref,legacyInvocation(),new EditorInput("只使用 {{user_request}}","",List.of()),control());
        assertTrue(removed.legacyScopes().isEmpty());
        assertThrows(AiRuntimeException.class,()->bridge.compileEdited(ref,legacyInvocation(),editor("扩展 {{record_summary}}","summary"),control()));
        var restored=bridge.compileEdited(ref,legacyInvocation(),persisted.editor(),control());
        assertEquals(first.legacyScopes(),restored.legacyScopes());
        jdbc.update("UPDATE sys_ai_data_resource SET enabled=0 WHERE id=21");
        assertThrows(AiRuntimeException.class,()->bridge.compileEdited(ref,legacyInvocation(),persisted.editor(),control()));
        assertEquals(ErrorCode.RESOURCE_UNAVAILABLE,assertThrows(AiRuntimeException.class,()->service.authorize(restored,context("11"),control())).code());
        assertEquals(1,jdbc.queryForObject("SELECT COUNT(*) FROM sys_ai_prompt_template_version WHERE template_id=31",Integer.class));
    }
    @Test void legacyInputTruncationAndRequiredPoliciesStayReadOnly() throws Exception {
        legacyFixture(false);var original=(com.fasterxml.jackson.databind.node.ObjectNode)json.readTree(jdbc.queryForObject("SELECT config_json FROM sys_ai_prompt_template_version WHERE id=41",String.class));
        for(String policy:List.of("truncate","optional","length","requiredWithoutSlot")) {
            var changed=original.deepCopy();var input=(com.fasterxml.jackson.databind.node.ObjectNode)changed.path("variables").get(0);
            String reason;
            if(policy.equals("truncate")){input.put("truncate",true);reason="VARIABLE_TRUNCATION_UNSUPPORTED";}
            else if(policy.equals("optional")){input.put("required",false);reason="INPUT_REQUIRED_POLICY_CHANGED";}
            else if(policy.equals("length")){input.put("maxChars",1024);reason="INPUT_LENGTH_POLICY_CHANGED";}
            else {((com.fasterxml.jackson.databind.node.ObjectNode)changed.path("messages").get(1)).put("text","固定输入要求");reason="INPUT_REQUIRED_POLICY_CHANGED";}
            assertLegacyReadOnly(changed,reason);
        }
    }
    @Test void legacyResourceVariableTruncationAndNarrowLengthStayReadOnly() throws Exception {
        legacyFixture(false);var original=(com.fasterxml.jackson.databind.node.ObjectNode)json.readTree(jdbc.queryForObject("SELECT config_json FROM sys_ai_prompt_template_version WHERE id=41",String.class));
        for(boolean truncate:List.of(false,true)) {
            var changed=original.deepCopy();var variable=(com.fasterxml.jackson.databind.node.ObjectNode)changed.path("variables").get(1);
            if(truncate)variable.put("truncate",true);else variable.put("maxChars",1000);
            assertLegacyReadOnly(changed,truncate?"VARIABLE_TRUNCATION_UNSUPPORTED":"RESOURCE_VARIABLE_LENGTH_POLICY_CHANGED");
        }
    }
    private void assertLegacyReadOnly(com.fasterxml.jackson.databind.node.ObjectNode changed,String reason) throws Exception {
        String encoded=json.writeValueAsString(changed);jdbc.update("UPDATE sys_ai_prompt_template_version SET config_json=? WHERE id=41",encoded);
        var bridge=new LegacySceneTemplates(jdbc,json,service);var ref=new TemplateVersionRef("31","41");
        var view=bridge.inspect(ref,legacyInvocation(),control());
        assertEquals(AiSceneLegacyTemplates.Compatibility.READ_ONLY,view.compatibility());assertTrue(view.reasons().contains(reason));assertNull(view.editor());
        assertEquals(changed,json.valueToTree(view.originalDefinition().values()));
        assertEquals(ErrorCode.CAPABILITY_UNSUPPORTED,assertThrows(AiRuntimeException.class,()->bridge.compileEdited(ref,legacyInvocation(),editor("替换 {{record_subject}}","subject"),control())).code());
        assertEquals(encoded,jdbc.queryForObject("SELECT config_json FROM sys_ai_prompt_template_version WHERE id=41",String.class));
    }
    @Test void legacyApplicationAllowlistIsNotReplacedBySceneIdentity() throws Exception {
        legacyFixture(false);var bridge=new LegacySceneTemplates(jdbc,json,service);var ref=new TemplateVersionRef("31","41");var base=legacyInvocation();
        var wrong=new Invocation(base.actor(),"202","302",base.businessRef(),base.requestId());
        var view=bridge.inspect(ref,wrong,control());assertEquals(AiSceneLegacyTemplates.Compatibility.READ_ONLY,view.compatibility());assertTrue(view.reasons().contains("RESOURCE_SCOPE_DENIED"));
        assertThrows(AiRuntimeException.class,()->bridge.compileEdited(ref,wrong,editor("概括","subject"),control()));
    }
    @Test void sceneManagedLegacyWritesShareReferenceGuardAndReturnExplicitConflict() throws Exception {
        var d=legacyFixture(false);var factory=new org.springframework.beans.factory.support.DefaultListableBeanFactory();
        var inside=new java.util.concurrent.atomic.AtomicBoolean();var checks=new java.util.concurrent.atomic.AtomicInteger();
        factory.registerSingleton("references",new AiApplicationReferences(){
            public List<ApplicationReference> modelReferences(ModelVersionRef r){return List.of();}
            public List<ApplicationReference> templateReferences(TemplateVersionRef r){return List.of();}
            public List<ApplicationReference> resourceReferences(ResourceVersionRef r){return List.of();}
            public <T>T withReferenceGuard(java.util.function.Supplier<T> action){inside.set(true);try{return action.get();}finally{inside.set(false);}}
        });
        factory.registerSingleton("ownership",new AiSceneOwnership(){
            public boolean ownsTemplate(String id){assertTrue(inside.get());checks.incrementAndGet();return id.equals("31");}
            public boolean ownsApplication(String id){return false;}
            public void requireTemplateWritable(String id){throw new UnsupportedOperationException();}
            public void requireApplicationWritable(String id){throw new UnsupportedOperationException();}
        });
        var identity=new LowcodeIdentity(true);var manager=new DataSourceTransactionManager(jdbc.getDataSource());
        var reader=new com.xinghuiTec.ai.template.LowcodeResourceReader(new LowcodeService(jdbc,new LowcodeSchema(json),identity),identity,jdbc,manager);
        try {
            var templates=new com.xinghuiTec.ai.template.TemplateDataService(jdbc,json,reader,factory.getBeanProvider(AiApplicationReferences.class),factory.getBeanProvider(AiSceneOwnership.class));
            var actions=List.<Runnable>of(()->templates.saveTemplate(31,new com.xinghuiTec.ai.template.TemplateDataService.Draft<>(1,d)),()->templates.publish(31,1),()->templates.enabled(false,31,new com.xinghuiTec.ai.template.TemplateDataService.Enabled(1,false)),()->templates.testTemplate(31,new com.xinghuiTec.ai.template.TemplateDataService.Test(1,"201","1:11",Map.of()),true));
            for(var action:actions){var e=assertThrows(com.xinghuiTec.exception.ServiceException.class,action::run);assertEquals("SCENE_MANAGED",e.getMessage());var response=new com.xinghuiTec.ai.template.TemplateResponsePolicy().service(e);assertEquals(409,response.getStatusCode().value());assertEquals("SCENE_MANAGED",response.getBody().getMessage());}
            assertEquals(4,checks.get());assertEquals(1,jdbc.queryForObject("SELECT revision FROM sys_ai_prompt_template WHERE id=31",Integer.class));
        } finally {reader.close();}
    }
    public static String extract(String raw,String path) throws Exception {var n=new ObjectMapper().readTree(raw).get(path.substring(2));return n==null?null:n.toString();}
    public static String unquote(String raw) throws Exception {return raw==null?null:new ObjectMapper().readTree(raw).asText();}
    public static String type(String raw) throws Exception {return raw!=null&&new ObjectMapper().readTree(raw).isTextual()?"STRING":"NULL";}
    public static String search(String raw,String mode,String value,String escape,String path) throws Exception {for(var n:new ObjectMapper().readTree(raw).path("fields"))if(n.path("fieldKey").asText().equals(value))return "$.fields";return null;}
}
