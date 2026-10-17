package com.xinghuiTec.ai.scenedata;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.*;
import com.xinghuiTec.ai.contract.*;
import com.xinghuiTec.ai.contract.AiSceneDataRuntime.*;
import com.xinghuiTec.ai.contract.AiTypes.*;
import com.xinghuiTec.lowcode.*;
import com.xinghuiTec.security.PlatformAccessService;
import org.springframework.jdbc.core.*;
import org.springframework.stereotype.Component;
import java.sql.*;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.*;
/** Invoked inside SceneDataService's authenticated, readonly, deadline-bound worker transaction. */
@Component
public class DatabaseBindingReader {
    public static final String READ = "ai:data:database:read";
    private static final int MAX_BYTES = 262144;
    private final JdbcTemplate jdbc;
    private final ObjectMapper json;
    private final LowcodeService lowcode;
    private final LowcodeSchema schemas;
    private final PlatformAccessService platform;

    public DatabaseBindingReader(JdbcTemplate jdbc, ObjectMapper json, LowcodeService lowcode, LowcodeSchema schemas, PlatformAccessService platform) {
        this.jdbc = jdbc;
        this.json = json;
        this.lowcode = lowcode;
        this.schemas = schemas;
        this.platform = platform;
    }
    private record Column(String name, String comment, String type, int jdbcType, boolean nullable, boolean pk) {
    }
    private record Table(String name, String comment, List<Column> columns, List<String> pk) {
    }
    private record Loaded(List<Map<String, Object>> rows, boolean truncated, List<Notice> notices) {
        Loaded(List<Map<String, Object>> rows, boolean truncated) {
            this(rows, truncated, List.of());
        }
    }

    public Page<DatabaseTable> tables(OptionQuery query, TrustedActor actor) {
        databasePermission(actor);
        var all = allTables().stream().filter(t -> allowedTable(t.name()) && knownPermission(t.name(), actor)).filter(t -> query.keyword() == null || (t.name()+" "+t.comment()).toLowerCase(Locale.ROOT).contains(query.keyword().toLowerCase(Locale.ROOT))).toList();
        int start = (int)Math.min(all.size(), (query.pageNum()-1L)*query.pageSize());
        var selected = all.subList(start, Math.min(all.size(), start+query.pageSize()));
        var records = new ArrayList<DatabaseTable>();
        for (var t:selected) {
            if (!knownPermission(t.name(), actor))continue;
            var full = table("db."+t.name(), actor);
            records.add(new DatabaseTable("db."+t.name(), t.name(), label(t.comment(), t.name()), t.comment(), full.pk()));
        }
        // Filter permissions before publishing totals; inaccessible tables are not counted.
        long total = all.stream().filter(t -> knownPermission(t.name(), actor)).count();
        return new Page<>(records, Long.toString(total), query.pageNum(), query.pageSize());
    }

    public DatabaseFields fields(String source, TrustedActor actor) {
        if (source != null && source.startsWith("lowcode."))return lowcodeFields(source, actor);
        var t = table(source, actor);
        return new DatabaseFields(source, t.columns().stream().filter(this::readable).map(this::wire).toList(), t.pk());
    }

    public List<DatabaseRelation> relations(String source, TrustedActor actor) {
        var sourceTable = table(source, actor);
        var result = new ArrayList<DatabaseRelation>();
        var candidates = jdbc.execute((ConnectionCallback<List<String[]>>)c -> {
            var list = new ArrayList<String[]>();
            var m = c.getMetaData();
            for (boolean imported:new boolean[] {
                true, false
            }
            )try (var r = imported?m.getImportedKeys(c.getCatalog(), null, sourceTable.name()):m.getExportedKeys(c.getCatalog(), null, sourceTable.name())) {
                while (r.next()) {
                    if (list.size() >= 512)throw failure(ErrorCode.RESOURCE_UNAVAILABLE);
                    list.add(new String[] {
                        Optional.ofNullable(r.getString("FK_NAME")).orElse("relation"), r.getString("PKTABLE_NAME"), r.getString("PKCOLUMN_NAME"), r.getString("FKTABLE_NAME"), r.getString("FKCOLUMN_NAME")
                    });
                }
            }
            return list;
        });
        for (var r:candidates) {
            if (!allowedTable(r[1]) || !allowedTable(r[3]) || !knownPermission(r[1], actor) || !knownPermission(r[3], actor))continue;
            long components = candidates.stream().filter(x -> x[0].equals(r[0]) && x[1].equals(r[1]) && x[3].equals(r[3])).count();
            if (components != 1)continue;
            var parent = table("db."+r[1], actor);
            var child = table("db."+r[3], actor);
            if (!isReadable(parent, r[2]) || !isReadable(child, r[4]))continue;
            result.add(new DatabaseRelation(r[0], "db."+r[1], r[2], "db."+r[3], r[4], true));
            if (result.size() == 100)break;
        }
        return List.copyOf(result);
    }

