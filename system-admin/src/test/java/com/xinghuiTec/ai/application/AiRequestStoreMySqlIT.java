package com.xinghuiTec.ai.application;

import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DataSourceTransactionManager;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import org.springframework.jdbc.support.GeneratedKeyHolder;
import org.springframework.transaction.TransactionDefinition;
import org.springframework.transaction.support.TransactionTemplate;

import java.sql.Statement;
import java.time.Instant;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicLong;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Explicit -Dtest=AiRequestStoreMySqlIT only, in the PM-authorized scratch schema.
 * V200 is a DDL fixture here, not Flyway evidence. No startup, reset, secrets or provider traffic.
 */
class AiRequestStoreMySqlIT {
    @Test
    void replayReadsConcurrentWinnerEvenAfterOuterRepeatableReadSnapshotWasEstablished() {
        String password = System.getenv("AI_V04_B_DB_PASSWORD");
        assertNotNull(password, "Assigned B database password must be supplied privately");
        String url = "jdbc:mysql://127.0.0.1:13319/aiv04_b_rr_20261016"
                + "?useUnicode=true&characterEncoding=utf8&useSSL=false&allowPublicKeyRetrieval=true"
                + "&serverTimezone=Asia/Shanghai";
        var outerSource = new DriverManagerDataSource(url, "ai_v04_b", password);
        var writerSource = new DriverManagerDataSource(url, "ai_v04_b", password);
        var jdbc = new JdbcTemplate(outerSource);
        var writer = new JdbcTemplate(writerSource);
        assertEquals("aiv04_b_rr_20261016", jdbc.queryForObject("SELECT DATABASE()", String.class));
        assertEquals(13319, jdbc.queryForObject("SELECT @@port", Integer.class));
        assertTrue(jdbc.queryForObject("SELECT CURRENT_USER()", String.class).startsWith("ai_v04_b@"));
        assertEquals(0, jdbc.queryForObject("SELECT COUNT(*) FROM information_schema.tables"
                + " WHERE table_schema=DATABASE() AND table_name='flyway_schema_history'", Integer.class));
        int tables = jdbc.queryForObject("SELECT COUNT(*) FROM information_schema.tables"
                + " WHERE table_schema=DATABASE()", Integer.class);
        if (tables == 0) {
            try (var connection = outerSource.getConnection()) {
                org.springframework.jdbc.datasource.init.ScriptUtils.executeSqlScript(connection,
                        new org.springframework.core.io.ClassPathResource(
                                "db/migration/V20261016_200__ai_app_runtime_v04.sql"));
            } catch (java.sql.SQLException error) {
                throw new IllegalStateException("Scratch DDL fixture failed", error);
            }
        } else {
            assertEquals(8, tables, "Only this task's scratch fixture tables are permitted");
        }
        System.out.println("AI-V04-B scratch MySQL=" + jdbc.queryForObject("SELECT @@version", String.class));

        var keys = new GeneratedKeyHolder();
        jdbc.update(connection -> {
            var statement = connection.prepareStatement("INSERT INTO sys_ai_application"
                    + "(name,purpose,owner_id,entry_key,draft_json) VALUES(?,?,1,'lowcode.record.summary','{}')",
                    Statement.RETURN_GENERATED_KEYS);
            statement.setString(1, "b-rr-test-" + UUID.randomUUID());
            statement.setString(2, "isolated RR test fixture");
            return statement;
        }, keys);
        assertNotNull(keys.getKey());
        long appId = keys.getKey().longValue();
        try {
            var outer = new TransactionTemplate(new DataSourceTransactionManager(outerSource));
            outer.setIsolationLevel(TransactionDefinition.ISOLATION_REPEATABLE_READ);
            var independent = new TransactionTemplate(new DataSourceTransactionManager(writerSource));
            independent.setIsolationLevel(TransactionDefinition.ISOLATION_REPEATABLE_READ);
            var readerStore = new AiRequestStore(jdbc);
            var writerStore = new AiRequestStore(writer);
            var owner = new AiRequestStore.Owner("test:rr:" + UUID.randomUUID(), "platform");
            var winnerId = new AtomicLong();
            Instant now = Instant.now();
            var admission = new AiRequestStore.Admission(appId, null, 1, AiRequestStore.Kind.TEST, owner,
                    "lowcode.record", "1:1", UUID.randomUUID().toString(), "a".repeat(64), now.plusSeconds(60));
            outer.execute(status -> {
                assertEquals("REPEATABLE-READ", jdbc.queryForObject("SELECT @@transaction_isolation", String.class));
                System.out.println("AI-V04-B RR isolation=REPEATABLE_READ; scratch fixture only");
                // A normal SELECT fixes this transaction's RR snapshot before the winner commits.
                assertEquals(0, jdbc.queryForObject("SELECT COUNT(*) FROM sys_ai_request WHERE app_id=?",
                        Integer.class, appId));
                var winner = independent.execute(writerStatus -> writerStore.accept(admission, now));
                assertNotNull(winner);
                assertTrue(winner.created());
                winnerId.set(Long.parseLong(winner.request().requestId()));
                assertEquals(0, jdbc.queryForObject("SELECT COUNT(*) FROM sys_ai_request WHERE app_id=?",
                        Integer.class, appId), "Plain RR read must still miss the committed winner");
                var replay = readerStore.accept(admission, now.plusMillis(1));
                assertFalse(replay.created());
                assertEquals(Long.toString(winnerId.get()), replay.request().requestId());
                assertEquals(AiRequestStatus.QUEUED, replay.request().status());
                assertEquals(Long.toString(appId), replay.request().appId());
                return null;
            });
            assertEquals(1, jdbc.queryForObject("SELECT COUNT(*) FROM sys_ai_request WHERE app_id=?",
                    Integer.class, appId));
        } finally {
            // Fixture IDs only; never clean/reset an entire schema or delete preserved user data.
            jdbc.update("DELETE FROM sys_ai_request WHERE app_id=?", appId);
            jdbc.update("DELETE FROM sys_ai_application WHERE app_id=?", appId);
        }
    }
}
