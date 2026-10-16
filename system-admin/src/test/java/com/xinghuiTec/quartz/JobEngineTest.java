package com.xinghuiTec.quartz;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.xinghuiTec.quartz.handler.JobHandler;
import com.xinghuiTec.quartz.handler.JobHandlerRegistry;
import com.xinghuiTec.quartz.service.JobEngine;
import com.xinghuiTec.quartz.service.JobSchedule;
import org.h2.tools.RunScript;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DataSourceTransactionManager;
import org.springframework.jdbc.datasource.DriverManagerDataSource;

import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.sql.Connection;
import java.time.Instant;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;

class JobEngineTest {
    private JdbcTemplate jdbc;
    private DataSourceTransactionManager transactions;
    private JobHandlerRegistry registry;
    private JobHandler handler;
    private final ObjectMapper json = new ObjectMapper();
    private final AtomicInteger executions = new AtomicInteger();
    private JobEngine first;
    private JobEngine second;

    @BeforeEach
    void setUp() throws Exception {
        var dataSource = new DriverManagerDataSource();
        dataSource.setDriverClassName("org.h2.Driver");
        dataSource.setUrl("jdbc:h2:mem:job02;MODE=MySQL;DB_CLOSE_DELAY=-1");
        dataSource.setUsername("sa");
        jdbc = new JdbcTemplate(dataSource);
        transactions = new DataSourceTransactionManager(dataSource);
        jdbc.execute("DROP TABLE IF EXISTS sys_job_fire");
        jdbc.execute("DROP TABLE IF EXISTS sys_job");
        jdbc.execute("""
                CREATE TABLE sys_job (
                  job_id BIGINT PRIMARY KEY, tenant_id VARCHAR(20), is_deleted INT,
                  job_name VARCHAR(64), job_group VARCHAR(64),
                  handler_key VARCHAR(100), handler_params VARCHAR(4096),
                  cron_expression VARCHAR(255), time_zone VARCHAR(64),
                  misfire_policy VARCHAR(20), concurrent CHAR(1), status CHAR(1)
                )
                """);
        try (Connection connection = dataSource.getConnection();
                var resource = getClass().getResourceAsStream("/db/migration/V20260926_50__job_execution.sql")) {
            assertNotNull(resource);
            RunScript.execute(connection, new InputStreamReader(resource, StandardCharsets.UTF_8));
        }
        try (Connection connection = dataSource.getConnection();
                var resource = getClass().getResourceAsStream("/db/migration/V20260926_70__job_fire_diagnostics.sql")) {
            assertNotNull(resource);
            RunScript.execute(connection, new InputStreamReader(resource, StandardCharsets.UTF_8));
        }
        handler = mock(JobHandler.class);
        when(handler.key()).thenReturn("system.noop");
        doAnswer(invocation -> {
            executions.incrementAndGet();
            return null;
        }).when(handler).execute(any(JsonNode.class));
        registry = new JobHandlerRegistry(List.of(handler));
        first = new JobEngine(jdbc, transactions, registry, json);
        second = new JobEngine(jdbc, transactions, registry, json);
    }

    @AfterEach
    void close() {
        first.close();
        second.close();
        executions.set(0);
    }

    @Test
    void twoNodesClaimOneScheduledOccurrenceOnlyOnce() throws Exception {
        insertJob("0", JobSchedule.utc(Instant.now().minusSeconds(1)));
        var start = new CountDownLatch(1);
        Thread a = new Thread(() -> awaitAndPoll(start, first));
        Thread b = new Thread(() -> awaitAndPoll(start, second));
        a.start();
        b.start();
        start.countDown();
        a.join();
        b.join();
        awaitExecutions(1);
        awaitFireStatus("SUCCEEDED");
        assertEquals(1, jdbc.queryForObject("SELECT COUNT(*) FROM sys_job_fire WHERE job_id=1", Integer.class));
        assertEquals("SUCCEEDED", jdbc.queryForObject(
                "SELECT status FROM sys_job_fire WHERE job_id=1", String.class));
        assertNotNull(jdbc.queryForObject("SELECT next_fire_time FROM sys_job WHERE job_id=1",
                java.time.LocalDateTime.class));
    }

