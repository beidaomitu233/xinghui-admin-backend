package com.xinghuiTec.ai.query;

import com.xinghuiTec.ai.contract.AiSceneDataRuntime.*;
import com.xinghuiTec.ai.contract.AiSceneQueryRuntime.*;
import com.xinghuiTec.ai.contract.AiTypes.*;
import com.xinghuiTec.lowcode.LowcodeSchema;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.ConnectionCallback;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;
import java.util.*;
import static com.xinghuiTec.ai.contract.AiSceneQueryRuntime.QueryIssue.*;

/** Explicit business policy registry. No HTTP operation can register a physical table or an ownership policy. */
@Component
public final class SqlDatasetRegistry {
    public record TablePolicy(String key, String sqlName, String label, String table, ScopeKind scope,
                       Set<String> permissions, Map<String, String> columns, String tenantColumn,
                       String ownerColumn, String deletedColumn, String policyCode) {
        public TablePolicy { permissions = Set.copyOf(permissions); columns = Collections.unmodifiableMap(new LinkedHashMap<>(columns)); }
    }
    private record PhysicalColumn(String name, String type, String comment, boolean nullable) {}
    private final JdbcTemplate jdbc;
    private final LowcodeSchema schemas;
    private final SqlActorVerifier actors;
    private final List<TablePolicy> policies;

    @Autowired
    public SqlDatasetRegistry(JdbcTemplate jdbc, LowcodeSchema schemas, SqlActorVerifier actors,
                              org.springframework.beans.factory.ObjectProvider<SqlDatasetPolicyProvider> providers) {
        this(jdbc, schemas, actors, providers.orderedStream().flatMap(p -> p.policies().stream()).toList());
    }
    /** Code-only extension seam used by business integrations and isolated policy tests, never request JSON. */
    SqlDatasetRegistry(JdbcTemplate jdbc, LowcodeSchema schemas, SqlActorVerifier actors, List<TablePolicy> extensions) {
        this.jdbc = jdbc; this.schemas = schemas; this.actors = actors;
        var values = new ArrayList<TablePolicy>();
        values.addAll(extensions);
        if (values.stream().map(TablePolicy::key).distinct().count() != values.size()) throw new IllegalArgumentException("Duplicate dataset policy");
        policies = List.copyOf(values);
    }

    Page<DatasetOption> catalog(TrustedActor actor, OptionQuery request) {
        String word = request.keyword() == null ? "" : request.keyword().strip();
        var available = new ArrayList<>(policies.stream().filter(policy -> permitted(policy, actor)).toList());
        available.addAll(genericPolicies(actor, false));
        var fixed = available.stream()
                .filter(policy -> policy.label().contains(word) || policy.sqlName().contains(word) || policy.key().contains(word))
                .map(policy -> new DatasetOption(policy.key(), policy.sqlName(), policy.label(), policy.scope(), scope(policy.scope()))).toList();
        long forms = 0;
        String conditions = " WHERE is_deleted=0 AND published_version>0 AND status IN ('enabled','disabled')";
        var parameters = new ArrayList<Object>();
        if (!word.isEmpty()) {
            conditions += " AND (name LIKE ? ESCAPE '!' OR form_key LIKE ? ESCAPE '!')";
            parameters.add(like(word)); parameters.add(like(word));
        }
        if (lowcode(actor)) forms = Objects.requireNonNull(jdbc.queryForObject("SELECT COUNT(*) FROM sys_lowcode_form" + conditions, Long.class, parameters.toArray()));
        long offset = (long) (request.pageNum() - 1) * request.pageSize();
        var records = new ArrayList<DatasetOption>();
        if (offset < fixed.size()) records.addAll(fixed.subList((int) offset, Math.min(fixed.size(), (int) offset + request.pageSize())));
        int remaining = request.pageSize() - records.size();
        long formOffset = Math.max(0, offset - fixed.size());
        if (remaining > 0 && formOffset < forms) {
            parameters.add(remaining); parameters.add(formOffset);
            records.addAll(jdbc.query("SELECT id,name FROM sys_lowcode_form" + conditions + " ORDER BY id LIMIT ? OFFSET ?",
                    (rs, index) -> new DatasetOption("lowcode." + rs.getLong(1), "form_" + rs.getLong(1), rs.getString(2),
                            ScopeKind.TENANT, scope(ScopeKind.TENANT)), parameters.toArray()));
        }
        return new Page<>(records, Long.toString(forms + fixed.size()), request.pageNum(), request.pageSize());
    }

