package com.xinghuiTec.lowcode;

import com.alibaba.druid.DbType;
import com.alibaba.druid.filter.stat.StatFilter;
import com.alibaba.druid.pool.DruidDataSource;
import com.alibaba.druid.wall.WallConfig;
import com.alibaba.druid.wall.WallFilter;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.NullNode;
import com.xinghuiTec.exception.ServiceException;
import org.flywaydb.core.Flyway;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DriverManagerDataSource;

import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

/** Native MySQL + production stat/wall gate. Local execution is limited to UI-NATIVE-02's new assigned schema. */
class LowcodeSortingMySqlIT {
    private DriverManagerDataSource migratedSource() throws Exception {
        if ("true".equals(System.getenv("CI"))) return LowcodeSharingMySqlIT.source();
        String url = Objects.requireNonNull(System.getenv("DB_URL"), "Assigned isolated DB_URL required");
        assertTrue(url.startsWith("jdbc:mysql://127.0.0.1:13323/ui_native_02_20261024?"), "Only UI-NATIVE-02's assigned schema is allowed");
        var source = new DriverManagerDataSource(url, System.getenv("DB_USERNAME"), System.getenv("DB_PASSWORD"));
        try (var connection = source.getConnection()) {
            assertTrue(connection.getMetaData().getDatabaseProductName().contains("MySQL"));
        }
        Flyway.configure().dataSource(source).locations("classpath:db/migration").cleanDisabled(true)
                .baselineOnMigrate(false).outOfOrder(false).load().migrate();
        return source;
    }

    @Test
    void typedPublishedFieldsSortAcrossAllPagesWithNullHistoryAndTenantIsolation() throws Exception {
        var migrated = migratedSource();
        try (var source = new DruidDataSource()) {
            source.setUrl(migrated.getUrl()); source.setUsername(migrated.getUsername()); source.setPassword(migrated.getPassword());
            source.setDriverClassName("com.mysql.cj.jdbc.Driver"); source.setDbType(DbType.mysql);
            source.setInitialSize(0); source.setMinIdle(0); source.setMaxActive(2); source.setMaxWait(3000);
            var wall = new WallFilter(); wall.setDbType(DbType.mysql);
            var wallConfig = new WallConfig(); wallConfig.setMultiStatementAllow(true); wall.setConfig(wallConfig);
            source.setProxyFilters(List.of(new StatFilter(), wall));
            var jdbc = new JdbcTemplate(source);
            assertEquals("true".equals(System.getenv("CI")) ? "xinghui_oss04_ci" : "ui_native_02_20261024", jdbc.queryForObject("SELECT DATABASE()", String.class));
            assertEquals(43, jdbc.queryForObject("SELECT COUNT(*) FROM flyway_schema_history WHERE success=1 AND version IS NOT NULL", Integer.class));
            var json = new ObjectMapper(); var schemas = new LowcodeSchema(json);
            var tenant = new AtomicReference<>("000000");
            var identity = mock(LowcodeIdentity.class);
            when(identity.tenant()).thenAnswer(ignored -> tenant.get()); when(identity.creator()).thenReturn("1");
            var service = new LowcodeService(jdbc, schemas, identity);
            var createdForms = new ArrayList<Long>();
            try {
                var oldFields = List.of(field("subject", "text", true, true), field("paragraph", "textarea", true, true),
                        field("day", "date", true, true), field("active", "switch", true, true),
                        field("choice", "select", true, true), field("choices", "multiselect", true, false));
                long form = create(service, new LowcodeSchema.Schema(oldFields)); createdForms.add(form);
                // Version 1 genuinely has no amount key. Do not corrupt current-version records to simulate history.
                for (int index = 0; index < 4; index++) service.addRecord(form, new LowcodeService.Submission(1, 0, data(json, index, false)));
                var currentFields = new ArrayList<>(oldFields);
                currentFields.add(field("amount", "number", true, true));
                currentFields.add(field("private_note", "text", false, false));
                var current = new LowcodeSchema.Schema(currentFields);
                var definition = service.form(form);
                service.update(form, new LowcodeService.Draft(String.valueOf(definition.get("name")), String.valueOf(definition.get("formKey")), "enabled", ((Number) definition.get("revision")).intValue(), current));
                service.publish(form, ((Number) service.form(form).get("revision")).intValue());
                for (int index = 4; index < 35; index++) service.addRecord(form, new LowcodeService.Submission(2, 0, data(json, index, true)));
                tenant.set("100001"); service.addRecord(form, new LowcodeService.Submission(2, 0, data(json, 40, true))); tenant.set("000000");
                long other = create(service, new LowcodeSchema.Schema(oldFields)); createdForms.add(other);
                service.addRecord(other, new LowcodeService.Submission(1, 0, data(json, 41, false)));

                for (var sortable : Map.of("subject", "string", "paragraph", "string", "day", "string", "active", "boolean", "choice", "string", "amount", "number").entrySet()) {
                    for (String direction : List.of("asc", "desc")) {
                        String path = "$." + sortable.getKey();
                        String expression = referenceExpression(sortable.getValue());
                        var args = new ArrayList<Object>(List.of(form, "000000", path));
                        if (!sortable.getValue().equals("boolean")) args.add(path);
                        var expected = jdbc.queryForList("SELECT CAST(id AS CHAR) FROM sys_lowcode_record WHERE form_id=? AND tenant_id=? AND is_deleted=0 ORDER BY "
                                + expression + " " + direction + ",id DESC", String.class, args.toArray());
                        var actual = new ArrayList<String>();
                        for (int page = 1; page <= 4; page++) {
                            var result = service.records(form, page, 10, null, null, "data." + sortable.getKey(), direction);
                            assertEquals("35", result.total());
                            result.records().forEach(row -> actual.add(assertInstanceOf(String.class, row.get("id"))));
                        }
                        assertEquals(expected, actual, sortable.getKey() + " " + direction + " must sort the full set before paging");
                        assertEquals(35, new java.util.HashSet<>(actual).size());
                    }
                }
                assertEquals(4, jdbc.queryForObject("SELECT COUNT(*) FROM sys_lowcode_record WHERE form_id=? AND JSON_EXTRACT(data_json,'$.amount') IS NULL", Integer.class, form));
                assertTrue(jdbc.queryForObject("SELECT COUNT(*) FROM sys_lowcode_record WHERE form_id=? AND JSON_TYPE(JSON_EXTRACT(data_json,'$.subject'))='NULL'", Integer.class, form) > 0);
                assertTrue(jdbc.queryForObject("SELECT COUNT(*) FROM sys_lowcode_record WHERE form_id=? AND JSON_UNQUOTE(JSON_EXTRACT(data_json,'$.subject'))='null' AND JSON_TYPE(JSON_EXTRACT(data_json,'$.subject'))='STRING'", Integer.class, form) > 0);
                for (String column : List.of("data.choices", "data.private_note", "data.unknown", "tenant_id", "data.subject') DESC; DELETE FROM sys_lowcode_record"))
                    assertThrows(ServiceException.class, () -> service.records(form, 1, 10, null, null, column, "asc"));
                assertThrows(ServiceException.class, () -> service.records(form, 1, 10, null, null, "data.amount", "DESC; SELECT 1"));
                var draftFields = new ArrayList<>(currentFields); draftFields.add(field("draft_only", "text", true, true));
                definition = service.form(form);
                service.update(form, new LowcodeService.Draft(String.valueOf(definition.get("name")), String.valueOf(definition.get("formKey")), "enabled", ((Number) definition.get("revision")).intValue(), new LowcodeSchema.Schema(draftFields)));
                assertThrows(ServiceException.class, () -> service.records(form, 1, 10, null, null, "data.draft_only", "asc"));
                tenant.set("100001"); assertEquals("1", service.records(form, 1, 10, null, null, "data.amount", "asc").total());
                tenant.set("000000"); assertEquals("35", service.records(form, 1, 10, null, null).total());
            } finally {
                // Exact IDs created by this test only; never clean/drop schemas or remove unrelated QA data.
                for (long form : createdForms) {
                    jdbc.update("DELETE FROM sys_lowcode_record WHERE form_id=?", form);
                    jdbc.update("DELETE FROM sys_lowcode_form_version WHERE form_id=?", form);
                    jdbc.update("DELETE FROM sys_lowcode_form WHERE id=?", form);
                }
            }
        }
    }

