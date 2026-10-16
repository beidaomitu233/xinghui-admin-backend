package com.xinghuiTec.lowcode;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.annotation.JsonDeserialize;
import com.xinghuiTec.exception.ServiceException;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.support.GeneratedKeyHolder;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import static com.xinghuiTec.lowcode.LowcodeSchema.invalid;

@Service
public class LowcodeService {
    public record Draft(String name, String formKey, String status,
                        @JsonDeserialize(using = LowcodeIntegerDeserializer.class) Integer revision,
                        LowcodeSchema.Schema schema) {}
    public record Submission(@JsonDeserialize(using = LowcodeIntegerDeserializer.class) Integer schemaVersion,
                             @JsonDeserialize(using = LowcodeIntegerDeserializer.class) Integer revision,
                             JsonNode data) {}
    public record VersionRequest(@JsonDeserialize(using = LowcodeIntegerDeserializer.class) Integer revision) {}
    public record Page(List<Map<String, Object>> records, String total, int pageNum, int pageSize) {}

    private static final Logger LOG = LoggerFactory.getLogger(LowcodeService.class);
    private final JdbcTemplate jdbc;
    private final LowcodeSchema schemas;
    private final LowcodeIdentity identity;

    public LowcodeService(JdbcTemplate jdbc, LowcodeSchema schemas, LowcodeIdentity identity) {
        this.jdbc = jdbc;
        this.schemas = schemas;
        this.identity = identity;
    }

    public Page forms(int pageNum, int pageSize, String name) {
        paging(pageNum, pageSize);
        String keyword = text(name, 100, false, "名称");
        List<Object> args = new ArrayList<>();
        String where = " WHERE is_deleted=0";
        if (!keyword.isEmpty()) {
            where += " AND name LIKE ? ESCAPE '!'";
            args.add(like(keyword));
        }
        Long total = jdbc.queryForObject("SELECT COUNT(*) FROM sys_lowcode_form" + where, Long.class, args.toArray());
        args.add(pageSize);
        args.add((pageNum - 1L) * pageSize);
        var rows = jdbc.queryForList("SELECT * FROM sys_lowcode_form" + where + " ORDER BY id DESC LIMIT ? OFFSET ?", args.toArray());
        return new Page(rows.stream().map(row -> formMap(row, false)).toList(), String.valueOf(total), pageNum, pageSize);
    }

    public Map<String, Object> form(long id) {
        return formMap(formRow(id, false), true);
    }

    @Transactional
    public String create(Draft draft) {
        validateDraft(draft);
        String key = text(draft.formKey(), 50, true, "表单标识");
        if (!key.matches("[a-z][a-z0-9_]{0,49}")) {
            throw invalid("表单标识须为小写字母、数字或下划线");
        }
        try {
            String id = insert("INSERT INTO sys_lowcode_form(name,form_key,status,draft_schema,creator) VALUES(?,?,'draft',?,?)",
                    text(draft.name(), 100, true, "名称"), key, schemas.encode(schemas.validate(draft.schema()), 262144, "表单配置"), identity.creator());
            LOG.info("Lowcode form created formId={}", id);
            return id;
        } catch (DuplicateKeyException conflict) {
            throw invalid("表单标识已存在");
        }
    }

    @Transactional
    public Map<String, Object> update(long id, Draft draft) {
        validateDraft(draft);
        revision(draft.revision());
        var row = formRow(id, false);
        if (!String.valueOf(row.get("form_key")).equals(draft.formKey())) {
            throw invalid("表单标识创建后不可修改");
        }
        String status = draft.status();
        int published = ((Number) row.get("published_version")).intValue();
        if (!(published == 0 ? "draft".equals(status) : "enabled".equals(status) || "disabled".equals(status))) {
            throw invalid("表单状态不正确");
        }
        int changed = jdbc.update("UPDATE sys_lowcode_form SET name=?,status=?,draft_schema=?,revision=revision+1,"
                        + "update_time=CURRENT_TIMESTAMP WHERE id=? AND revision=? AND is_deleted=0",
                text(draft.name(), 100, true, "名称"), status, schemas.encode(schemas.validate(draft.schema()), 262144, "表单配置"), id, draft.revision());
        changed(changed);
        LOG.info("Lowcode form updated formId={}", id);
        return form(id);
    }

