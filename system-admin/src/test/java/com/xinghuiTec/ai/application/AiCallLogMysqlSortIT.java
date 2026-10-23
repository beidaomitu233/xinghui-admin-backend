package com.xinghuiTec.ai.application;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.alibaba.druid.DbType;
import com.alibaba.druid.filter.stat.StatFilter;
import com.alibaba.druid.pool.DruidDataSource;
import com.alibaba.druid.wall.WallConfig;
import com.alibaba.druid.wall.WallFilter;
import com.xinghuiTec.ai.contract.AiTypes.TrustedActor;
import com.xinghuiTec.ai.modelhub.ModelHubTestService;
import com.xinghuiTec.ai.scene.SqlAssistanceService;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DataSourceTransactionManager;

import java.sql.Timestamp;
import java.time.Instant;
import java.util.*;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

/** Explicit native-MySQL gate, excluded by the ordinary *Test naming pattern. No migrations or providers. */
@Tag("native-mysql")
class AiCallLogMysqlSortIT {
    @Test
    void metadataDurationsSortNumericallyWithNullsStablePagesAndOwnAuditIsolation() throws Exception {
        String url = System.getenv("DB_URL");
        assertEquals("1", System.getenv("UI_NATIVE_MYSQL_SORT"), "Enable this isolated gate explicitly");
        boolean ci = "true".equals(System.getenv("CI"));
        String schema = ci ? "xinghui_oss04_ci" : "ui_native_03_20261024";
        String target = "jdbc:mysql://127.0.0.1:" + (ci ? "3306/" : "13323/") + schema;
        assertTrue(url != null && (url.equals(target) || url.startsWith(target + "?")),
                "Refuse any database outside the exact local or CI gate schema");
        try (var source = dataSource(url, System.getenv("DB_USERNAME"), System.getenv("DB_PASSWORD"))) {
            var jdbc = new JdbcTemplate(source);
            assertEquals(schema, jdbc.queryForObject("SELECT DATABASE()", String.class));
            var json = new ObjectMapper().findAndRegisterModules();
            var store = new AiCallLogStore(jdbc, new DataSourceTransactionManager(source), json);
            var requestLogs = mock(AiRequestLogService.class);
            var requests = mock(AiRequestStore.class);
            var runtime = mock(AiRuntimeService.class);
            var sql = mock(SqlAssistanceService.class);
            var modelTests = mock(ModelHubTestService.class);
            var service = new AiCallLogService(jdbc, store, requestLogs, requests, runtime, sql, modelTests);
            var grants = Set.of("ai:request:list", "ai:request:query", "ai:request:audit", "ai:config:query");
            var own = new TrustedActor("90001", "native-sort-c", grants, null);
            var foreign = new TrustedActor("90001", "native-sort-other", grants, null);
            var audit = new TrustedActor("90002", "000000", grants, null);
            String provider = "native-sort-" + UUID.randomUUID().toString().replace("-", "");
            var ids = new ArrayList<String>();
            var ownIds = new ArrayList<String>();
            var elapsed = new HashMap<String, Long>();
            var firstOutput = new HashMap<String, Long>();
            try {
                for (int index = 0; index < 27; index++) {
                    String id = store.beginDiscovery(index < 25 ? own : foreign, provider);
                    assertNotNull(id);
                    ids.add(id);
                    store.completeDiscovery(id, true, 10L);
                    Long duration = index % 7 == 0 ? null : new long[] { 0L, 2L, 10L, 100L }[(index * 3) % 4];
                    Long first = index % 6 == 0 ? null : new long[] { 100L, 0L, 10L, 2L }[(index * 5) % 4];
                    Map<String, Object> metadata = json.readValue(jdbc.queryForObject(
                            "SELECT metadata_json FROM sys_ai_call_log WHERE call_id=?", String.class, id), new TypeReference<>() {});
                    // Mix missing paths and explicit JSON null; both must remain SQL NULL rather than zero.
                    if (duration == null && index % 2 == 0) metadata.remove("elapsedMs");
                    else metadata.put("elapsedMs", duration);
                    metadata.put("firstOutputMs", first);
                    metadata.put("firstOutputKind", "TEXT");
                    jdbc.update("UPDATE sys_ai_call_log SET metadata_json=?,started_at=? WHERE call_id=?",
                            json.writeValueAsString(metadata), Timestamp.from(Instant.now().minusSeconds(30)), id);
                    if (index < 25) { ownIds.add(id); elapsed.put(id, duration); firstOutput.put(id, first); }
                }
                for (var metric : Map.of("elapsedMs", elapsed, "firstOutputMs", firstOutput).entrySet()) {
                    var expected = ownIds.stream().sorted(Comparator.comparing((String id) -> metric.getValue().get(id),
                            Comparator.nullsFirst(Long::compareTo)).thenComparing(String::compareTo)).toList();
                    for (String direction : List.of("asc", "desc")) {
                        var ordered = new ArrayList<>(expected);
                        if (direction.equals("desc")) Collections.reverse(ordered);
                        var actual = new ArrayList<String>();
                        for (int page = 1; page <= 3; page++) {
                            var result = service.page(own, true, filters(AiRequestLogService.Scope.OWN, provider),
                                    page, 10, metric.getKey(), direction);
                            assertEquals(25, result.total());
                            for (var row : result.records()) {
                                String id = (String) row.get("callId");
                                actual.add(id);
                                Object value = row.get(metric.getKey());
                                assertEquals(metric.getValue().get(id), value == null ? null : ((Number) value).longValue());
                                assertEquals("TEXT", row.get("firstOutputKind"));
                            }
                        }
                        assertEquals(ordered, actual);
                        assertEquals(25, new HashSet<>(actual).size());
                    }
                }
                assertEquals(27, service.page(audit, true, filters(AiRequestLogService.Scope.AUDIT, provider),
                        1, 100, "elapsedMs", "asc").total());
                verifyNoInteractions(requestLogs, requests, runtime, sql, modelTests);
            } finally {
                for (String id : ids) {
                    jdbc.update("DELETE FROM sys_ai_call_response WHERE call_id=?", id);
                    jdbc.update("DELETE FROM sys_ai_call_log WHERE call_id=? AND provider_code=?", id, provider);
                }
                assertEquals(0L, jdbc.queryForObject("SELECT COUNT(*) FROM sys_ai_call_log WHERE provider_code=?", Long.class, provider));
            }
        }
    }

    /** Matches the existing AiConfigurationDruidHttpTest and production stat + MySQL Wall settings. */
    private DruidDataSource dataSource(String url, String username, String password) {
        var source = new DruidDataSource();
        source.setUrl(url); source.setUsername(username); source.setPassword(password);
        source.setDriverClassName("com.mysql.cj.jdbc.Driver"); source.setDbType(DbType.mysql);
        source.setInitialSize(1); source.setMinIdle(0); source.setMaxActive(2);
        source.setMaxWait(3000); source.setValidationQuery("SELECT 1");
        var config = new WallConfig(); config.setMultiStatementAllow(true);
        var wall = new WallFilter(); wall.setDbType(DbType.mysql); wall.setConfig(config);
        source.setProxyFilters(List.of(new StatFilter(), wall));
        return source;
    }

    private AiCallLogService.Filters filters(AiRequestLogService.Scope scope, String provider) {
        return new AiCallLogService.Filters(scope, null, null, null, null, provider, null,
                AiCallLogService.EntryType.MODEL_DISCOVERY, null, null, null, null);
    }
}
