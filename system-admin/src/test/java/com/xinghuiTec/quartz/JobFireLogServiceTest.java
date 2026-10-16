package com.xinghuiTec.quartz;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.xinghuiTec.exception.ServiceException;
import com.xinghuiTec.quartz.service.JobFireLogService;
import org.h2.tools.RunScript;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DriverManagerDataSource;

import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.sql.Connection;
import java.time.LocalDateTime;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

class JobFireLogServiceTest {
    private JdbcTemplate jdbc;
    private JobFireLogService logs;

    @BeforeEach
    void setUp() throws Exception {
        var dataSource = new DriverManagerDataSource();
        dataSource.setDriverClassName("org.h2.Driver");
        dataSource.setUrl("jdbc:h2:mem:job03;MODE=MySQL;DB_CLOSE_DELAY=-1");
        dataSource.setUsername("sa");
        jdbc = new JdbcTemplate(dataSource);
        jdbc.execute("DROP TABLE IF EXISTS sys_job_fire");
        jdbc.execute("DROP TABLE IF EXISTS sys_job");
        jdbc.execute("CREATE TABLE sys_job (job_id BIGINT PRIMARY KEY, tenant_id VARCHAR(20), "
                + "job_name VARCHAR(64), job_group VARCHAR(64), is_deleted INT)");
        jdbc.execute("CREATE TABLE sys_job_fire (fire_id BIGINT PRIMARY KEY, job_id BIGINT, "
                + "job_revision BIGINT, fire_key VARCHAR(120), trigger_type VARCHAR(16), "
                + "scheduled_fire_time DATETIME(3), status VARCHAR(16), attempt_count INT, "
                + "claim_token VARCHAR(36), lease_until DATETIME(3), started_at DATETIME(3), "
                + "completed_at DATETIME(3), result_summary VARCHAR(200))");
        try (Connection connection = dataSource.getConnection();
                var resource = getClass().getResourceAsStream("/db/migration/V20260926_70__job_fire_diagnostics.sql")) {
            assertNotNull(resource);
            RunScript.execute(connection, new InputStreamReader(resource, StandardCharsets.UTF_8));
        }
        jdbc.update("INSERT INTO sys_job VALUES (1,'000000','current name','DEFAULT',1)");
        logs = new JobFireLogService(jdbc);
    }

    @Test
    void listAndDetailUseSafeSnapshotAfterLogicalJobDeletion() throws Exception {
        insert(1, "SUCCEEDED", 40, "snapshot name", "trace-1", "completed");
        insert(2, "FAILED", 2, "snapshot name", "trace-2", "handler failed: IllegalStateException");
        var page = logs.list(1, 1, 1L, "snapshot name", "FAILED", "MANUAL");
        assertEquals(1, page.total());
        assertEquals(2L, page.records().get(0).fireId());
        assertEquals("snapshot name", logs.detail(2L).jobName());
        assertEquals("trace-2", logs.detail(2L).traceId());
        String json = new ObjectMapper().findAndRegisterModules().writeValueAsString(logs.detail(2L));
        assertFalse(json.contains("fireKey"));
        assertFalse(json.contains("claimToken"));
        assertFalse(json.contains("invokeTarget"));
        assertThrows(ServiceException.class, () -> logs.detail(999L));
        assertThrows(ServiceException.class, () -> logs.list(1, 101, null, null, null, null));
        assertThrows(ServiceException.class, () -> logs.list(1, 10, null, null, "INVALID", null));
    }

    @Test
    void removalAndCleanPreserveClaimedAndRecentDedupLedger() {
        insert(1, "SUCCEEDED", 40, "name", "trace-1", "completed");
        insert(2, "FAILED", 2, "name", "trace-2", "handler failed: TestException");
        insert(3, "CLAIMED", 100, "name", "trace-3", null);
        insert(4, "SKIPPED", 100, "name", "trace-4", "misfire skipped");
        assertThrows(ServiceException.class, () -> logs.remove(List.of(1L, 2L)));
        assertThrows(ServiceException.class, () -> logs.remove(List.of(3L)));
        assertEquals(4, logs.list(1, 10, null, null, null, null).total());
        assertEquals(1, logs.remove(List.of(1L)));
        assertEquals(1, logs.clean());
        assertEquals(2, logs.list(1, 10, null, null, null, null).total());
        assertEquals("CLAIMED", logs.detail(3L).status());
        assertThrows(ServiceException.class, () -> logs.detail(4L));
        assertEquals(0, logs.clean());
    }

    @Test
    void cleanIsBoundedToFiveHundredTerminalRowsPerCall() {
        for (long id = 1; id <= 501; id++) {
            insert(id, "SUCCEEDED", 100, "name", "trace-" + id, "completed");
        }
        assertEquals(500, logs.clean());
        assertEquals(1, logs.list(1, 10, null, null, null, null).total());
        assertEquals(1, logs.clean());
        assertEquals(0, logs.clean());
        assertThrows(ServiceException.class, () -> logs.remove(java.util.Collections.nCopies(101, 1L)));
    }

    private void insert(long id, String status, int daysAgo, String name, String trace, String summary) {
        LocalDateTime when = LocalDateTime.now().minusDays(daysAgo);
        jdbc.update("INSERT INTO sys_job_fire (fire_id,job_id,job_revision,fire_key,trigger_type,"
                        + "scheduled_fire_time,status,attempt_count,started_at,completed_at,result_summary,"
                        + "trace_id,job_name_snapshot,job_group_snapshot) "
                        + "VALUES (?,1,0,?,'MANUAL',NULL,?,1,?,?,?,?,?,'DEFAULT')",
                id, "M:" + id, status, when, "CLAIMED".equals(status) ? null : when,
                summary, trace, name);
    }
}