    @Transactional
    public Map<String, Object> publish(long id, Integer revision) {
        revision(revision);
        var row = formRow(id, true);
        if (((Number) row.get("revision")).intValue() != revision) {
            throw conflict();
        }
        var next = schemas.validate(schemas.decode(String.valueOf(row.get("draft_schema"))));
        Map<String, String> nextTypes = new LinkedHashMap<>();
        next.fields().forEach(field -> nextTypes.put(field.fieldKey(), field.type()));
        var usedSchemas = jdbc.queryForList("SELECT DISTINCT v.schema_json FROM sys_lowcode_form_version v "
                + "JOIN sys_lowcode_record r ON r.form_id=v.form_id AND r.schema_version=v.schema_version WHERE v.form_id=?", id);
        for (var previous : usedSchemas) {
            for (var field : schemas.decode(String.valueOf(previous.get("schema_json"))).fields()) {
                if (!field.type().equals(nextTypes.get(field.fieldKey()))) {
                    throw invalid("已有记录使用字段“" + field.label() + "”，不能删除或修改其标识/类型；草稿已保留");
                }
            }
        }
        int version = ((Number) row.get("published_version")).intValue() + 1;
        jdbc.update("INSERT INTO sys_lowcode_form_version(form_id,schema_version,schema_json,creator) VALUES(?,?,?,?)",
                id, version, schemas.encode(next, 262144, "表单配置"), identity.creator());
        changed(jdbc.update("UPDATE sys_lowcode_form SET published_version=?,status='enabled',revision=revision+1,"
                + "update_time=CURRENT_TIMESTAMP WHERE id=? AND revision=? AND is_deleted=0", version, id, revision));
        LOG.info("Lowcode form published formId={} version={}", id, version);
        return form(id);
    }

    @Transactional
    public void removeForm(long id, Integer revision) {
        revision(revision);
        formRow(id, true);
        Long count = jdbc.queryForObject("SELECT COUNT(*) FROM sys_lowcode_record WHERE form_id=?", Long.class, id);
        if (count != null && count > 0) {
            throw invalid("表单已有记录，请停用以保留历史");
        }
        changed(jdbc.update("UPDATE sys_lowcode_form SET is_deleted=1,revision=revision+1,update_time=CURRENT_TIMESTAMP "
                + "WHERE id=? AND revision=? AND is_deleted=0", id, revision));
        LOG.info("Lowcode form removed formId={}", id);
    }

    public Map<String, Object> runtime(long id) {
        identity.tenant();
        var row = formRow(id, false);
        int version = ((Number) row.get("published_version")).intValue();
        if (version == 0) {
            throw invalid("表单尚未发布");
        }
        Map<String, Object> result = new LinkedHashMap<>();
        result.put("id", Long.toString(id));
        result.put("name", row.get("name"));
        result.put("status", row.get("status"));
        result.put("schemaVersion", version);
        result.put("schema", version(id, version));
        return result;
    }

