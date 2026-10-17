package com.xinghuiTec.ai.application;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.alibaba.druid.pool.DruidDataSource;
import com.alibaba.druid.wall.WallFilter;
import com.alibaba.druid.filter.stat.StatFilter;
import com.xinghuiTec.ai.contract.*;
import com.xinghuiTec.ai.contract.AiProviderRuntime.*;
import com.xinghuiTec.ai.contract.AiTypes.*;
import com.xinghuiTec.controller.AiRequestLogController;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.support.StaticListableBeanFactory;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DataSourceTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

import java.sql.Timestamp;
import java.time.Instant;
import java.time.Duration;
import java.util.*;

import static org.junit.jupiter.api.Assertions.*;

class AiRequestLogServiceTest {
    private final Set<String> actions = Set.of("business:read", "ai:request:list", "ai:request:query", "ai:request:export");
    private final TrustedActor own = new TrustedActor("101", "tenant-a", actions, null);
    private final TrustedActor audit = new TrustedActor("900", "platform", Set.of("ai:request:list", "ai:request:query", "ai:request:export", "ai:request:audit"), null);
    private JdbcTemplate jdbc;
    private AiRequestLogService logs;
    private AiRequestStore requests;
    private AiRequestJournal journal;
    private TransactionTemplate transaction;
    private long ownBusiness, ownTest, otherSubject, otherTenant;

    @BeforeEach void setup() throws Exception {
        var source = AiApplicationTestDatabase.create(); jdbc = new JdbcTemplate(source);
        var mapper = new ObjectMapper(); var manager = new DataSourceTransactionManager(source);
        transaction = new TransactionTemplate(manager); requests = new AiRequestStore(jdbc); journal = new AiRequestJournal(jdbc, requests, manager);
        var apps = new AiApplicationStore(jdbc, mapper); var empty = new StaticListableBeanFactory();
        var versions = new AiApplicationService(jdbc, mapper, apps, new JdbcAiApplicationReferences(jdbc, manager),
                empty.getBeanProvider(AiProviderRuntime.class), empty.getBeanProvider(AiTemplateRuntime.class));
        var policy = new AiApplicationPolicy(AiApplicationPolicy.ModelMode.FIXED,
                List.of(new ModelBinding(new ModelVersionRef("1001", "2001"), KeyMode.AUTO, Set.of(), null, List.of())), false,
                new TemplateVersionRef("1", "2"), Set.of("user_request"), new Requirements(OutputFormat.TEXT, true, false),
                AiApplicationPolicy.Scope.FRONTEND, Set.of(), Set.of("business:read"), "1", 30, 2, 2, 128);
        String appId = apps.create(new AiApplicationStore.Draft("safe app", "metadata fixture", "lowcode.record.summary/1", policy), 101);
        assertEquals("1", appId);
        var snapshot = new AiApplicationService.Snapshot(policy, List.of(), List.of(new AiApplicationService.TestEvidence("1", policy.models().get(0).model())));
        jdbc.update("INSERT INTO sys_ai_application_version(app_version_id,app_id,version_number,draft_revision,snapshot_json,tested_request_id,created_by)"
                + " VALUES(11,1,1,1,?,1,101)", mapper.writeValueAsString(snapshot));
        jdbc.update("UPDATE sys_ai_application SET published_version_id=11,enabled=TRUE WHERE app_id=1");
        logs = new AiRequestLogService(jdbc, apps, versions);
        ownBusiness = request(own, false, true);
        ownTest = request(own, true, false);
        otherSubject = request(new TrustedActor("102", "tenant-a", actions, null), false, true);
        otherTenant = request(new TrustedActor("101", "tenant-b", actions, null), false, true);
    }

    @Test void ownScopeIsFixedAcrossPageDetailStatisticsAndExportWithoutExecutionSideEffects() throws Exception {
        var ownFilter = filter(AiRequestLogService.Scope.OWN, null, null);
        assertEquals(2, logs.page(own, false, ownFilter, 1, 20).total());
        assertEquals(Set.of(Long.toString(ownBusiness), Long.toString(ownTest)), logs.export(own, false, ownFilter).stream()
                .map(row -> row.request().requestId()).collect(java.util.stream.Collectors.toSet()));
        assertEquals(ErrorCode.NOT_FOUND, assertThrows(AiRuntimeException.class, () -> logs.detail(own, false, ownFilter, otherSubject)).code());
        assertThrows(AiRuntimeException.class, () -> logs.detail(own, false, ownFilter, otherTenant));
        var stats = logs.statistics(own, false, ownFilter);
        assertEquals(2, stats.requests()); assertEquals(1, stats.businessRequests()); assertEquals(1, stats.testRequests());
        assertEquals(2, stats.modelAttempts()); assertEquals(2L, stats.knownInputTokens()); assertEquals(3L, stats.knownOutputTokens());
        assertEquals(1, stats.unknownInputAttempts()); assertEquals(1, stats.unknownOutputAttempts());
        var detail = logs.detail(own, false, ownFilter, ownBusiness);
        assertEquals("SIMULATED", detail.attempts().get(0).providerCode()); assertEquals("4001", detail.attempts().get(0).keyVersion());
        String json = new ObjectMapper().findAndRegisterModules().writeValueAsString(detail);
        for (String forbidden : List.of("input_fingerprint", "inputFingerprint", "idempotency_scope", "owner_scope", "messages", "ciphertext")) assertFalse(json.contains(forbidden));
        assertEquals(4, jdbc.queryForObject("SELECT COUNT(*) FROM sys_ai_request", Integer.class));
        assertEquals(4, jdbc.queryForObject("SELECT COUNT(*) FROM sys_ai_request_attempt", Integer.class));
    }