    public List<BindingSnapshot> compile(List<DataBinding> bindings, TrustedActor actor) {
        var result = new ArrayList<BindingSnapshot>();
        var previous = new LinkedHashMap<String, BindingSnapshot>();
        for (var b:bindings) {
            check(b.variable().matches("[\\p{L}_][\\p{L}\\p{N}_.]{0,63}") && !b.variable().equals("user_request") && !previous.containsKey(b.variable()));
            var meta = fields(b.sourceKey(), actor);
            var selected = selected(b.fields(), meta.fields());
            check(!selected.isEmpty());
            for (var f:b.filters()) {
                var col = find(meta.fields(), f.field());
                filterValue(f, col);
            }
            if (b.mode() == ReadMode.FIELD)check(selected.size() == 1);
            if (b.mode() == ReadMode.FIELD || b.mode() == ReadMode.ROW) {
                check(b.row() != null && b.relation() == null && b.filters().isEmpty());
                if (!b.row().currentRecord())keys(b.row().key(), meta.primaryKey());
            }
            else check(b.row() == null);
            if (b.mode() == ReadMode.CHILDREN) {
                check(b.relation() != null);
                var p = previous.get(b.relation().parentVariable());
                check(p != null && p.binding().mode() == ReadMode.ROW);
                var parent = fields(p.binding().sourceKey(), actor);
                var pc = find(parent.fields(), b.relation().parentField());
                var cc = find(meta.fields(), b.relation().childField());
                check(typeGroup(pc.type()).equals(typeGroup(cc.type())) && !typeGroup(pc.type()).equals("complex"));
            }
            else check(b.relation() == null);
            var snap = new BindingSnapshot(b, selected, fingerprint(selected));
            result.add(snap);
            previous.put(b.variable(), snap);
        }
        return List.copyOf(result);
    }

    public BindingPreview preview(BindingPreviewRequest request, TrustedActor actor, InvocationControl control) {
        return read(compile(request.bindings(), actor), request.businessRef(), actor, control);
    }

    public void authorize(List<BindingSnapshot> snapshots, BusinessRef business, TrustedActor actor, InvocationControl control) {
        // Permissions/schema recheck only. Record reads are performed by render under the same scope.
        for (var s:snapshots) {
            control.check();
            verify(s, actor);
            var b = s.binding();
            if (b.row() != null) {
                var pk = fields(b.sourceKey(), actor).primaryKey();
                var key = b.row().currentRecord()?currentKeys(b.sourceKey(), business, pk):b.row().key();
                if (b.sourceKey().startsWith("lowcode.")) {
                    keys(key, pk);
                    var rows = jdbc.queryForList("SELECT v.schema_json FROM sys_lowcode_record r JOIN sys_lowcode_form_version v ON v.form_id=r.form_id AND v.schema_version=r.schema_version WHERE r.id=? AND r.form_id=? AND r.tenant_id=? AND r.is_deleted=0", key.get("id"), formId(b.sourceKey()), actor.tenantId());
                    if (rows.size() != 1)throw failure(ErrorCode.RESOURCE_FORBIDDEN);
                    var original = schemas.decode(String.valueOf(rows.get(0).get("schema_json")));
                    for (var pinned:s.fields())if (!pinned.field().equals("id")) {
                        var field = original.fields().stream().filter(f -> f.fieldKey().equals(pinned.field())).findFirst().orElse(null);
                        if (field != null && (!field.type().equals(pinned.type()) || secret(field.fieldKey(), field.label())))throw failure(ErrorCode.RESOURCE_UNAVAILABLE);
                    }
                }
                else if (load(b.sourceKey(), pk, List.of(), key, 1, actor, control).rows().size() != 1)throw failure(ErrorCode.RESOURCE_FORBIDDEN);
            }
            else if (b.sourceKey().startsWith("lowcode."))permission(actor, "lowcode:record:list");
        }
    }