    List<SqlDataset> resolve(QueryContext context, TrustedActor actor, InvocationControl control) {
        var keys = context.datasetKeys();
        if (keys.isEmpty()) {
            var options = catalog(actor, new OptionQuery(null, 1, 17));
            if (Long.parseLong(options.total()) > 16) throw new SqlQueryException(RESULT_LIMIT);
            keys = options.records().stream().map(DatasetOption::datasetKey).toList();
        }
        if (keys.size() > 16 || new HashSet<>(keys).size() != keys.size()) throw new SqlQueryException(PARAMETER_INVALID);
        var result = new ArrayList<SqlDataset>();
        int columns = 0;
        for (String key : keys) {
            control.check();
            var dataset = dataset(key, actor);
            columns += dataset.schema().columns().size();
            if (columns > 256) throw new SqlQueryException(RESULT_LIMIT);
            result.add(dataset);
        }
        return List.copyOf(result);
    }

    SqlDataset dataset(String key, TrustedActor actor) {
        if (key != null && key.matches("lowcode\\.[1-9][0-9]{0,18}")) return lowcode(key, actor);
        var policy = policies.stream().filter(item -> item.key().equals(key)).findFirst().orElse(null);
        if (policy == null && key != null && key.startsWith("db.") && genericAllowed(key.substring(3), actor))
            policy = genericPolicy(key.substring(3), tableLabel(key.substring(3)), true);
        if (policy == null) throw new SqlQueryException(DATASET_FORBIDDEN);
        if (!permitted(policy, actor)) throw new SqlQueryException(DATASET_FORBIDDEN);
        var physical = metadata(policy.table());
        var columns = new ArrayList<QueryColumn>();
        var projection = new ArrayList<String>();
        for (var field : policy.columns().entrySet()) {
            var actual = physical.get(field.getValue());
            if (actual == null) throw new SqlQueryException(SCHEMA_CHANGED);
            if (secret(actual.name(), actual.comment())) continue;
            String type = sqlType(actual.type());
            if (type == null) continue;
            columns.add(new QueryColumn(field.getKey(), label(actual.comment(), field.getKey()), type, actual.nullable()));
            projection.add(q(actual.name()) + " AS " + q(field.getKey()));
        }
        if (columns.isEmpty() || columns.size() > 64) throw new SqlQueryException(RESULT_LIMIT);
        var conditions = new ArrayList<String>();
        var values = new ArrayList<Object>();
        if (policy.scope() != ScopeKind.PLATFORM || policy.tenantColumn() != null) {
            requiredPhysical(physical, policy.tenantColumn());
            if (!"STRING".equals(sqlType(physical.get(policy.tenantColumn()).type()))) throw new SqlQueryException(SCHEMA_CHANGED);
            conditions.add("BINARY " + q(policy.tenantColumn()) + "=?"); values.add(actors.tenant(actor));
        }
        if (policy.scope() == ScopeKind.PERSONAL) {
            requiredPhysical(physical, policy.ownerColumn());
            String type = sqlType(physical.get(policy.ownerColumn()).type());
            if (!Set.of("INTEGER", "STRING").contains(type)) throw new SqlQueryException(SCHEMA_CHANGED);
            conditions.add((type.equals("STRING") ? "BINARY " : "") + q(policy.ownerColumn()) + "=?");
            values.add(type.equals("INTEGER") ? Long.valueOf(actor.subjectId()) : actor.subjectId());
        }
        if (policy.deletedColumn() != null) {
            requiredPhysical(physical, policy.deletedColumn());
            String type = sqlType(physical.get(policy.deletedColumn()).type());
            if (!Set.of("INTEGER", "STRING", "BOOLEAN").contains(type)) throw new SqlQueryException(SCHEMA_CHANGED);
            conditions.add(q(policy.deletedColumn()) + "=?"); values.add(type.equals("STRING") ? "0" : 0);
        }
        String sql = "SELECT " + String.join(",", projection) + " FROM " + q(policy.table())
                + (conditions.isEmpty() ? "" : " WHERE " + String.join(" AND ", conditions));
        var guards = new ArrayList<PhysicalColumn>();
        for (String name : Arrays.asList(policy.tenantColumn(), policy.ownerColumn(), policy.deletedColumn()))
            if (name != null) guards.add(physical.get(name));
        var physicalKeys = primaryKeys(policy.table());
        var mappings = policy.columns();
        var primaryKeys = physicalKeys.stream().map(name -> mappings.entrySet().stream()
                .filter(entry -> entry.getValue().equals(name)).map(Map.Entry::getKey).findFirst().orElse(""))
                .filter(name -> columns.stream().anyMatch(column -> column.name().equals(name))).toList();
        if(primaryKeys.size()!=physicalKeys.size())primaryKeys=List.of();
        String policyCode = policy.policyCode() + "@" + SqlFingerprints.hash(List.of(policy.scope(), policy.table(),
                policy.key().startsWith("db.") ? Map.of() : policy.columns(), guards, physicalKeys));
        String fingerprint = fingerprint(policyCode, columns);
        return new SqlDataset(new DatasetSchema(policy.key(), policy.sqlName(), policy.label(), "已登记业务数据集",
                policy.scope(), scope(policy.scope()), columns, fingerprint), policyCode, sql, values,
                primaryKeys);
    }