    @Test void emptyOwnScopeUsesRealDruidStatWallWithoutWeakeningAuditOrValidation() throws Exception {
        try (var pool = new DruidDataSource()) {
            pool.setUrl(((org.springframework.jdbc.datasource.DriverManagerDataSource) jdbc.getDataSource()).getUrl());
            pool.setUsername("sa"); pool.setPassword(""); pool.setDbType("mysql");
            pool.setFilters("stat,wall"); pool.setInitialSize(0); pool.setMinIdle(0); pool.setMaxActive(2);
            pool.setValidationQuery("SELECT 1");
            var guardedJdbc = new JdbcTemplate(pool);
            var apps = new AiApplicationStore(guardedJdbc, new ObjectMapper());
            var beans = new StaticListableBeanFactory();
            var versions = new AiApplicationService(guardedJdbc, new ObjectMapper(), apps,
                    new JdbcAiApplicationReferences(guardedJdbc, new DataSourceTransactionManager(pool)),
                    beans.getBeanProvider(AiProviderRuntime.class), beans.getBeanProvider(AiTemplateRuntime.class));
            var guarded = new AiRequestLogService(guardedJdbc, apps, versions);
            var empty = new TrustedActor("101", "tenant-a", Set.of("ai:request:list", "ai:request:query", "ai:request:export"), null);
            var noRows = new TrustedActor("799", "tenant-a", empty.authorities(), null);
            var ownFilter = filter(AiRequestLogService.Scope.OWN, null, null);
            for (TrustedActor actor : List.of(empty, noRows)) {
                var page = guarded.page(actor, true, ownFilter, 2, 17);
                assertEquals(0, page.total()); assertTrue(page.records().isEmpty());
                assertEquals(2, page.pageNum()); assertEquals(17, page.pageSize());
                assertTrue(guarded.export(actor, true, ownFilter).isEmpty());
                var stats = guarded.statistics(actor, true, ownFilter);
                assertEquals(new AiRequestLogService.Statistics(0, 0, 0, 0, null, null, 0, 0), stats);
                assertEquals(ErrorCode.NOT_FOUND, assertThrows(AiRuntimeException.class,
                        () -> guarded.detail(actor, true, ownFilter, ownBusiness)).code());
            }
            assertTrue(pool.getProxyFilters().stream().anyMatch(WallFilter.class::isInstance));
            assertTrue(pool.getProxyFilters().stream().anyMatch(StatFilter.class::isInstance));
            var auditFilter = filter(AiRequestLogService.Scope.AUDIT, null, null);
            assertEquals(4, guarded.page(audit, true, auditFilter, 1, 20).total());
            assertEquals(4, guarded.export(audit, true, auditFilter).size());
            assertEquals(4, guarded.statistics(audit, true, auditFilter).requests());
            assertEquals(Long.toString(otherTenant), guarded.detail(audit, true, auditFilter, otherTenant).request().request().requestId());
            assertEquals(ErrorCode.FORBIDDEN, assertThrows(AiRuntimeException.class,
                    () -> guarded.page(empty, true, auditFilter, 1, 20)).code());
            assertEquals(ErrorCode.FORBIDDEN, assertThrows(AiRuntimeException.class,
                    () -> guarded.export(empty, true, filter(AiRequestLogService.Scope.OWN, "tenant-b", null))).code());
            var invalid = new AiRequestLogService.Filters(AiRequestLogService.Scope.OWN, null, null, null, null, null, null, null,
                    Instant.now().minus(Duration.ofDays(32)), Instant.now());
            assertEquals(ErrorCode.INVALID_INPUT, assertThrows(AiRuntimeException.class, () -> guarded.statistics(noRows, true, invalid)).code());
            assertEquals(4, guardedJdbc.queryForObject("SELECT COUNT(*) FROM sys_ai_request", Integer.class));
            assertEquals(4, guardedJdbc.queryForObject("SELECT COUNT(*) FROM sys_ai_request_attempt", Integer.class));
        }
    }