    public BindingPreview read(List<BindingSnapshot> snapshots, BusinessRef business, TrustedActor actor, InvocationControl control) {
        var values = new LinkedHashMap<String, Object>();
        var rawParents = new HashMap<String, Map<String, Object>>();
        var reads = new ArrayList<ReadReceipt>();
        var notices = new ArrayList<Notice>();
        for (var s:snapshots) {
            control.check();
            verify(s, actor);
            var b = s.binding();
            var projected = s.fields().stream().map(DatabaseField::field).toList();
            var queryFields = new LinkedHashSet<>(projected);
            for (var child:snapshots)if (child.binding().relation() != null && child.binding().relation().parentVariable().equals(b.variable()))queryFields.add(child.binding().relation().parentField());
            Map<String, Object> key = null;
            var filters = new ArrayList<>(b.filters());
            if (b.row() != null)key = b.row().currentRecord()?currentKeys(b.sourceKey(), business, fields(b.sourceKey(), actor).primaryKey()):b.row().key();
            if (b.relation() != null) {
                var parent = rawParents.get(b.relation().parentVariable());
                check(parent != null);
                Object join = parent.get(b.relation().parentField());
                if (join == null) {
                    values.put(b.variable(), List.of());
                    reads.add(new ReadReceipt(b.variable(), b.sourceKey(), 0, false));
                    continue;
                }
                filters.add(new DataFilter(b.relation().childField(), FilterOperator.EQ, join));
            }
            Loaded loaded = load(b.sourceKey(), List.copyOf(queryFields), filters, key, b.row() != null?1:b.limit(), actor, control);
            if (b.row() != null && loaded.rows().size() != 1)throw failure(ErrorCode.NOT_FOUND);
            var projectedRows = loaded.rows().stream().map(row -> project(row, projected)).toList();
            Object value;
            if (b.mode() == ReadMode.FIELD)value = projectedRows.get(0).get(projected.get(0));
            else if (b.mode() == ReadMode.ROW) {
                value = projectedRows.get(0);
                rawParents.put(b.variable(), loaded.rows().get(0));
            }
            else value = projectedRows;
            values.put(b.variable(), value);
            reads.add(new ReadReceipt(b.variable(), b.sourceKey(), loaded.rows().size(), loaded.truncated()));
            notices.addAll(loaded.notices());
            if (loaded.truncated())notices.add(new Notice("DATA_TRUNCATED", b.variable()));
            bounded(values);
        }
        return new BindingPreview(values, reads, notices);
    }

    public DatabaseRecords records(DatabaseRecordQuery request, TrustedActor actor, InvocationControl control) {
        var meta = fields(request.sourceKey(), actor);
        check(!meta.primaryKey().isEmpty());
        var names = meta.fields().stream().map(DatabaseField::field).toList();
        var loaded = load(request.sourceKey(), names, request.filters(), null, request.limit(), actor, control);
        var records = new ArrayList<DatabaseRecord>();
        for (var row:loaded.rows()) {
            var key = new LinkedHashMap<String, Object>();
            for (String pk:meta.primaryKey())key.put(pk, candidateKey(row.get(pk)));
            String label = row.values().stream().filter(Objects::nonNull).map(Object::toString).findFirst().orElse("记录");
            if (label.length()>80)label = label.substring(0, 80);
            records.add(new DatabaseRecord(key, label, row, reference(request.sourceKey(), key)));
        }
        bounded(records.stream().map(DatabaseRecord::values).toList());
        return new DatabaseRecords(records, loaded.truncated());
    }

    private void verify(BindingSnapshot snapshot, TrustedActor actor) {
        var current = fields(snapshot.binding().sourceKey(), actor);
        var pinned = selected(snapshot.fields().stream().map(DatabaseField::field).toList(), current.fields());
        if (!fingerprint(pinned).equals(snapshot.schemaFingerprint()))throw failure(ErrorCode.RESOURCE_UNAVAILABLE);
    }

    private List<DatabaseField> selected(List<String> requested, List<DatabaseField> fields) {
        check(fields.size() <= 512);
        var names = requested.isEmpty()?fields.stream().map(DatabaseField::field).toList():requested;
        check(names.size() <= 64 && new HashSet<>(names).size() == names.size());
        return names.stream().map(n -> find(fields, n)).toList();
    }

    private DatabaseField find(List<DatabaseField> fields, String name) {
        return fields.stream().filter(f -> f.field().equals(name)).findFirst().orElseThrow(() -> failure(ErrorCode.RESOURCE_FORBIDDEN));
    }