    private SqlDataset lowcode(String key, TrustedActor actor) {
        if (!lowcode(actor)) throw new SqlQueryException(DATASET_FORBIDDEN);
        long id;
        try { id = Long.parseLong(key.substring(8)); }
        catch (NumberFormatException invalid) { throw new SqlQueryException(DATASET_FORBIDDEN); }
        var forms = jdbc.queryForList("SELECT name,published_version FROM sys_lowcode_form WHERE id=? AND is_deleted=0 "
                + "AND published_version>0 AND status IN ('enabled','disabled')", id);
        if (forms.size() != 1) throw new SqlQueryException(DATASET_FORBIDDEN);
        var versions = jdbc.queryForList("SELECT schema_version,schema_json FROM sys_lowcode_form_version WHERE form_id=? ORDER BY schema_version LIMIT 129", id);
        if (versions.isEmpty() || versions.size() > 128) throw new SqlQueryException(RESULT_LIMIT);
        int currentVersion = ((Number) forms.get(0).get("published_version")).intValue();
        var latest = versions.stream().filter(row -> ((Number) row.get("schema_version")).intValue() == currentVersion).findFirst()
                .orElseThrow(() -> new SqlQueryException(SCHEMA_CHANGED));
        var schema = schemas.decode(String.valueOf(latest.get("schema_json")));
        var columns = new ArrayList<QueryColumn>();
        columns.add(new QueryColumn("id", "记录编号", "INTEGER", false));
        var projection = new ArrayList<>(List.of("r.id AS id"));
        for (var field : schema.fields()) {
            if (secret(field.fieldKey(), field.label())) continue;
            String type = field.type().equals("number") ? "DECIMAL" : field.type().equals("switch") ? "BOOLEAN"
                    : field.type().equals("multiselect") ? "JSON" : "STRING";
            var compatible = new ArrayList<String>();
            for (var version : versions) {
                var original = schemas.decode(String.valueOf(version.get("schema_json"))).fields().stream()
                        .filter(item -> item.fieldKey().equals(field.fieldKey())).findFirst();
                if (original.isPresent()) {
                    if (!original.get().type().equals(field.type()) || secret(original.get().fieldKey(), original.get().label()))
                        throw new SqlQueryException(SCHEMA_CHANGED);
                    compatible.add(Integer.toString(((Number) version.get("schema_version")).intValue()));
                }
            }
            if (compatible.isEmpty()) throw new SqlQueryException(SCHEMA_CHANGED);
            String json = "JSON_EXTRACT(r.data_json,'$." + identifier(field.fieldKey()) + "')";
            String expression;
            if (type.equals("DECIMAL")) expression = "CASE WHEN JSON_TYPE(" + json + ") IN ('INTEGER','DOUBLE','DECIMAL') THEN CAST(JSON_UNQUOTE(" + json + ") AS DECIMAL(65,20)) END";
            else if (type.equals("BOOLEAN")) expression = "CASE WHEN JSON_TYPE(" + json + ")='BOOLEAN' THEN (JSON_UNQUOTE(" + json + ")='true') END";
            else if (type.equals("JSON")) expression = "CASE WHEN JSON_TYPE(" + json + ")='ARRAY' THEN " + json + " END";
            else expression = "CASE WHEN JSON_TYPE(" + json + ")='STRING' THEN JSON_UNQUOTE(" + json + ") END";
            projection.add("CASE WHEN r.schema_version IN (" + String.join(",", compatible) + ") THEN " + expression + " END AS " + q(field.fieldKey()));
            columns.add(new QueryColumn(field.fieldKey(), field.label(), type, true));
        }
        if (columns.size() > 64) throw new SqlQueryException(RESULT_LIMIT);
        var physical = metadata("sys_lowcode_record");
        for (String name : List.of("id", "form_id", "schema_version", "tenant_id", "is_deleted", "data_json")) requiredPhysical(physical, name);
        if (!"STRING".equals(sqlType(physical.get("tenant_id").type())) || !"INTEGER".equals(sqlType(physical.get("is_deleted").type())))
            throw new SqlQueryException(SCHEMA_CHANGED);
        String policy = "LOWCODE_ROLE_TENANT_V1@" + SqlFingerprints.hash(List.of("sys_lowcode_record",
                List.of("id", "form_id", "schema_version", "tenant_id", "is_deleted", "data_json").stream().map(physical::get).toList()));
        String sql = "SELECT " + String.join(",", projection) + " FROM sys_lowcode_record r "
                + "WHERE r.form_id=? AND BINARY r.tenant_id=? AND r.is_deleted=0 AND JSON_VALID(r.data_json)";
        return new SqlDataset(new DatasetSchema(key, "form_" + id, String.valueOf(forms.get(0).get("name")),
                "当前已发布表单的可读字段；旧记录未声明的字段为NULL", ScopeKind.TENANT, scope(ScopeKind.TENANT), columns,
                fingerprint(policy, columns)), policy, sql, List.of(id, actors.tenant(actor)), List.of("id"));
    }