    @Test
    void expiredLeaseIsRecoveredOnceAndThenStopsRetrying() throws Exception {
        insertJob("0", JobSchedule.utc(Instant.now().plusSeconds(3600)));
        jdbc.update("INSERT INTO sys_job_fire (job_id,job_revision,fire_key,trigger_type,status,attempt_count,"
                        + "claim_token,lease_until) VALUES (1,0,'S:1:old','SCHEDULED','CLAIMED',1,'old',?)",
                JobSchedule.utc(Instant.now().minusSeconds(60)));
        first.poll();
        awaitExecutions(1);
        awaitFireStatus("SUCCEEDED");
        assertEquals(2, jdbc.queryForObject(
                "SELECT attempt_count FROM sys_job_fire WHERE fire_key='S:1:old'", Integer.class));
        assertEquals("SUCCEEDED", jdbc.queryForObject(
                "SELECT status FROM sys_job_fire WHERE fire_key='S:1:old'", String.class));

        jdbc.update("INSERT INTO sys_job_fire (job_id,job_revision,fire_key,trigger_type,status,attempt_count,"
                        + "claim_token,lease_until) VALUES (1,0,'S:1:exhausted','SCHEDULED','CLAIMED',2,'old',?)",
                JobSchedule.utc(Instant.now().minusSeconds(60)));
        second.poll();
        assertEquals("FAILED", jdbc.queryForObject(
                "SELECT status FROM sys_job_fire WHERE fire_key='S:1:exhausted'", String.class));
        assertEquals(1, executions.get());
    }

    @Test
    void manualTriggerRejectsPausedTaskAndDoesNotMoveCronPlan() throws Exception {
        insertJob("1", null);
        assertThrows(com.xinghuiTec.exception.ServiceException.class, () -> first.runNow(1L));
        assertEquals(0, jdbc.queryForObject("SELECT COUNT(*) FROM sys_job_fire", Integer.class));
        assertNull(jdbc.queryForObject("SELECT next_fire_time FROM sys_job WHERE job_id=1",
                java.time.LocalDateTime.class));
    }

    @Test
    void manualTriggerRunsEnabledTaskWithoutMovingCronPlan() throws Exception {
        var future = JobSchedule.utc(Instant.now().plusSeconds(3600))
                .truncatedTo(java.time.temporal.ChronoUnit.MILLIS);
        insertJob("0", future);
        Long fireId = first.runNow(1L);
        awaitExecutions(1);
        assertEquals("MANUAL", jdbc.queryForObject(
                "SELECT trigger_type FROM sys_job_fire WHERE fire_id=?", String.class, fireId));
        assertEquals(future, jdbc.queryForObject("SELECT next_fire_time FROM sys_job WHERE job_id=1",
                java.time.LocalDateTime.class));
    }

    @Test
    void handlerFailureIsRecordedWithoutAutomaticRetry() throws Exception {
        doAnswer(invocation -> {
            executions.incrementAndGet();
            throw new IllegalStateException("sensitive value must not be persisted");
        }).when(handler).execute(any(JsonNode.class));
        insertJob("0", JobSchedule.utc(Instant.now().minusSeconds(1)));
        first.poll();
        awaitExecutions(1);
        awaitFireStatus("FAILED");
        assertEquals("FAILED", jdbc.queryForObject(
                "SELECT status FROM sys_job_fire WHERE job_id=1", String.class));
        assertEquals("handler failed: IllegalStateException", jdbc.queryForObject(
                "SELECT result_summary FROM sys_job_fire WHERE job_id=1", String.class));
        second.poll();
        assertEquals(1, executions.get());
    }

    @Test
    void skipMisfireAdvancesPlanWithoutInvokingHandler() {
        insertJob("0", JobSchedule.utc(Instant.now().minusSeconds(60)));
        jdbc.update("UPDATE sys_job SET misfire_policy='3' WHERE job_id=1");
        first.poll();
        assertEquals("SKIPPED", jdbc.queryForObject(
                "SELECT status FROM sys_job_fire WHERE job_id=1", String.class));
        assertEquals(0, executions.get());
        assertNotNull(jdbc.queryForObject("SELECT next_fire_time FROM sys_job WHERE job_id=1",
                java.time.LocalDateTime.class));
    }

    @Test
    void changedJobCancelsExpiredClaimInsteadOfRunningStaleHandler() {
        insertJob("1", null);
        jdbc.update("UPDATE sys_job SET revision=1 WHERE job_id=1");
        jdbc.update("INSERT INTO sys_job_fire (job_id,job_revision,fire_key,trigger_type,status,attempt_count,"
                        + "claim_token,lease_until) VALUES (1,0,'S:1:stale','SCHEDULED','CLAIMED',1,'old',?)",
                JobSchedule.utc(Instant.now().minusSeconds(60)));
        second.poll();
        assertEquals("CANCELLED", jdbc.queryForObject(
                "SELECT status FROM sys_job_fire WHERE fire_key='S:1:stale'", String.class));
        assertEquals(0, executions.get());
    }