    private DatabaseFields lowcodeFields(String source, TrustedActor actor) {
        permission(actor, "lowcode:record:query");
        String id = formId(source);
        var runtime = lowcode.runtime(Long.parseLong(id));
        if (!"enabled".equals(runtime.get("status")))throw failure(ErrorCode.RESOURCE_UNAVAILABLE);
        var schema = (LowcodeSchema.Schema)runtime.get("schema");
        var f = new ArrayList<DatabaseField>();
        f.add(new DatabaseField("id", "记录编号", "", "BIGINT", false, true));
        for (var field:schema.fields())if (!secret(field.fieldKey(), field.label()))f.add(new DatabaseField(field.fieldKey(), field.label(), field.label(), field.type(), !field.required(), false));
        return new DatabaseFields(source, f, List.of("id"));
    }

    private Loaded load(String source, List<String> selected, List<DataFilter> filters, Map<String, Object> key, int limit, TrustedActor actor, InvocationControl control) {
        if (source.startsWith("lowcode."))return loadLowcode(source, selected, filters, key, limit, actor, control);
        var t = table(source, actor);
        for (String name:selected)check(isReadable(t, name));
        var args = new ArrayList<Object>();
        StringBuilder where = new StringBuilder(" WHERE 1=1");
        scope(t, actor, where, args);
        if (key != null) {
            keys(key, t.pk());
            for (var e:key.entrySet()) {
                where.append(" AND ").append(q(e.getKey())).append("=?");
                args.add(keyParameter(t.columns().stream().filter(c -> c.name().equals(e.getKey())).findFirst().orElseThrow(), e.getValue()));
            }
        }
        for (var f:filters) {
            var c = t.columns().stream().filter(col -> col.name().equals(f.field()) && readable(col)).findFirst().orElseThrow(() -> failure(ErrorCode.RESOURCE_FORBIDDEN));
            filterValue(f, wire(c));
            appendFilter(where, args, q(c.name()), f);
        }
        String columns = selected.stream().map(n -> {
            var col = t.columns().stream().filter(c -> c.name().equals(n)).findFirst().orElseThrow();
            return textType(col.jdbcType())?"LEFT("+q(n)+",8193) AS "+q(n):q(n);
        }
        ).collect(java.util.stream.Collectors.joining(","));
        String order = (t.pk().isEmpty()?List.of(selected.get(0)):t.pk()).stream().map(DatabaseBindingReader::q).collect(java.util.stream.Collectors.joining(","));
        args.add(limit+1);
        var truncated = new boolean[] {
            false
        }
        ;
        var bytes = new int[] {
            0
        }
        ;
        var rows = jdbc.query("SELECT "+columns+" FROM "+q(t.name())+where+" ORDER BY "+order+" LIMIT ?", (rs, n) -> {
            control.check();
            Map<String, Object> row = new LinkedHashMap<>();
            for (String field:selected) {
                var column = t.columns().stream().filter(c -> c.name().equals(field)).findFirst().orElseThrow();
                boolean structured = column.type().equalsIgnoreCase("JSON") || field.toLowerCase(Locale.ROOT).endsWith("_json");
                Object v = structured ? structured(rs.getString(field)) : normal(rs.getObject(field));
                if (v instanceof String text && text.length()>8192) {
                    v = text.substring(0, 8192);
                    truncated[0] = true;
                }
                row.put(field, sanitize(v));
            }
            bytes[0]+= encodedBytes(row);
            if (bytes[0]>MAX_BYTES)throw failure(ErrorCode.OUTPUT_LIMIT);
            return row;
        }
        , args.toArray());
        if (rows.size()>limit) {
            rows = new ArrayList<>(rows.subList(0, limit));
            truncated[0] = true;
        }
        return new Loaded(rows, truncated[0]);
    }

