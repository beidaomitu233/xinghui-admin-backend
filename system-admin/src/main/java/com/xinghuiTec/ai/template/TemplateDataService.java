package com.xinghuiTec.ai.template;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.xinghuiTec.ai.contract.*;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.support.GeneratedKeyHolder;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.time.Instant;
import java.util.*;
import java.util.function.Supplier;
import static com.xinghuiTec.ai.contract.AiTypes.*;
import static com.xinghuiTec.ai.template.TemplateRules.invalid;

/** Persistence and approval state machine; no model/provider dependency. */
@Service
public class TemplateDataService implements AiTemplateRuntime, AiTemplatePreviewRuntime {
    public record Page(List<Map<String, Object>> records, String total, int pageNum, int pageSize) {}
    public enum OptionUnavailableReason {
        TEMPLATE_DISABLED, TEMPLATE_INVALID, RESOURCE_DISABLED, RESOURCE_UNAVAILABLE,
        RESOURCE_VERSION_UNAPPROVED, RESOURCE_FIELDS_NOT_APPROVED, APP_NOT_ALLOWED
    }
    public record TemplateOption(String templateId, String templateVersionId, int versionNumber, String name,
                                 List<AiTemplateRuntime.Variable> inputVariables, List<ResourceVersionRef> resources,
                                 boolean available, List<OptionUnavailableReason> unavailableReasons) {}
    public record OptionsPage(List<TemplateOption> records, String total, int pageNum, int pageSize) {}
    public record Draft<T>(int revision, T definition) { @Override public String toString() { return "Draft[REDACTED]"; } }
    public record Revision(int revision) {}
    public record Approval(int revision, boolean approved) {}
    public record Enabled(int revision, boolean enabled) {}
    public record Test(int revision, String appId, String businessRef, Map<String, JsonNode> inputs) {
        @Override public String toString() { return "Test[REDACTED]"; }
    }
    public record PreviewDetails(List<TemplateDefinition.Message> original, Map<String, String> inputs, Map<String, String> resources) {
        @Override public String toString() { return "PreviewDetails[REDACTED]"; }
    }
    public record TestResult(String outcome, List<String> fields, Integer schemaVersion, TemplateRules.Render preview, PreviewDetails details) {
        public TestResult(String outcome, List<String> fields, Integer schemaVersion, TemplateRules.Render preview) { this(outcome, fields, schemaVersion, preview, null); }
        @Override public String toString() { return "TestResult[REDACTED]"; }
    }
    private final JdbcTemplate jdbc;
    private final ObjectMapper mapper;
    private final LowcodeResourceReader reader;
    private final ObjectProvider<AiApplicationReferences> references;
    public TemplateDataService(JdbcTemplate jdbc, ObjectMapper mapper, LowcodeResourceReader reader,
                               ObjectProvider<AiApplicationReferences> references) {
        this.jdbc = jdbc; this.mapper = mapper; this.reader = reader; this.references = references;
    }
    private String table(boolean resource) { return resource ? "sys_ai_data_resource" : "sys_ai_prompt_template"; }
    private String foreign(boolean resource) { return resource ? "resource_id" : "template_id"; }
    /** Metadata candidates only; application test/publish must still invoke the runtime checks. */
    public OptionsPage options(int page, int size, String keyword, String appId) {
        if (page < 1 || page > 1000000 || size < 1 || size > 100 || (appId != null && !TemplateRules.id(appId))
                || (keyword != null && keyword.length() > 100)) throw new IllegalArgumentException("Invalid option filters");
        String where = ""; List<Object> args = new ArrayList<>();
        if (keyword != null && !keyword.isBlank()) {
            where = " WHERE JSON_UNQUOTE(JSON_EXTRACT(v.config_json,'$.name')) LIKE ? ESCAPE '!'";
            args.add("%" + keyword.trim().replace("!", "!!").replace("%", "!%").replace("_", "!_") + "%");
        }
        String from = " FROM sys_ai_prompt_template_version v JOIN sys_ai_prompt_template t ON t.id=v.template_id";
        String total = String.valueOf(jdbc.queryForObject("SELECT COUNT(*)" + from + where, Long.class, args.toArray()));
        args.add(size); args.add((page - 1L) * size);
        var rows = jdbc.queryForList("SELECT v.id,v.template_id,v.version_no,v.config_json,t.enabled" + from + where
                + " ORDER BY v.id DESC LIMIT ? OFFSET ?", args.toArray());
        return new OptionsPage(rows.stream().map(row -> option(row, appId)).toList(), total, page, size);
    }
    private TemplateOption option(Map<String, Object> row, String appId) {
        Set<OptionUnavailableReason> reasons = new LinkedHashSet<>();
        if (!enabledValue(row.get("enabled"))) reasons.add(OptionUnavailableReason.TEMPLATE_DISABLED);
        TemplateDefinition d;
        try { d = decode(row.get("config_json"), TemplateDefinition.class); TemplateRules.validate(d); }
        catch (com.xinghuiTec.exception.ServiceException invalid) {
            reasons.add(OptionUnavailableReason.TEMPLATE_INVALID);
            return new TemplateOption(row.get("template_id").toString(), row.get("id").toString(), ((Number) row.get("version_no")).intValue(),
                    "[配置不可用]", List.of(), List.of(), false, List.copyOf(reasons));
        }
        for (var binding : d.resources()) {
            try {
                var head = head(true, Long.parseLong(binding.resourceId()), true);
                if (!enabledValue(head.get("enabled"))) reasons.add(OptionUnavailableReason.RESOURCE_DISABLED);
                var version = versionRow(true, Long.parseLong(binding.resourceId()), Long.parseLong(binding.resourceVersionId()));
                var config = decode(version.get("config_json"), ResourceDefinition.class); TemplateRules.validate(config);
                var proof = jdbc.queryForList("SELECT id FROM sys_ai_data_resource_check WHERE id=? AND resource_id=? AND config_hash=? AND action='TEST' AND outcome='SUCCESS'",
                        version.get("test_check_id"), binding.resourceId(), version.get("config_hash"));
                if (proof.size() != 1 || version.get("approver") == null || !hash(config).equals(version.get("config_hash"))) reasons.add(OptionUnavailableReason.RESOURCE_VERSION_UNAPPROVED);
                if (!config.fields().containsAll(binding.outputs().keySet())) reasons.add(OptionUnavailableReason.RESOURCE_FIELDS_NOT_APPROVED);
                if (appId != null && !config.applications().contains(appId)) reasons.add(OptionUnavailableReason.APP_NOT_ALLOWED);
            } catch (com.xinghuiTec.exception.ServiceException missing) { reasons.add(OptionUnavailableReason.RESOURCE_UNAVAILABLE); }
        }
        Set<String> outputVariables = new HashSet<>(); d.resources().forEach(b -> outputVariables.addAll(b.outputs().values()));
        var inputs = d.variables().stream().filter(v -> !outputVariables.contains(v.name()))
                .map(v -> new AiTemplateRuntime.Variable(v.name(), VariableType.valueOf(v.type().name()), v.required(), v.hasDefault())).toList();
        return new TemplateOption(row.get("template_id").toString(), row.get("id").toString(), ((Number) row.get("version_no")).intValue(),
                d.name(), inputs, resourceRefs(d), reasons.isEmpty(), List.copyOf(reasons));
    }
    private boolean enabledValue(Object value) { return Boolean.TRUE.equals(value) || "1".equals(String.valueOf(value)); }
    public Page page(boolean resource, int page, int size) {
        if (page < 1 || page > 1000000 || size < 1 || size > 100) throw invalid("分页无效");
        var rows = jdbc.queryForList("SELECT id,name,revision,enabled," + (resource ? "state," : "")
                + "create_time,update_time FROM " + table(resource) + " ORDER BY id DESC LIMIT ? OFFSET ?", size, (page - 1L) * size);
        return new Page(rows.stream().map(this::safeIds).toList(), String.valueOf(jdbc.queryForObject("SELECT COUNT(*) FROM " + table(resource), Long.class)), page, size);
    }
    public Page legacyScenes(int page, int size) {
        if (page < 1 || page > 1000000 || size < 1 || size > 100) throw invalid("分页无效");
        var rows = jdbc.queryForList("SELECT scene_id,app_key,scene_key,published_model_id,published_revision,enabled FROM sys_ai_prompt_scene ORDER BY scene_id DESC LIMIT ? OFFSET ?", size, (page - 1L) * size);
        var records = rows.stream().map(row -> {
            var result = safeIds(row); result.put("compatibilityOnly", true); result.put("templateVersionId", null);
            result.put("versionMeaning", "旧场景当前快照；不代表不可变模板版本"); return result;
        }).toList();
        return new Page(records, String.valueOf(jdbc.queryForObject("SELECT COUNT(*) FROM sys_ai_prompt_scene", Long.class)), page, size);
    }
    public Map<String, Object> get(boolean resource, long id) {
        var row = head(resource, id, false); var result = safeIds(row);
        result.remove("draft_json"); result.put("definition", resource ? decode(row.get("draft_json"), ResourceDefinition.class) : decode(row.get("draft_json"), TemplateDefinition.class));
        result.put("versions", versions(resource, id)); return result;
    }
    public List<Map<String, Object>> versions(boolean resource, long id) {
        head(resource, id, false);
        return jdbc.queryForList("SELECT id," + foreign(resource) + ",version_no,config_hash,create_time FROM " + table(resource)
                + "_version WHERE " + foreign(resource) + "=? ORDER BY version_no DESC", id).stream().map(this::safeIds).toList();
    }
    public Map<String, Object> version(boolean resource, long id, long version) {
        var row = versionRow(resource, id, version); var result = safeIds(row);
        result.remove("config_json"); result.put("definition", resource ? decode(row.get("config_json"), ResourceDefinition.class) : decode(row.get("config_json"), TemplateDefinition.class)); return result;
    }
    @Transactional
    public String createTemplate(TemplateDefinition definition) { TemplateRules.validate(definition); return create(false, definition.name(), definition); }
    @Transactional
    public String createResource(ResourceDefinition definition) { TemplateRules.validate(definition); return create(true, definition.name(), definition); }
    private String create(boolean resource, String name, Object definition) {
        reader.currentActor();
        return insert("INSERT INTO " + table(resource) + "(name,draft_json) VALUES (?,?)", name, json(definition));
    }
    @Transactional
    public Map<String, Object> saveTemplate(long id, Draft<TemplateDefinition> draft) {
        TemplateRules.validate(draft.definition()); save(false, id, draft.revision(), draft.definition().name(), draft.definition()); return get(false, id);
    }
    @Transactional
    public Map<String, Object> saveResource(long id, Draft<ResourceDefinition> draft) {
        TemplateRules.validate(draft.definition()); save(true, id, draft.revision(), draft.definition().name(), draft.definition()); return get(true, id);
    }
    private void save(boolean resource, long id, int revision, String name, Object definition) {
        reader.currentActor();
        changed(jdbc.update("UPDATE " + table(resource) + " SET name=?,draft_json=?,revision=revision+1,tested_hash=NULL"
                + (resource ? ",state='DRAFT',submitted_hash=NULL,approved_hash=NULL,tested_check_id=NULL" : "") + " WHERE id=? AND revision=?", name, json(definition), id, revision));
    }
    @Transactional
    public TestResult testResource(long id, Test request) {
        var actor = reader.currentActor(); var row = checked(true, id, request.revision());
        var config = decode(row.get("draft_json"), ResourceDefinition.class); String hash = hash(config);
        String outcome; LowcodeResourceReader.Read read = null;
        try { read = reader.read(config, actor, request.appId(), business(request.businessRef())); outcome = "SUCCESS"; }
        catch (LowcodeResourceReader.ResourceFailure failure) { outcome = failure.code(); }
        String scope = hash(Map.of("parameters", business(request.businessRef()), "appId", request.appId(), "subject", actor.subjectId(), "tenant", actor.tenantId(), "authorities", new TreeSet<>(actor.authorities())));
        String evidenceId = evidence(id, hash, scope, "TEST", outcome, actor);
        changed(jdbc.update("UPDATE sys_ai_data_resource SET tested_hash=?,tested_check_id=?,state=?,submitted_hash=NULL,approved_hash=NULL,revision=revision+1 WHERE id=? AND revision=?",
                outcome.equals("SUCCESS") ? hash : null, evidenceId, outcome.equals("SUCCESS") ? "TESTED" : "DRAFT", id, request.revision()));
        return new TestResult(outcome, read == null ? List.of() : List.copyOf(read.fields().keySet()), read == null ? null : read.schemaVersion(), null);
    }
    @Transactional
    public Map<String, Object> submit(long id, int revision) {
        var actor = reader.currentActor(); var row = checked(true, id, revision); String hash = hash(decode(row.get("draft_json"), ResourceDefinition.class));
        if (!hash.equals(row.get("tested_hash")) || !"TESTED".equals(row.get("state"))) throw invalid("须先成功测试当前配置");
        changed(jdbc.update("UPDATE sys_ai_data_resource SET state='PENDING',submitted_hash=?,revision=revision+1 WHERE id=? AND revision=?", hash, id, revision));
        evidence(id, hash, hash(Map.of("testedCheckId", row.get("tested_check_id"))), "SUBMIT", "PENDING", actor); return get(true, id);
    }
    @Transactional
    public Map<String, Object> approve(long id, Approval approval) {
        return guarded(() -> {
            var actor = reader.currentActor(); var row = checked(true, id, approval.revision());
            var config = decode(row.get("draft_json"), ResourceDefinition.class); String hash = hash(config);
            if (!"PENDING".equals(row.get("state")) || !hash.equals(row.get("submitted_hash")) || !hash.equals(row.get("tested_hash"))) throw invalid("审批配置或测试已失效");
            if (jdbc.queryForList("SELECT id FROM sys_ai_data_resource_check WHERE id=? AND resource_id=? AND config_hash=? AND action='TEST' AND outcome='SUCCESS' FOR UPDATE", row.get("tested_check_id"), id, hash).size() != 1) throw invalid("审批缺少对应成功测试证据");
            if (approval.approved()) publishVersion(true, id, config, hash, actor.subjectId());
            changed(jdbc.update("UPDATE sys_ai_data_resource SET state=?,approved_hash=?,enabled=?,revision=revision+1 WHERE id=? AND revision=?",
                    approval.approved() ? "APPROVED" : "REJECTED", approval.approved() ? hash : null, approval.approved(), id, approval.revision()));
            evidence(id, hash, hash(Map.of("testedCheckId", row.get("tested_check_id"))), "APPROVAL", approval.approved() ? "APPROVED" : "REJECTED", actor); return get(true, id);
        });
    }
    public List<Map<String, Object>> evidence(long id) {
        head(true, id, false);
        return jdbc.queryForList("SELECT id,config_hash,scope_hash,action,outcome,subject_id,tenant_id,create_time FROM sys_ai_data_resource_check WHERE resource_id=? ORDER BY id DESC LIMIT 100", id)
                .stream().map(this::safeIds).toList();
    }
    private String evidence(long id, String hash, String scope, String action, String outcome, LowcodeResourceReader.Actor actor) {
        return insert("INSERT INTO sys_ai_data_resource_check(resource_id,config_hash,scope_hash,action,outcome,subject_id,tenant_id) VALUES (?,?,?,?,?,?,?)", id, hash, scope, action, outcome, actor.subjectId(), actor.tenantId());
    }
    @Transactional
    public TestResult testTemplate(long id, Test request, boolean persist) {
        var row = checked(false, id, request.revision()); var d = decode(row.get("draft_json"), TemplateDefinition.class);
        var actor = reader.currentActor();
        var invocation = new Invocation(new TrustedActor(actor.subjectId(), actor.tenantId(), actor.authorities(), null), request.appId(), "draft:" + request.revision(), new BusinessRef("lowcode.record", request.businessRef()), "template-test");
        final Instant deadline = Instant.now().plusSeconds(10);
        var control = new InvocationControl() { public Instant deadline() { return deadline; } public boolean cancelled() { return Thread.currentThread().isInterrupted(); } };
        var rendered = render(d, request.inputs() == null ? Map.of() : request.inputs(), invocation, control, true);
        if (persist) changed(jdbc.update("UPDATE sys_ai_prompt_template SET tested_hash=?,revision=revision+1 WHERE id=? AND revision=?", hash(d), id, request.revision()));
        Map<String, String> safeInputs = new LinkedHashMap<>(), safeResources = new LinkedHashMap<>();
        if (request.inputs() != null) request.inputs().keySet().forEach(key -> safeInputs.put(key, "[已脱敏]"));
        d.resources().forEach(b -> b.outputs().forEach((field, variable) -> safeResources.put(b.name() + "." + field + " → " + variable, "[已脱敏，缺失情况见元数据]")));
        return new TestResult("SUCCESS", List.of(), null, rendered.preview(), new PreviewDetails(d.messages(), safeInputs, safeResources));
    }
    @Transactional
    public Map<String, Object> publish(long id, int revision) {
        return guarded(() -> {
            var actor = reader.currentActor(); var row = checked(false, id, revision);
            var d = decode(row.get("draft_json"), TemplateDefinition.class); TemplateRules.validate(d); String hash = hash(d);
            if (!hash.equals(row.get("tested_hash"))) throw invalid("须先成功测试当前模板");
            checkResources(d, null);
            publishVersion(false, id, d, hash, actor.subjectId());
            changed(jdbc.update("UPDATE sys_ai_prompt_template SET enabled=1,revision=revision+1 WHERE id=? AND revision=?", id, revision));
            return get(false, id);
        });
    }
    @Transactional
    public Map<String, Object> enabled(boolean resource, long id, Enabled request) {
        return guarded(() -> {
            var row = checked(resource, id, request.revision());
            var affected = versions(resource, id).stream().flatMap(v -> references(resource, id, Long.parseLong(v.get("id").toString())).stream()).distinct().toList();
            if (request.enabled()) {
                var versions = versions(resource, id);
                if (versions.isEmpty()) throw invalid("须先发布批准版本");
                if (resource && (!"APPROVED".equals(row.get("state")) || row.get("approved_hash") == null)) throw invalid("当前配置尚未批准");
            }
            changed(jdbc.update("UPDATE " + table(resource) + " SET enabled=?,revision=revision+1 WHERE id=? AND revision=?", request.enabled(), id, request.revision()));
            var result = get(resource, id); result.put("affectedApplications", affected); return result;
        });
    }
    public List<AiApplicationReferences.ApplicationReference> references(boolean resource, long id, long version) {
        versionRow(resource, id, version);
        return resource ? ref().resourceReferences(new ResourceVersionRef(Long.toString(id), Long.toString(version)))
                : ref().templateReferences(new TemplateVersionRef(Long.toString(id), Long.toString(version)));
    }
    private AiApplicationReferences ref() {
        var value = references.getIfAvailable(); if (value == null) throw new com.xinghuiTec.exception.ServiceException("DEPENDENCY_UNAVAILABLE", com.xinghuiTec.emues.ResultCodeEnum.SERVICE_ERROR.getCode()); return value;
    }
    private <T> T guarded(Supplier<T> action) { return ref().withReferenceGuard(action); }
    private void publishVersion(boolean resource, long id, Object config, String hash, String actor) {
        Integer number = jdbc.queryForObject("SELECT COALESCE(MAX(version_no),0)+1 FROM " + table(resource) + "_version WHERE " + foreign(resource) + "=?", Integer.class, id);
        if (resource) insert("INSERT INTO sys_ai_data_resource_version(resource_id,version_no,config_json,config_hash,approver,test_check_id) VALUES (?,?,?,?,?,?)", id, number, json(config), hash, actor, head(true, id, true).get("tested_check_id"));
        else insert("INSERT INTO sys_ai_prompt_template_version(template_id,version_no,config_json,config_hash,publisher) VALUES (?,?,?,?,?)", id, number, json(config), hash, actor);
    }
    @Override public PublishedTemplate publishedSnapshot(TemplateVersionRef version, Invocation invocation, InvocationControl control) {
        control.check(); var d = published(version); checkResources(d, invocation);
        return new PublishedTemplate(version, d.variables().stream().filter(v -> d.resources().stream().noneMatch(b -> b.outputs().containsValue(v.name())))
                .map(v -> new Variable(v.name(), VariableType.valueOf(v.type().name()), v.required(), v.hasDefault())).toList(), resourceRefs(d));
    }
    @Override public Validation validateBinding(TemplateVersionRef version, Set<String> suppliedInputs, Invocation invocation, InvocationControl control) {
        var snapshot = publishedSnapshot(version, invocation, control); List<Violation> violations = new ArrayList<>();
        for (var v : snapshot.variables()) if (v.required() && !v.hasDefault() && !suppliedInputs.contains(v.name())) violations.add(new Violation(v.name(), ErrorCode.MISSING_VARIABLE));
        Set<String> names = snapshot.variables().stream().map(Variable::name).collect(java.util.stream.Collectors.toSet());
        for (String input : suppliedInputs) if (!names.contains(input)) violations.add(new Violation("inputs", ErrorCode.INVALID_INPUT));
        return new Validation(violations);
    }
    @Override public RenderedTemplate renderPublished(RenderRequest request, InvocationControl control) {
        control.check(); var d = published(request.version());
        Map<String, JsonNode> input = new LinkedHashMap<>(); request.inputs().values().forEach((k,v) -> input.put(k, mapper.valueToTree(v)));
        try {
            var result = render(d, input, request.invocation(), control, false);
            return new RenderedTemplate(request.version(), result.preview().messages().stream().map(m -> new AiMessage(m.section() == TemplateDefinition.Section.INSTRUCTION ? MessageRole.SYSTEM : MessageRole.USER, m.text())).toList(),
                    resourceRefs(d), result.notices());
        } catch (com.xinghuiTec.exception.ServiceException invalid) { throw new AiRuntimeException(ErrorCode.INVALID_INPUT); }
    }
    @Override public PublishedPreview previewPublished(RenderRequest request, InvocationControl control) {
        control.check(); var definition = published(request.version());
        Map<String, JsonNode> inputs = new LinkedHashMap<>(); request.inputs().values().forEach((key,value) -> inputs.put(key,mapper.valueToTree(value)));
        // A management preview never exposes resolved defaults or input/business values, including
        // variables whose ordinary runtime sensitivity flag is false. Persisted configuration is unchanged.
        var masked = new TemplateDefinition(definition.name(),definition.purpose(),definition.owner(),definition.messages(),
                definition.variables().stream().map(v -> new TemplateDefinition.Variable(v.name(),v.type(),v.required(),v.hasDefault(),v.defaultValue(),v.maxChars(),true,v.truncate())).toList(),
                definition.resources(),definition.maxChars());
        try {
            var rendered = render(masked,inputs,request.invocation(),control,true);
            Set<String> resourceVariables = new HashSet<>(); definition.resources().forEach(b -> resourceVariables.addAll(b.outputs().values()));
            Set<String> defaults = new HashSet<>(); rendered.notices().stream().filter(n -> n.kind() == RenderNoticeKind.DEFAULT_USED).forEach(n -> defaults.add(n.reference()));
            Map<String,String> inputSummary = new LinkedHashMap<>(), resourceSummary = new LinkedHashMap<>();
            for (var variable : definition.variables()) if (!resourceVariables.contains(variable.name())) {
                var value = inputs.get(variable.name());
                boolean absent = value == null || value.isNull() || (value.isTextual() && value.textValue().isBlank());
                inputSummary.put(variable.name(),defaults.contains(variable.name()) ? "DEFAULT_MASKED" : absent ? "OPTIONAL_EMPTY" : "SUPPLIED_MASKED");
            }
            for (var binding : definition.resources()) {
                String missing = rendered.notices().stream().filter(n -> n.kind() == RenderNoticeKind.OPTIONAL_RESOURCE_MISSING && n.reference().startsWith(binding.name()+":"))
                        .map(n -> n.reference().substring(binding.name().length()+1)).findFirst().orElse(null);
                resourceSummary.put(binding.name(),missing == null ? "AUTHORIZED_FIELDS_MASKED" : "OPTIONAL_"+missing);
            }
            control.check();
            return new PublishedPreview(request.version(),previewMessages(definition.messages()),previewMessages(rendered.preview().messages()),inputSummary,resourceSummary,rendered.notices());
        } catch (com.xinghuiTec.exception.ServiceException invalid) { throw new AiRuntimeException(ErrorCode.INVALID_INPUT); }
    }
    private List<AiMessage> previewMessages(List<TemplateDefinition.Message> messages) {
        return messages.stream().map(m -> new AiMessage(m.section() == TemplateDefinition.Section.INSTRUCTION ? MessageRole.SYSTEM : MessageRole.USER,m.text())).toList();
    }
    private TemplateDefinition published(TemplateVersionRef ref) {
        if (!TemplateRules.id(ref.templateId()) || !TemplateRules.id(ref.templateVersionId())) throw new AiRuntimeException(ErrorCode.TEMPLATE_UNAVAILABLE);
        try {
        var h = head(false, Long.parseLong(ref.templateId()), true);
        if (!Boolean.TRUE.equals(h.get("enabled")) && !"1".equals(String.valueOf(h.get("enabled")))) throw new AiRuntimeException(ErrorCode.TEMPLATE_UNAVAILABLE);
        return decode(versionRow(false, Long.parseLong(ref.templateId()), Long.parseLong(ref.templateVersionId())).get("config_json"), TemplateDefinition.class);
        } catch (com.xinghuiTec.exception.ServiceException missing) { throw new AiRuntimeException(ErrorCode.TEMPLATE_UNAVAILABLE); }
    }
    private List<ResourceVersionRef> resourceRefs(TemplateDefinition d) { return d.resources().stream().map(b -> new ResourceVersionRef(b.resourceId(), b.resourceVersionId())).toList(); }
    private ResourceDefinition resource(TemplateDefinition.Binding b) {
        try {
        var h = head(true, Long.parseLong(b.resourceId()), true);
        if (!Boolean.TRUE.equals(h.get("enabled")) && !"1".equals(String.valueOf(h.get("enabled")))) throw new AiRuntimeException(ErrorCode.RESOURCE_UNAVAILABLE);
        var d = decode(versionRow(true, Long.parseLong(b.resourceId()), Long.parseLong(b.resourceVersionId())).get("config_json"), ResourceDefinition.class);
        if (!d.fields().containsAll(b.outputs().keySet())) throw new AiRuntimeException(ErrorCode.RESOURCE_FORBIDDEN);
        return d;
        } catch (com.xinghuiTec.exception.ServiceException missing) { throw new AiRuntimeException(ErrorCode.RESOURCE_UNAVAILABLE); }
    }
    private void checkResources(TemplateDefinition d, Invocation invocation) {
        TemplateRules.validate(d);
        for (var b : d.resources()) {
            var r = resource(b);
            if (invocation != null && (!r.applications().contains(invocation.appId()) || !r.subjects().contains(invocation.actor().subjectId())
                    || !invocation.actor().authorities().contains("lowcode:record:query"))) throw new AiRuntimeException(ErrorCode.RESOURCE_FORBIDDEN);
        }
    }
    private record RenderResult(TemplateRules.Render preview, List<RenderNotice> notices) {}
    private RenderResult render(TemplateDefinition d, Map<String, JsonNode> inputs, Invocation invocation, InvocationControl control, boolean mask) {
        TemplateRules.validate(d); Map<String, JsonNode> data = new LinkedHashMap<>(); List<RenderNotice> notices = new ArrayList<>();
        TemplateRules.validateInputs(d, inputs);
        var actor = new LowcodeResourceReader.Actor(invocation.actor().subjectId(), invocation.actor().tenantId(), invocation.actor().authorities());
        Map<String, String> business = d.resources().isEmpty() ? Map.of() : business(invocation.businessRef().id());
        if (!d.resources().isEmpty() && !"lowcode.record".equals(invocation.businessRef().type())) throw new AiRuntimeException(ErrorCode.INVALID_INPUT);
        // Validate user inputs before any business read; substitute temporary typed resource values only in final pass.
        Set<String> inputNames = d.variables().stream().map(TemplateDefinition.Variable::name).collect(java.util.stream.Collectors.toSet());
        d.resources().forEach(b -> inputNames.removeAll(b.outputs().values()));
        if (!inputNames.containsAll(inputs.keySet())) throw new AiRuntimeException(ErrorCode.INVALID_INPUT);
        for (var v : d.variables()) if (inputNames.contains(v.name()) && v.required() && !v.hasDefault()
                && (!inputs.containsKey(v.name()) || inputs.get(v.name()).isNull())) throw new AiRuntimeException(ErrorCode.MISSING_VARIABLE);
        for (var b : d.resources()) {
            control.check(); var r = resource(b); Map<String, String> parameters = new LinkedHashMap<>();
            for (var entry : b.parameters().entrySet()) {
                var p = entry.getValue(); String value = switch (p.source()) {
                    case BUSINESS_REF -> business.get(p.key());
                    case ACTOR -> p.key().equals("subjectId") ? actor.subjectId() : actor.tenantId();
                    case INPUT -> inputs.get(p.key()) == null ? null : inputs.get(p.key()).asText();
                }; parameters.put(entry.getKey(), value);
            }
            // Every mapped parameter must still point at the invocation's authorized business reference.
            if (!business.equals(parameters)) throw new AiRuntimeException(ErrorCode.RESOURCE_FORBIDDEN);
            try {
                var read = reader.read(r, actor, invocation.appId(), parameters, control); control.check();
                b.outputs().forEach((field, variable) -> { if (read.fields().containsKey(field)) data.put(variable, read.fields().get(field)); });
            } catch (LowcodeResourceReader.ResourceFailure failure) {
                // Optional omission is permitted only for a legitimate empty/missing record, never a denial or timeout.
                if (!b.required() && b.omitOnMissing() && Set.of("EMPTY", "NOT_FOUND").contains(failure.code()))
                    notices.add(new RenderNotice(RenderNoticeKind.OPTIONAL_RESOURCE_MISSING, b.name() + ":" + failure.code()));
                else throw new AiRuntimeException(switch (failure.code()) { case "FORBIDDEN" -> ErrorCode.RESOURCE_FORBIDDEN; case "TIMEOUT" -> ErrorCode.RESOURCE_TIMEOUT; case "CANCELLED" -> ErrorCode.CANCELLED; default -> ErrorCode.RESOURCE_UNAVAILABLE; });
            }
        }
        control.check(); var actual = TemplateRules.render(d, inputs, data, false);
        var preview = mask ? TemplateRules.render(d, inputs, data, true) : actual;
        preview.notices().forEach(n -> notices.add(new RenderNotice(RenderNoticeKind.valueOf(n.kind()), n.reference())));
        return new RenderResult(preview, List.copyOf(notices));
    }
    static Map<String, String> business(String value) {
        String[] parts = value == null ? new String[0] : value.split(":", -1);
        if (parts.length != 2 || !TemplateRules.id(parts[0]) || !TemplateRules.id(parts[1])) throw invalid("业务引用应为formId:recordId");
        return Map.of("formId", parts[0], "recordId", parts[1]);
    }
    private Map<String, Object> checked(boolean resource, long id, int revision) {
        var row = head(resource, id, true);
        if (((Number) row.get("revision")).intValue() != revision) throw conflict(); return row;
    }
    private Map<String, Object> head(boolean resource, long id, boolean lock) {
        var rows = jdbc.queryForList("SELECT * FROM " + table(resource) + " WHERE id=?" + (lock ? " FOR UPDATE" : ""), id);
        if (rows.isEmpty()) throw new com.xinghuiTec.exception.ServiceException("NOT_FOUND", com.xinghuiTec.emues.ResultCodeEnum.DATA_ERROR.getCode()); return rows.get(0);
    }
    private Map<String, Object> versionRow(boolean resource, long id, long version) {
        var rows = jdbc.queryForList("SELECT * FROM " + table(resource) + "_version WHERE id=? AND " + foreign(resource) + "=?", version, id);
        if (rows.isEmpty()) throw new com.xinghuiTec.exception.ServiceException("NOT_FOUND", com.xinghuiTec.emues.ResultCodeEnum.DATA_ERROR.getCode()); return rows.get(0);
    }
    private Map<String, Object> safeIds(Map<String, Object> row) {
        Map<String, Object> result = new LinkedHashMap<>();
        row.forEach((key,value) -> result.put(key, value != null && (key.equals("id") || key.endsWith("_id") || key.endsWith("_time")) ? value.toString() : value)); return result;
    }
    private String json(Object value) { try { String json = mapper.writeValueAsString(value); if (json.length() > 131072) throw invalid("配置过长"); return json; } catch (com.fasterxml.jackson.core.JsonProcessingException e) { throw invalid("配置格式无效"); } }
    private <T> T decode(Object json, Class<T> type) { try { return mapper.readValue(String.valueOf(json), type); } catch (com.fasterxml.jackson.core.JsonProcessingException e) { throw invalid("配置格式无效"); } }
    String hash(Object value) {
        try { JsonNode canonical = canonical(mapper.valueToTree(value)); return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(canonical.toString().getBytes(StandardCharsets.UTF_8))); }
        catch (java.security.NoSuchAlgorithmException e) { throw new IllegalStateException(e); }
    }
    private JsonNode canonical(JsonNode node) {
        if (node.isObject()) { var sorted = mapper.createObjectNode(); var names = new TreeSet<String>(); node.fieldNames().forEachRemaining(names::add); names.forEach(n -> sorted.set(n, canonical(node.get(n)))); return sorted; }
        if (node.isArray()) { var array = mapper.createArrayNode(); node.forEach(n -> array.add(canonical(n))); return array; } return node;
    }
    private String insert(String sql, Object... args) {
        var key = new GeneratedKeyHolder(); jdbc.update(c -> { var s = c.prepareStatement(sql, new String[]{"id"}); for (int i = 0; i < args.length; i++) s.setObject(i + 1, args[i]); return s; }, key);
        if (key.getKey() == null) throw invalid("保存失败"); return key.getKey().toString();
    }
    private com.xinghuiTec.exception.ServiceException conflict() { return new com.xinghuiTec.exception.ServiceException("VERSION_CONFLICT", com.xinghuiTec.emues.ResultCodeEnum.FAIL.getCode()); }
    private void changed(int count) { if (count != 1) throw conflict(); }
}