    @Test
    void startupRestoresMissingFuturePlanForEnabledRegisteredJob() {
        insertJob("0", null);
        first.onReady();
        var next = jdbc.queryForObject("SELECT next_fire_time FROM sys_job WHERE job_id=1",
                java.time.LocalDateTime.class);
        assertNotNull(next);
        assertTrue(next.isAfter(JobSchedule.utc(Instant.now())));
        assertEquals(0, jdbc.queryForObject("SELECT COUNT(*) FROM sys_job_fire", Integer.class));
    }

    @Test
    void cronUsesConfiguredIanaZoneAndPersistsUtcInstant() {
        assertEquals(java.time.LocalDateTime.parse("2026-09-25T18:00:00"),
                JobSchedule.nextUtc("0 0 2 * * ?", "Asia/Shanghai",
                        Instant.parse("2026-09-25T17:59:59Z")));
    }

    @Test
    void fullLocalCapacityLeavesNextDuePlanUnclaimedUntilWorkersDrain() throws Exception {
        var release = new CountDownLatch(1);
        doAnswer(invocation -> {
            executions.incrementAndGet();
            release.await(10, TimeUnit.SECONDS);
            return null;
        }).when(handler).execute(any(JsonNode.class));
        insertJob("0", JobSchedule.utc(Instant.now().plusSeconds(3600)));
        jdbc.update("UPDATE sys_job SET concurrent='0' WHERE job_id=1");
        try {
            for (int i = 0; i < 8; i++) {
                first.runNow(1L);
            }
            assertThrows(com.xinghuiTec.exception.ServiceException.class, () -> first.runNow(1L));
            assertEquals(8, jdbc.queryForObject("SELECT COUNT(*) FROM sys_job_fire", Integer.class));

            insertJob(2L, "0", JobSchedule.utc(Instant.now().minusSeconds(1)));
            var due = jdbc.queryForObject("SELECT next_fire_time FROM sys_job WHERE job_id=2",
                    java.time.LocalDateTime.class);
            first.poll();
            assertEquals(due, jdbc.queryForObject("SELECT next_fire_time FROM sys_job WHERE job_id=2",
                    java.time.LocalDateTime.class));
            assertEquals(0, jdbc.queryForObject(
                    "SELECT COUNT(*) FROM sys_job_fire WHERE job_id=2", Integer.class));
        } finally {
            release.countDown();
        }
        awaitFireCount("SUCCEEDED", 8);
        first.poll();
        awaitFireCount("SUCCEEDED", 9);
        assertEquals(1, jdbc.queryForObject(
                "SELECT COUNT(*) FROM sys_job_fire WHERE job_id=2", Integer.class));
    }

    private void insertJob(String status, java.time.LocalDateTime next) {
        insertJob(1L, status, next);
    }

    private void insertJob(Long jobId, String status, java.time.LocalDateTime next) {
        jdbc.update("INSERT INTO sys_job (job_id,tenant_id,is_deleted,job_name,job_group,handler_key,handler_params,"
                        + "cron_expression,time_zone,misfire_policy,concurrent,status,next_fire_time,revision) "
                        + "VALUES (?,'000000',0,'test job','DEFAULT','system.noop','{}','0 * * * * ?','UTC','2','1',?,?,0)",
                jobId, status, next);
    }

    private static void awaitAndPoll(CountDownLatch start, JobEngine engine) {
        try {
            start.await();
            engine.poll();
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
    }

    private void awaitExecutions(int expected) throws InterruptedException {
        long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(3);
        while (executions.get() < expected && System.nanoTime() < deadline) {
            Thread.sleep(10);
        }
        assertEquals(expected, executions.get());
    }

    private void awaitFireStatus(String status) throws InterruptedException {
        long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(3);
        while (System.nanoTime() < deadline) {
            Integer count = jdbc.queryForObject("SELECT COUNT(*) FROM sys_job_fire WHERE status=?",
                    Integer.class, status);
            if (count != null && count > 0) {
                return;
            }
            Thread.sleep(10);
        }
        fail("fire did not reach status " + status);
    }

    private void awaitFireCount(String status, int expected) throws InterruptedException {
        long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(5);
        while (System.nanoTime() < deadline) {
            Integer count = jdbc.queryForObject("SELECT COUNT(*) FROM sys_job_fire WHERE status=?",
                    Integer.class, status);
            if (count != null && count >= expected) {
                return;
            }
            Thread.sleep(10);
        }
        fail("expected " + expected + " fires in " + status);
    }
}