    private Loaded loadLowcode(String source, List<String> selected, List<DataFilter> filters, Map<String, Object> key, int limit, TrustedActor actor, InvocationControl control) {
        var meta = lowcodeFields(source, actor);
        for (String f:selected)find(meta.fields(), f);
        String form = formId(source);
        var args = new ArrayList<Object>(List.of(form, actor.tenantId()));
        StringBuilder where = new StringBuilder(" WHERE form_id=? AND tenant_id=? AND is_deleted=0");
        if (key != null) {
            keys(key, List.of("id"));
            where.append(" AND id=?");
            args.add(scalar(key.get("id")));
        }
        else permission(actor, "lowcode:record:list");
        for (var f:filters) {
            var field = find(meta.fields(), f.field());
            filterValue(f, field);
            if (!f.field().equals("id") && (f.operator() == FilterOperator.IS_NULL || f.operator() == FilterOperator.NOT_NULL)) {
                where.append(f.operator() == FilterOperator.IS_NULL?" AND (JSON_EXTRACT(data_json, ?) IS NULL OR JSON_TYPE(JSON_EXTRACT(data_json, ?))='NULL')":" AND (JSON_EXTRACT(data_json, ?) IS NOT NULL AND JSON_TYPE(JSON_EXTRACT(data_json, ?))<>'NULL')");
                args.add("$."+f.field());
                args.add("$."+f.field());
                continue;
            }
            String expression = f.field().equals("id")?"id":"JSON_UNQUOTE(JSON_EXTRACT(data_json, ?))";
            if (!f.field().equals("id"))args.add("$."+f.field());
            if (field.type().equals("number"))expression = "CAST("+expression+" AS DECIMAL(20,6))";
            var actual = field.type().equals("switch") && f.value() instanceof Boolean?new DataFilter(f.field(), f.operator(), f.value().toString()):f;
            appendFilter(where, args, expression, actual);
        }
        args.add(limit+1);
        var ids = jdbc.query("SELECT id FROM sys_lowcode_record"+where+" ORDER BY id LIMIT ?", (rs, n) -> rs.getString(1), args.toArray());
        boolean truncated = ids.size()>limit;
        if (truncated)ids = ids.subList(0, limit);
        var rows = new ArrayList<Map<String, Object>>();
        var notices = new LinkedHashSet<Notice>();
        for (String id:ids) {
            control.check();
            var record = lowcode.record(Long.parseLong(form), Long.parseLong(id));
            var schema = (LowcodeSchema.Schema)record.get("schema");
            var data = (JsonNode)record.get("data");
            var row = new LinkedHashMap<String, Object>();
            for (String name:selected) {
                if (name.equals("id")) {
                    row.put(name, id);
                    continue;
                }
                var field = schema.fields().stream().filter(f -> f.fieldKey().equals(name) && !secret(f.fieldKey(), f.label())).findFirst().orElse(null);
                if (field == null) {
                    row.put(name, null);
                    notices.add(new Notice("FIELD_NOT_IN_RECORD_SCHEMA", source+"."+name));
                    continue;
                }
                var expected = find(meta.fields(), name);
                if (!expected.type().equals(field.type()))throw failure(ErrorCode.RESOURCE_UNAVAILABLE);
                var node = data.get(name);
                schemas.validateValue(field, node == null?json.nullNode():node, false);
                row.put(name, node == null || node.isNull()?null:sanitize(json.convertValue(node, Object.class)));
            }
            rows.add(row);
            bounded(rows);
        }
        return new Loaded(rows, truncated, List.copyOf(notices));
    }

    private Table table(String source, TrustedActor actor) {
        databasePermission(actor);
        check(source != null && source.startsWith("db."));
        String name = source.substring(3);
        if (!allowedTable(name) || !knownPermission(name, actor))throw failure(ErrorCode.RESOURCE_FORBIDDEN);
        return jdbc.execute((ConnectionCallback<Table>)c -> {
            var m = c.getMetaData();
            String catalog = c.getCatalog();
            String comment = null;
            try (var r = m.getTables(catalog, null, name, new String[] {
                "TABLE"
            }
            )) {
                while (r.next())if (name.equals(r.getString("TABLE_NAME"))) {
                    comment = Optional.ofNullable(r.getString("REMARKS")).orElse("");
                    break;
                }
            }
            if (comment == null)throw failure(ErrorCode.RESOURCE_UNAVAILABLE);
            var pk = new TreeMap<Integer, String>();
            try (var r = m.getPrimaryKeys(catalog, null, name)) {
                while (r.next())pk.put((int)r.getShort("KEY_SEQ"), r.getString("COLUMN_NAME"));
            }
            var columns = new ArrayList<Column>();
            try (var r = m.getColumns(catalog, null, name, null)) {
                while (r.next()) {
                    if (!name.equals(r.getString("TABLE_NAME")) || (catalog != null && !catalog.equals(r.getString("TABLE_CAT")) && !catalog.equals(r.getString("TABLE_SCHEM"))))continue;
                    String field = r.getString("COLUMN_NAME"), remark = Optional.ofNullable(r.getString("REMARKS")).orElse("");
                    columns.add(new Column(field, remark, r.getString("TYPE_NAME"), r.getInt("DATA_TYPE"), r.getInt("NULLABLE") != DatabaseMetaData.columnNoNulls, pk.containsValue(field)));
                    if (columns.size()>512)throw failure(ErrorCode.RESOURCE_UNAVAILABLE);
                }
            }
            var t = new Table(name, comment, List.copyOf(columns), List.copyOf(pk.values()));
            if (t.pk().stream().anyMatch(p -> !isReadable(t, p))) {
                return new Table(t.name(), t.comment(), t.columns(), List.of());
            }
            return t;
        });
    }