    private boolean permitted(TablePolicy policy, TrustedActor actor) {
        return actor.authorities().containsAll(policy.permissions()) && (policy.scope() != ScopeKind.PLATFORM || actors.platform(actor));
    }
    Object currentRecord(SqlDataset dataset, BusinessRef ref, InvocationControl control) {
        if (ref == null || dataset.primaryKey().size() != 1) throw new SqlQueryException(CONTEXT_REQUIRED);
        String key = dataset.primaryKey().get(0);
        Object value;
        if (dataset.schema().datasetKey().startsWith("lowcode.")) {
            String[] ids = ref.id().split(":", -1);
            if (!ref.type().equals("lowcode.record") || ids.length != 2
                    || !ids[0].equals(dataset.schema().datasetKey().substring(8)) || !ids[1].matches("[1-9][0-9]{0,18}"))
                throw new SqlQueryException(CONTEXT_REQUIRED);
            value = ids[1];
        } else {
            if (!ref.type().equals("database.record") || !ref.id().matches("db1\\.[A-Za-z0-9_-]{1,2044}")) throw new SqlQueryException(CONTEXT_REQUIRED);
            try {
                byte[] bytes = Base64.getUrlDecoder().decode(ref.id().substring(4));
                if (bytes.length > 1536) throw new SqlQueryException(CONTEXT_REQUIRED);
                var parser = new com.fasterxml.jackson.databind.ObjectMapper().enable(com.fasterxml.jackson.core.JsonParser.Feature.STRICT_DUPLICATE_DETECTION)
                        .enable(com.fasterxml.jackson.databind.DeserializationFeature.FAIL_ON_TRAILING_TOKENS);
                var root = parser.readTree(bytes);
                String source = dataset.schema().datasetKey();
                if (!root.isObject() || root.size() != 2 || !root.path("sourceKey").asText().equals(source)
                        || !root.path("key").isObject() || root.path("key").size() != 1 || !root.path("key").has(key)) throw new SqlQueryException(CONTEXT_REQUIRED);
                var node = root.path("key").get(key);
                if (!node.isTextual() && !node.isNumber()) throw new SqlQueryException(CONTEXT_REQUIRED);
                value = node.asText();
            } catch (SqlQueryException safe) { throw safe; }
            catch (Exception invalid) { throw new SqlQueryException(CONTEXT_REQUIRED); }
        }
        try {
            if (dataset.field(key).type().equals("INTEGER")) value = new java.math.BigDecimal(value.toString()).toBigIntegerExact();
            else if (!dataset.field(key).type().equals("STRING") || value.toString().length() > 256) throw new SqlQueryException(CONTEXT_REQUIRED);
        } catch (NumberFormatException | ArithmeticException invalid) { throw new SqlQueryException(CONTEXT_REQUIRED); }
        control.check();
        var parameters = new ArrayList<>(dataset.sourceParameters()); parameters.add(value);
        var rows = jdbc.queryForList("SELECT " + q(key) + " FROM (" + dataset.sourceSql() + ") scoped_record WHERE " + q(key) + "=? LIMIT 2", parameters.toArray());
        if (rows.size() != 1) throw new SqlQueryException(DATASET_FORBIDDEN);
        return rows.get(0).get(key);
    }
    private boolean lowcode(TrustedActor actor) {
        return actor.authorities().containsAll(Set.of("lowcode:record:list", "lowcode:record:query"));
    }
    private List<TablePolicy> genericPolicies(TrustedActor actor, boolean columns) {
        if (!actors.platform(actor) || !actor.authorities().contains("ai:data:database:read")) return List.of();
        return jdbc.execute((ConnectionCallback<List<TablePolicy>>) connection -> {
            var result = new ArrayList<TablePolicy>();
            String catalog = connection.getCatalog();
            try (var tables = connection.getMetaData().getTables(catalog, null, "%", new String[]{"TABLE"})) {
                int scanned = 0;
                while (tables.next()) {
                    if (++scanned > 2000) throw new SqlQueryException(RESULT_LIMIT);
                    String name = tables.getString("TABLE_NAME");
                    if (!Objects.equals(catalog, tables.getString("TABLE_CAT")) || !genericAllowed(name, actor)) continue;
                    result.add(genericPolicy(name, label(tables.getString("REMARKS"), name), columns));
                }
            }
            result.sort(Comparator.comparing(TablePolicy::key));
            return List.copyOf(result);
        });
    }
    private TablePolicy genericPolicy(String name, String label, boolean loadColumns) {
        var fields = new LinkedHashMap<String, String>();
        String tenant = null, deleted = null;
        if (loadColumns) for (var column : metadata(name).values()) {
            String normalized = column.name().replace("_", "").toLowerCase(Locale.ROOT);
            if (normalized.equals("tenantid")) { if (tenant != null) throw new SqlQueryException(SCHEMA_CHANGED); tenant = column.name(); }
            if (Set.of("isdeleted", "deleted", "delflag").contains(normalized)) {
                if (deleted != null) throw new SqlQueryException(SCHEMA_CHANGED); deleted = column.name();
            }
            if (!secret(column.name(), column.comment()) && sqlType(column.type()) != null
                    && column.name().matches("[A-Za-z_][A-Za-z0-9_]{0,63}")) fields.put(column.name(), column.name());
        }
        return new TablePolicy("db." + name, name, label, name, ScopeKind.PLATFORM,
                Set.of("ai:data:database:read"), fields, tenant, null, deleted, "PLATFORM_DATABASE_READ_V1");
    }
    private boolean genericAllowed(String name, TrustedActor actor) {
        if (!actors.platform(actor) || !actor.authorities().contains("ai:data:database:read")
                || name == null || !name.matches("[A-Za-z_][A-Za-z0-9_]{0,63}")) return false;
        String n = name.toLowerCase(Locale.ROOT);
        if (List.of("sys_ai_", "sys_lowcode_", "sys_user", "sys_role", "sys_menu", "sys_tenant", "sys_oss",
                "sys_config", "sys_business_config", "sys_oper_log", "sys_log", "sys_notification", "sys_notice", "sys_news",
                "sys_wechat", "sys_job", "flyway").stream().anyMatch(n::startsWith) || n.contains("session") || secret(n, "")) return false;
        String prefix = switch (n) { case "sys_dict_type", "sys_dict_data" -> "system:dict:"; default -> null; };
        return prefix == null || actor.authorities().contains(prefix + "list") || actor.authorities().contains(prefix + "query");
    }
    private static String label(String comment, String fallback) {
        if (comment == null || comment.isBlank()) return fallback;
        return comment.replaceAll("[\\p{Cntrl}\\u2028\\u2029]", " ").substring(0, Math.min(100, comment.length()));
    }
    private Map<String, PhysicalColumn> metadata(String table) {
        return jdbc.execute((ConnectionCallback<Map<String, PhysicalColumn>>) connection -> {
            String catalog = connection.getCatalog();
            var result = new LinkedHashMap<String, PhysicalColumn>();
            try (var fields = connection.getMetaData().getColumns(catalog, null, table, null)) {
                while (fields.next()) {
                    if (!table.equals(fields.getString("TABLE_NAME")) || !Objects.equals(catalog, fields.getString("TABLE_CAT"))) continue;
                    result.put(fields.getString("COLUMN_NAME"), new PhysicalColumn(fields.getString("COLUMN_NAME"), fields.getString("TYPE_NAME"),
                            fields.getString("REMARKS"), fields.getInt("NULLABLE") != java.sql.DatabaseMetaData.columnNoNulls));
                    if (result.size() > 512) throw new SqlQueryException(RESULT_LIMIT);
                }
            }
            if (result.isEmpty()) throw new SqlQueryException(SCHEMA_CHANGED);
            return result;
        });
    }
    private List<String> primaryKeys(String table) {
        return jdbc.execute((ConnectionCallback<List<String>>) connection -> {
            String catalog = connection.getCatalog(); var keys = new TreeMap<Integer,String>();
            try(var rows=connection.getMetaData().getPrimaryKeys(catalog,null,table)) {
                while(rows.next()) {
                    if(!table.equals(rows.getString("TABLE_NAME"))||!Objects.equals(catalog,rows.getString("TABLE_CAT")))continue;
                    if(keys.size()>16)throw new SqlQueryException(RESULT_LIMIT);
                    keys.put(rows.getInt("KEY_SEQ"),rows.getString("COLUMN_NAME"));
                }
            }
            return List.copyOf(keys.values());
        });
    }
    private String tableLabel(String table) {
        return jdbc.execute((ConnectionCallback<String>) connection -> {
            String catalog=connection.getCatalog();
            try(var rows=connection.getMetaData().getTables(catalog,null,table,new String[]{"TABLE"})) {
                while(rows.next())if(table.equals(rows.getString("TABLE_NAME"))&&Objects.equals(catalog,rows.getString("TABLE_CAT")))
                    return label(rows.getString("REMARKS"),table);
            }
            throw new SqlQueryException(DATASET_FORBIDDEN);
        });
    }
    private static void requiredPhysical(Map<String, PhysicalColumn> physical, String name) {
        if (name == null || !physical.containsKey(name)) throw new SqlQueryException(SCHEMA_CHANGED);
    }
    static String fingerprint(String policy, List<QueryColumn> columns) {
        return SqlFingerprints.hash(List.of(policy, columns.stream().map(c -> List.of(c.name(), c.type(), c.nullable())).toList()));
    }
    static String scope(ScopeKind scope) {
        return switch (scope) {
            case PLATFORM -> "当前平台身份及业务读取权限允许的数据";
            case TENANT -> "当前租户内具有列表与查询权限的数据，租户及软删除范围由服务器强制限定";
            case PERSONAL -> "当前租户内属于当前用户的数据，个人归属由已登记服务端政策强制限定";
        };
    }
    static boolean secret(String name, String comment) {
        String value = name == null ? "" : name.replace("_", "").replace("-", "").toLowerCase(Locale.ROOT);
        return value.matches(".*(password|passwd|secret|token|credential|privatekey|apikey|accesskey|authcode|salt|nonce|cipher|encrypted).*" )
                || Set.of("pwd", "authorization").contains(value)
                || comment != null && comment.matches("(?s).*(密码|密钥|凭据|令牌|私钥|验证码).*" );
    }
    private static String sqlType(String type) {
        String value = type.toLowerCase(Locale.ROOT);
        if (value.contains("binary") || value.contains("blob")) return null;
        if (value.contains("int")) return "INTEGER";
        if (value.contains("decimal") || value.contains("numeric") || value.contains("double") || value.contains("float")) return "DECIMAL";
        if (value.contains("bool") || value.equals("bit")) return "BOOLEAN";
        if (value.contains("date") || value.contains("time")) return "DATETIME";
        if (value.contains("char") || value.contains("text")) return "STRING";
        return null;
    }
    private static String identifier(String value) {
        if (value == null || !value.matches("[A-Za-z_][A-Za-z0-9_]{0,63}")) throw new SqlQueryException(SCHEMA_CHANGED);
        return value;
    }
    private static String q(String value) { return "`" + identifier(value) + "`"; }
    private static String like(String value) { return "%" + value.replace("!", "!!").replace("%", "!%").replace("_", "!_") + "%"; }
}