    @Test void auditRequiresBothPlatformAndIndependentPermissionAndNeverFallsBackToOwn() {
        var auditFilter = filter(AiRequestLogService.Scope.AUDIT, null, null);
        assertEquals(ErrorCode.FORBIDDEN, assertThrows(AiRuntimeException.class, () -> logs.page(own, true, auditFilter, 1, 20)).code());
        assertEquals(ErrorCode.FORBIDDEN, assertThrows(AiRuntimeException.class, () -> logs.page(audit, false, auditFilter, 1, 20)).code());
        assertEquals(4, logs.page(audit, true, auditFilter, 1, 20).total());
        assertEquals(4, logs.statistics(audit, true, auditFilter).requests());
        assertEquals(4, logs.export(audit, true, auditFilter).size());
        assertEquals(Long.toString(otherTenant), logs.detail(audit, true, auditFilter, otherTenant).request().request().requestId());
        assertEquals(1, logs.page(audit, true, filter(AiRequestLogService.Scope.AUDIT, "tenant-b", null), 1, 20).total());
        assertThrows(AiRuntimeException.class, () -> logs.export(own, false, filter(AiRequestLogService.Scope.OWN, "tenant-b", null)));
    }

    @Test void eachActionAndApplicationVisibilityAreStillRequiredInOwnScope() {
        var listOnly = new TrustedActor("101", "tenant-a", Set.of("business:read", "ai:request:list"), null);
        assertEquals(2, logs.page(listOnly, false, filter(AiRequestLogService.Scope.OWN, null, null), 1, 20).total());
        assertThrows(AiRuntimeException.class, () -> logs.detail(listOnly, false, filter(AiRequestLogService.Scope.OWN, null, null), ownBusiness));
        assertThrows(AiRuntimeException.class, () -> logs.export(listOnly, false, filter(AiRequestLogService.Scope.OWN, null, null)));
        var noApplicationAuthority = new TrustedActor("101", "tenant-a", Set.of("ai:request:list", "ai:request:query", "ai:request:export"), null);
        assertEquals(0, logs.page(noApplicationAuthority, false, filter(AiRequestLogService.Scope.OWN, null, null), 1, 20).total());
        assertThrows(AiRuntimeException.class, () -> logs.detail(noApplicationAuthority, false, filter(AiRequestLogService.Scope.OWN, null, null), ownBusiness));
    }

    @Test void windowsAndPaginationAreBoundedWhileExactAuthorizedHistoryIdRemainsQueryable() {
        var ownFilter = filter(AiRequestLogService.Scope.OWN, null, null);
        assertThrows(AiRuntimeException.class, () -> logs.page(own, false, ownFilter, 1, 101));
        var tooLong = new AiRequestLogService.Filters(AiRequestLogService.Scope.OWN, null, null, null, null, null, null, null,
                Instant.now().minus(Duration.ofDays(32)), Instant.now());
        assertThrows(AiRuntimeException.class, () -> logs.statistics(own, false, tooLong));
        jdbc.update("UPDATE sys_ai_request SET create_time=? WHERE request_id=?", Timestamp.from(Instant.now().minus(Duration.ofDays(400))), ownBusiness);
        assertEquals(1, logs.page(own, false, ownFilter, 1, 20).total());
        assertEquals(Long.toString(ownBusiness), logs.detail(own, false, ownFilter, ownBusiness).request().request().requestId());
        var query = new AiRequestLogController.Query("UNKNOWN", null, null, null, null, null, null, null, null, null, null, null,null,null);
        assertEquals(ErrorCode.INVALID_INPUT, assertThrows(AiRuntimeException.class, query::filters).code());
    }

    private AiRequestLogService.Filters filter(AiRequestLogService.Scope scope, String tenant, String subject) {
        return new AiRequestLogService.Filters(scope, null, null, null, null, null, tenant, subject, null, null);
    }
    private long request(TrustedActor actor, boolean test, boolean measured) {
        Instant now = Instant.now(); var owner = AiRuntimeService.owner(actor);
        var admitted = transaction.execute(status -> requests.accept(new AiRequestStore.Admission(1, test ? null : 11L, test ? 1 : null,
                test ? AiRequestStore.Kind.TEST : AiRequestStore.Kind.BUSINESS, owner, "lowcode.record", "1:7", UUID.randomUUID().toString(), "a".repeat(64), now.plusSeconds(30)), now));
        long id = Long.parseLong(admitted.request().requestId()); requests.start(id, now);
        var target = new Target(new ModelVersionRef("1001", "2001"), new KeyRef("3001", "4001"), "SIMULATED", "neutral-model", "masked-fixture", new Capabilities(Set.of(OutputFormat.TEXT), true, false));
        var attempt = journal.reserve(id, owner, target, 2, AiRequestJournal.SwitchReason.FIRST, 64);
        journal.complete(id, attempt.attemptId(), new ExecutionOutcome(measured ? ExecutionStatus.SUCCEEDED : ExecutionStatus.FAILED,
                measured ? null : ErrorCode.RATE_LIMITED, false, measured, measured ? new Usage(2L, 3L, 5L) : null, "gen-fixture-" + id, 1));
        requests.finish(id, measured ? AiRequestStatus.SUCCEEDED : AiRequestStatus.FAILED, measured ? null : "RATE_LIMITED", measured ? 2L : null, measured ? 3L : null, now.plusMillis(1));
        return id;
    }
}