    private List<Table> allTables() {
        return jdbc.execute((ConnectionCallback<List<Table>>)c -> {
            var result = new ArrayList<Table>();
            try (var r = c.getMetaData().getTables(c.getCatalog(), null, "%", new String[] {
                "TABLE"
            }
            )) {
                while (r.next()) {
                    if (result.size()>2000)throw failure(ErrorCode.RESOURCE_UNAVAILABLE);
                    result.add(new Table(r.getString("TABLE_NAME"), Optional.ofNullable(r.getString("REMARKS")).orElse(""), List.of(), List.of()));
                }
            }
            return result.stream().sorted(Comparator.comparing(Table::name)).toList();
        });
    }

    private void scope(Table table, TrustedActor actor, StringBuilder where, List<Object> args) {
        for (var column : table.columns()) {
            String name = column.name().replace("_", "").toLowerCase(Locale.ROOT);
            if (name.equals("tenantid")) {
                where.append(" AND ").append(q(column.name())).append("=?");
                args.add(actor.tenantId());
            } else if (name.equals("isdeleted") || name.equals("deleted")) {
                where.append(" AND ").append(q(column.name())).append("=0");
            } else if (name.equals("delflag")) {
                where.append(" AND ").append(q(column.name())).append("=?");
                args.add("0");
            }
        }
    }
    private static void appendFilter(StringBuilder where, List<Object> args, String expression, DataFilter f) {
        where.append(" AND ").append(expression);
        switch (f.operator()) {
            case IS_NULL -> where.append(" IS NULL");
            case NOT_NULL -> where.append(" IS NOT NULL");
            case CONTAINS -> {
                where.append(" LIKE ? ESCAPE '!'");
                args.add("%"+String.valueOf(scalar(f.value())).replace("!", "!!").replace("%", "!%").replace("_", "!_")+"%");
            }
            default -> {
                where.append(switch (f.operator()) {
                    case EQ -> "=?";
                    case NE -> "<>?";
                    case GT -> ">?";
                    case GE -> ">=?";
                    case LT -> "<?";
                    case LE -> "<=?";
                    default -> throw new IllegalArgumentException();
                });
                args.add(scalar(f.value()));
            }
        }
    }

    private void filterValue(DataFilter filter, DatabaseField field) {
        if (filter.operator() == FilterOperator.IS_NULL || filter.operator() == FilterOperator.NOT_NULL) {
            check(filter.value() == null);
            return;
        }
        scalar(filter.value());
        if (filter.operator() == FilterOperator.CONTAINS)check(typeGroup(field.type()).equals("text"));
    }

    private static Object scalar(Object value) {
        check(value != null && (value instanceof String || value instanceof Number || value instanceof Boolean));
        if (value instanceof String text)check(text.length() <= 2048);
        return value;
    }

    private static void keys(Map<String, Object> values, List<String> pk) {
        check(!pk.isEmpty() && values.keySet().equals(new HashSet<>(pk)));
        values.values().forEach(DatabaseBindingReader::scalar);
    }

    private Map<String, Object> currentKeys(String source, BusinessRef ref, List<String> pk) {
        check(ref != null);
        if (source.startsWith("lowcode.")) {
            check(ref.type().equals("lowcode.record"));
            var ids = ref.id().split(":", -1);
            check(ids.length == 2 && ids[0].equals(formId(source)));
            var key = Map.<String, Object>of("id", ids[1]);
            keys(key, pk);
            return key;
        }
        check(ref.type().equals("database.record") && ref.id().matches("db1\\.[A-Za-z0-9_-]{1,2044}"));
        try {
            byte[] bytes = Base64.getUrlDecoder().decode(ref.id().substring(4));
            check(bytes.length <= 1536);
            var n = json.readTree(bytes);
            check(n.isObject() && n.size() == 2 && n.path("sourceKey").asText().equals(source) && n.path("key").isObject());
            Map<String, Object> key = json.convertValue(n.get("key"), new TypeReference<>() {
            });
            keys(key, pk);
            return key;
        }
        catch (java.io.IOException|IllegalArgumentException invalid) {
            throw failure(ErrorCode.INVALID_INPUT);
        }
    }