    private static LowcodeSchema.Field field(String key, String type, boolean visible, boolean searchable) {
        var options = List.of(new LowcodeSchema.Option("甲", "a"), new LowcodeSchema.Option("乙", "b"));
        return new LowcodeSchema.Field(key, key, type, false, NullNode.getInstance(),
                List.of("select", "multiselect").contains(type) ? options : List.of(), "", type.equals("textarea") ? 5000 : 200,
                null, null, visible, searchable);
    }
    private static long create(LowcodeService service, LowcodeSchema.Schema schema) {
        long form = Long.parseLong(service.create(new LowcodeService.Draft("原生排序测试", "ui_sort_" + UUID.randomUUID().toString().replace("-", ""), "draft", 0, schema)));
        service.publish(form, 0); return form;
    }
    private static com.fasterxml.jackson.databind.JsonNode data(ObjectMapper json, int index, boolean amount) {
        String[] names = {"北京", "上海", "深圳", "null", "Alpha", null, "同值", "同值"};
        String[] days = {"2024-02-29", "2024-03-01", "2030-01-01", null};
        String[] numbers = {"-10", "-2", "-0.000001", "0", "1.000001", "2", "10", "1000000000000", null};
        var result = json.createObjectNode(); result.put("subject", names[index % names.length]);
        result.put("paragraph", index % 5 == 0 ? null : names[index % names.length]);
        result.put("day", days[index % days.length]);
        if (index % 3 == 0) result.putNull("active"); else result.put("active", index % 3 == 1);
        result.put("choice", index % 3 == 0 ? null : index % 3 == 1 ? "a" : "b");
        var choices = result.putArray("choices"); if (index % 2 == 0) choices.add("a");
        if (amount) { String value = numbers[index % numbers.length]; if (value == null) result.putNull("amount"); else result.put("amount", new BigDecimal(value)); }
        return result;
    }
    private static String referenceExpression(String kind) {
        return switch (kind) {
            // Type-aware reference preserves the literal string "null" independently of the service's CASE condition.
            case "number" -> "CASE WHEN JSON_TYPE(JSON_EXTRACT(data_json,?)) IN ('INTEGER','DOUBLE','DECIMAL') THEN CAST(JSON_UNQUOTE(JSON_EXTRACT(data_json,?)) AS DECIMAL(20,6)) END";
            case "boolean" -> "CASE JSON_UNQUOTE(JSON_EXTRACT(data_json,?)) WHEN 'true' THEN 1 WHEN 'false' THEN 0 END";
            default -> "CASE WHEN JSON_TYPE(JSON_EXTRACT(data_json,?))='STRING' THEN JSON_UNQUOTE(JSON_EXTRACT(data_json,?)) END";
        };
    }
}