    public Page records(long formId, int pageNum, int pageSize, String filterField, String filterValue) {
        paging(pageNum, pageSize);
        var runtime = runtime(formId);
        String tenant = identity.tenant();
        List<Object> args = new ArrayList<>(List.of(formId, tenant));
        String where = " WHERE form_id=? AND tenant_id=? AND is_deleted=0";
        if (filterField != null && !filterField.isBlank()) {
            var schema = (LowcodeSchema.Schema) runtime.get("schema");
            var field = schema.fields().stream().filter(f -> f.fieldKey().equals(filterField) && f.searchable())
                    .findFirst().orElseThrow(() -> invalid("查询字段未配置或不支持"));
            if (filterValue != null && !filterValue.isBlank()) {
                String value = text(filterValue, 5000, true, "查询内容");
                String path = "$." + field.fieldKey();
                if (field.type().equals("text") || field.type().equals("textarea")) {
                    where += " AND JSON_UNQUOTE(JSON_EXTRACT(data_json, ?)) LIKE ? ESCAPE '!'";
                    args.add(path);
                    args.add(like(value));
                } else if (field.type().equals("number")) {
                    BigDecimal number;
                    try {
                        number = new BigDecimal(value);
                    } catch (NumberFormatException invalid) {
                        throw invalid("查询数字不正确");
                    }
                    if (number.abs().compareTo(new BigDecimal("1000000000000")) > 0 || number.scale() > 6) {
                        throw invalid("查询数字超出范围或精度");
                    }
                    schemas.validateValue(field, schemas.data(number.toPlainString()), false);
                    where += " AND CAST(JSON_UNQUOTE(JSON_EXTRACT(data_json, ?)) AS DECIMAL(20,6))=?";
                    args.add(path);
                    args.add(number);
                } else {
                    JsonNode filter = field.type().equals("switch") ? schemas.data(value) : schemas.data(schemas.encode(value, 262144, "查询内容"));
                    schemas.validateValue(field, filter, false);
                    where += " AND JSON_UNQUOTE(JSON_EXTRACT(data_json, ?))=?";
                    args.add(path);
                    args.add(value);
                }
            }
        }
        Long total = jdbc.queryForObject("SELECT COUNT(*) FROM sys_lowcode_record" + where, Long.class, args.toArray());
        args.add(pageSize);
        args.add((pageNum - 1L) * pageSize);
        var rows = jdbc.queryForList("SELECT * FROM sys_lowcode_record" + where + " ORDER BY id DESC LIMIT ? OFFSET ?", args.toArray());
        return new Page(rows.stream().map(row -> recordMap(row, false)).toList(), String.valueOf(total), pageNum, pageSize);
    }

    public Map<String, Object> record(long formId, long recordId) {
        formRow(formId, false);
        return recordMap(recordRow(formId, recordId, identity.tenant()), true);
    }

    @Transactional
    public Map<String, Object> addRecord(long formId, Submission input) {
        String tenant = identity.tenant();
        var row = formRow(formId, true);
        int version = ((Number) row.get("published_version")).intValue();
        if (!"enabled".equals(row.get("status")) || version == 0) {
            throw invalid("表单未发布或已停用，不能新增记录");
        }
        if (input == null || input.schemaVersion() == null || input.schemaVersion() != version) {
            throw invalid("表单已更新，请重新读取后填写；输入已保留");
        }
        JsonNode data = schemas.validateData(version(formId, version), input.data(), true);
        String id = insert("INSERT INTO sys_lowcode_record(form_id,schema_version,tenant_id,creator,data_json) VALUES(?,?,?,?,?)",
                formId, version, tenant, identity.creator(), schemas.encode(data, 262144, "填写数据"));
        LOG.info("Lowcode record created formId={} recordId={}", formId, id);
        return record(formId, Long.parseLong(id));
    }

    @Transactional
    public Map<String, Object> updateRecord(long formId, long recordId, Submission input) {
        String tenant = identity.tenant();
        formRow(formId, false);
        var row = recordRow(formId, recordId, tenant);
        revision(input == null ? null : input.revision());
        int version = ((Number) row.get("schema_version")).intValue();
        if (input.schemaVersion() == null || input.schemaVersion() != version) {
            throw invalid("记录须按原发布版本编辑");
        }
        JsonNode data = schemas.validateData(version(formId, version), input.data(), false);
        changed(jdbc.update("UPDATE sys_lowcode_record SET data_json=?,revision=revision+1,update_time=CURRENT_TIMESTAMP "
                        + "WHERE id=? AND form_id=? AND tenant_id=? AND revision=? AND is_deleted=0",
                schemas.encode(data, 262144, "填写数据"), recordId, formId, tenant, input.revision()));
        LOG.info("Lowcode record updated formId={} recordId={}", formId, recordId);
        return record(formId, recordId);
    }

    @Transactional
    public void removeRecord(long formId, long recordId, Integer revision) {
        String tenant = identity.tenant();
        revision(revision);
        formRow(formId, false);
        recordRow(formId, recordId, tenant);
        changed(jdbc.update("UPDATE sys_lowcode_record SET is_deleted=1,revision=revision+1,update_time=CURRENT_TIMESTAMP "
                + "WHERE id=? AND form_id=? AND tenant_id=? AND revision=? AND is_deleted=0", recordId, formId, tenant, revision));
        LOG.info("Lowcode record removed formId={} recordId={}", formId, recordId);
    }