    /** Reconstitute numeric key parameters from exact candidate strings using actual JDBC metadata. */
    private static Object keyParameter(Column column, Object value) {
        Object checked = scalar(value);
        String text = checked.toString();
        try {
            return switch (column.jdbcType()) {
                case Types.TINYINT, Types.SMALLINT, Types.INTEGER, Types.BIGINT ->
                        new java.math.BigDecimal(text).toBigIntegerExact();
                case Types.NUMERIC, Types.DECIMAL -> new java.math.BigDecimal(text);
                case Types.FLOAT, Types.REAL -> Float.valueOf(text);
                case Types.DOUBLE -> Double.valueOf(text);
                default -> checked;
            };
        } catch (ArithmeticException | NumberFormatException invalid) {
            throw failure(ErrorCode.INVALID_INPUT);
        }
    }
    /** Candidate keys cross a browser boundary; values retain their original database types. */
    private static Object candidateKey(Object value) {
        if (value instanceof java.math.BigDecimal decimal) {
            return decimal.toPlainString();
        }
        if (value instanceof Byte || value instanceof Short || value instanceof Integer
                || value instanceof Long || value instanceof java.math.BigInteger) {
            return value.toString();
        }
        if (value instanceof Float || value instanceof Double) {
            check(Double.isFinite(((Number) value).doubleValue()));
            return new java.math.BigDecimal(value.toString()).toPlainString();
        }
        check(!(value instanceof Number));
        return value;
    }
    private BusinessRef reference(String source, Map<String, Object> key) {
        if (source.startsWith("lowcode."))return new BusinessRef("lowcode.record", formId(source)+":"+key.get("id"));
        try {
            String token = "db1."+Base64.getUrlEncoder().withoutPadding().encodeToString(json.writeValueAsBytes(Map.of("sourceKey", source, "key", key)));
            check(token.length() <= 2048);
            return new BusinessRef("database.record", token);
        }
        catch (com.fasterxml.jackson.core.JsonProcessingException invalid) {
            throw failure(ErrorCode.INVALID_INPUT);
        }
    }

    private void databasePermission(TrustedActor actor) {
        if (!platform.isPlatformIdentity())throw failure(ErrorCode.RESOURCE_FORBIDDEN);
        permission(actor, READ);
    }

    private static void permission(TrustedActor actor, String action) {
        if (!actor.authorities().contains(action))throw failure(ErrorCode.RESOURCE_FORBIDDEN);
    }

    private static boolean knownPermission(String table, TrustedActor actor) {
        String permission = switch (table.toLowerCase(Locale.ROOT)) {
            case "sys_news" -> "system:news:";
            case "sys_dict_type", "sys_dict_data" -> "system:dict:";
            default -> null;
        };
        return permission == null || actor.authorities().contains(permission + "query")
                || actor.authorities().contains(permission + "list");
    }
    private static boolean allowedTable(String name) {
        String n = name.toLowerCase(Locale.ROOT);
        return !n.startsWith("sys_ai_") && !n.startsWith("sys_lowcode_") && !n.startsWith("sys_user") && !n.startsWith("sys_role") && !n.startsWith("sys_menu") && !n.startsWith("sys_tenant") && !n.startsWith("sys_oss") && !n.startsWith("sys_config") && !n.startsWith("sys_business_config") && !n.startsWith("sys_oper_log") && !n.startsWith("sys_log") && !n.startsWith("sys_notification") && !n.startsWith("sys_notice") && !n.startsWith("sys_wechat") && !n.startsWith("sys_job") && !n.contains("session") && !n.startsWith("flyway") && !secret(n, "");
    }

    private boolean readable(Column col) {
        return !secret(col.name(), col.comment()) && !Set.of(Types.BINARY, Types.VARBINARY, Types.LONGVARBINARY, Types.BLOB, Types.CLOB, Types.JAVA_OBJECT, Types.OTHER).contains(col.jdbcType());
    }

    public static boolean secret(String field, String comment) {
        String n = (field+" "+comment).toLowerCase(Locale.ROOT).replaceAll("[\\s_-]", "");
        return n.matches(".*(password|passwd|secret|token|credential|privatekey|apikey|accesskey|authcode|salt|nonce|cipher|encrypted).*") || Set.of("pwd", "authorization").contains(n) || java.util.regex.Pattern.compile("密码|密钥|凭据|令牌|私钥|验证码").matcher(comment).find();
    }

    private boolean isReadable(Table t, String field) {
        return t.columns().stream().anyMatch(c -> c.name().equals(field) && readable(c));
    }

    private DatabaseField wire(Column c) {
        return new DatabaseField(c.name(), label(c.comment(), c.name()), c.comment(), c.type(), c.nullable(), c.pk());
    }

    private String fingerprint(List<DatabaseField> fields) {
        try {
            var shapes = fields.stream().map(f -> List.of(f.field(), f.type(), f.nullable())).toList();
            return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(json.writeValueAsBytes(shapes)));
        }
        catch (Exception invalid) {
            throw failure(ErrorCode.DEPENDENCY_UNAVAILABLE);
        }
    }

    private Object sanitize(Object value) {
        return sanitize(value, 0);
    }

    private Object sanitize(Object value, int depth) {
        if (depth > 16) throw failure(ErrorCode.OUTPUT_LIMIT);
        if (value instanceof Map<?, ?> map) {
            var clean = new LinkedHashMap<String, Object>();
            map.forEach((k, v) -> {
                if (k instanceof String name && !secret(name, ""))clean.put(name, sanitize(v, depth + 1));
            });
            return clean;
        }
        if (value instanceof List<?> list)return list.stream().map(item -> sanitize(item, depth + 1)).toList();
        return value;
    }

    private Object structured(String text) {
        if (text == null) return null;
        if (text.length() > 8192) throw failure(ErrorCode.OUTPUT_LIMIT);
        try {
            return sanitize(json.readValue(text, Object.class));
        } catch (com.fasterxml.jackson.core.JsonProcessingException invalid) {
            // Never return malformed or SQL-truncated structured text containing unfiltered secret keys.
            throw failure(ErrorCode.RESOURCE_UNAVAILABLE);
        }
    }
    private static Object normal(Object value) {
        if (value instanceof java.sql.Date d)return d.toLocalDate().toString();
        if (value instanceof Timestamp t)return t.toLocalDateTime().toString();
        if (value instanceof java.sql.Time t)return t.toLocalTime().toString();
        if (value instanceof byte[])throw failure(ErrorCode.RESOURCE_FORBIDDEN);
        return value;
    }

    private int encodedBytes(Object value) {
        try {
            return json.writeValueAsBytes(value).length;
        }
        catch (com.fasterxml.jackson.core.JsonProcessingException invalid) {
            throw failure(ErrorCode.INVALID_INPUT);
        }
    }

    private void bounded(Object value) {
        if (encodedBytes(value)>MAX_BYTES)throw failure(ErrorCode.OUTPUT_LIMIT);
    }

    private static Map<String, Object> project(Map<String, Object> row, List<String> fields) {
        var selected = new LinkedHashMap<String, Object>();
        for (String f:fields)selected.put(f, row.get(f));
        return selected;
    }

    private static String q(String identifier) {
        return "`"+identifier.replace("`", "``")+"`";
    }

    private static String label(String comment, String fallback) {
        return comment == null || comment.isBlank()?fallback:comment;
    }

    private static String formId(String source) {
        String id = source.substring("lowcode.".length());
        check(id.matches("[1-9][0-9]{0,18}"));
        try {
            Long.parseLong(id);
        }
        catch (NumberFormatException invalid) {
            throw failure(ErrorCode.INVALID_INPUT);
        }
        return id;
    }

    private static boolean textType(int type) {
        return Set.of(Types.CHAR, Types.VARCHAR, Types.LONGVARCHAR, Types.NCHAR, Types.NVARCHAR, Types.LONGNVARCHAR).contains(type);
    }

    private static String typeGroup(String raw) {
        String t = raw.toLowerCase(Locale.ROOT);
        if (t.contains("int") || t.contains("decimal") || t.contains("numeric") || t.equals("number") || t.contains("double") || t.contains("float"))return "number";
        if (t.contains("char") || t.contains("text") || t.equals("select"))return "text";
        return "complex";
    }

    private static void check(boolean condition) {
        if (!condition)throw failure(ErrorCode.INVALID_INPUT);
    }

    private static AiRuntimeException failure(ErrorCode code) {
        return new AiRuntimeException(code);
    }
}