    private Map<String, Object> formRow(long id, boolean lock) {
        if (id < 1) {
            throw invalid("表单不存在");
        }
        var rows = jdbc.queryForList("SELECT * FROM sys_lowcode_form WHERE id=? AND is_deleted=0" + (lock ? " FOR UPDATE" : ""), id);
        if (rows.isEmpty()) {
            throw invalid("表单不存在");
        }
        return rows.get(0);
    }

    private Map<String, Object> recordRow(long formId, long recordId, String tenant) {
        var rows = jdbc.queryForList("SELECT * FROM sys_lowcode_record WHERE id=? AND form_id=? AND tenant_id=? AND is_deleted=0",
                recordId, formId, tenant);
        if (rows.isEmpty()) {
            throw invalid("记录不存在");
        }
        return rows.get(0);
    }

    private LowcodeSchema.Schema version(long formId, int version) {
        var rows = jdbc.queryForList("SELECT schema_json FROM sys_lowcode_form_version WHERE form_id=? AND schema_version=?", formId, version);
        if (rows.isEmpty()) {
            throw invalid("发布版本不存在");
        }
        return schemas.decode(String.valueOf(rows.get(0).get("schema_json")));
    }

    private Map<String, Object> formMap(Map<String, Object> row, boolean schema) {
        Map<String, Object> result = new LinkedHashMap<>();
        result.put("id", String.valueOf(row.get("id")));
        result.put("name", row.get("name"));
        result.put("formKey", row.get("form_key"));
        result.put("status", row.get("status"));
        result.put("revision", row.get("revision"));
        result.put("publishedVersion", row.get("published_version"));
        result.put("createTime", String.valueOf(row.get("create_time")));
        result.put("updateTime", String.valueOf(row.get("update_time")));
        if (schema) {
            result.put("schema", schemas.decode(String.valueOf(row.get("draft_schema"))));
        }
        return result;
    }

    private Map<String, Object> recordMap(Map<String, Object> row, boolean schema) {
        Map<String, Object> result = new LinkedHashMap<>();
        result.put("id", String.valueOf(row.get("id")));
        result.put("formId", String.valueOf(row.get("form_id")));
        result.put("schemaVersion", row.get("schema_version"));
        result.put("revision", row.get("revision"));
        result.put("tenantId", row.get("tenant_id"));
        result.put("creator", String.valueOf(row.get("creator")));
        result.put("createTime", String.valueOf(row.get("create_time")));
        result.put("updateTime", String.valueOf(row.get("update_time")));
        result.put("data", schemas.data(String.valueOf(row.get("data_json"))));
        if (schema) {
            result.put("schema", version(((Number) row.get("form_id")).longValue(), ((Number) row.get("schema_version")).intValue()));
        }
        return result;
    }

    private String insert(String sql, Object... args) {
        var key = new GeneratedKeyHolder();
        int count = jdbc.update(connection -> {
            var statement = connection.prepareStatement(sql, new String[]{"id"});
            for (int i = 0; i < args.length; i++) {
                statement.setObject(i + 1, args[i]);
            }
            return statement;
        }, key);
        if (count != 1 || key.getKey() == null) {
            throw invalid("保存失败");
        }
        return key.getKey().toString();
    }

    private void validateDraft(Draft draft) {
        if (draft == null) {
            throw invalid("请填写表单配置");
        }
        text(draft.name(), 100, true, "名称");
        schemas.validate(draft.schema());
    }

    private static String text(String value, int max, boolean required, String label) {
        String text = value == null ? "" : value.trim();
        if ((required && text.isEmpty()) || text.length() > max) {
            throw invalid(label + "必填或超出长度限制");
        }
        return text;
    }

    private static String like(String text) {
        return "%" + text.replace("!", "!!").replace("%", "!%").replace("_", "!_") + "%";
    }

    private static void paging(int page, int size) {
        if (page < 1 || page > 1000000 || size < 1 || size > 100) {
            throw invalid("页码或每页数量不正确");
        }
    }

    private static void revision(Integer revision) {
        if (revision == null || revision < 0) {
            throw invalid("请先读取当前版本");
        }
    }

    private static void changed(int count) {
        if (count != 1) {
            throw conflict();
        }
    }

    private static ServiceException conflict() {
        return invalid("内容已被修改，请重新读取后重试；输入已保留");
    }
}
