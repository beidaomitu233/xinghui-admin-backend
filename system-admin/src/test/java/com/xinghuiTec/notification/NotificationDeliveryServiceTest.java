package com.xinghuiTec.notification;

import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DataSourceTransactionManager;
import org.springframework.jdbc.datasource.DriverManagerDataSource;

import java.sql.Timestamp;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.time.Instant;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.*;

class NotificationDeliveryServiceTest {
    @Test
    void duplicateEventPreservesFirstContentAndDifferentKeyDeliversSeparately() {
        Fixture fixture = new Fixture();
        fixture.user(1, "000000", 1, 0);
        long first = fixture.request("000000", 1, "sample.created", "same-key", "First");
        long duplicate = fixture.request("000000", 1, "sample.created", "same-key", "Changed");
        long other = fixture.request("000000", 1, "sample.created", "other-key", "Other");

        assertTrue(fixture.delivery.deliverOne(first));
        assertTrue(fixture.delivery.deliverOne(duplicate));
        assertTrue(fixture.delivery.deliverOne(other));
        assertEquals(2, fixture.count("SELECT COUNT(*) FROM sys_notification_inbox"));
        assertEquals("First", fixture.jdbc.queryForObject("SELECT title FROM sys_notification_inbox "
                + "WHERE event_key='same-key'", String.class));
        assertEquals("DUPLICATE", fixture.jdbc.queryForObject("SELECT delivery_outcome "
                + "FROM sys_notification_event_request WHERE request_id=?", String.class, duplicate));
        assertEquals("DELIVERED", fixture.status(duplicate));
        assertFalse(fixture.delivery.deliverOne(first));
    }

    @Test
    void rejectsMissingDisabledAndCrossTenantRecipientsWithoutContentLeak() {
        Fixture fixture = new Fixture();
        fixture.user(2, "000000", 0, 0);
        fixture.user(3, "000001", 1, 0);
        long missing = fixture.request("000000", 99, "sample.created", "missing", "secret");
        long disabled = fixture.request("000000", 2, "sample.created", "disabled", "secret");
        long cross = fixture.request("000000", 3, "sample.created", "cross", "secret");
        fixture.user(4, "000000", 1, 0);
        long unsafeTarget = fixture.request("000000", 4, "sample.created", "unsafe", "secret");
        fixture.jdbc.update("UPDATE sys_notification_event_request SET target=? WHERE request_id=?",
                "https://untrusted.example/path", unsafeTarget);

        fixture.delivery.deliverOne(missing);
        fixture.delivery.deliverOne(disabled);
        fixture.delivery.deliverOne(cross);
        fixture.delivery.deliverOne(unsafeTarget);
        assertEquals(0, fixture.count("SELECT COUNT(*) FROM sys_notification_inbox"));
        assertEquals("RECIPIENT_NOT_FOUND", fixture.failure(missing));
        assertEquals("RECIPIENT_DISABLED", fixture.failure(disabled));
        assertEquals("RECIPIENT_TENANT_MISMATCH", fixture.failure(cross));
        assertEquals("TARGET_INVALID", fixture.failure(unsafeTarget));
        assertEquals("FAILED", fixture.status(cross));
    }

    @Test
    void expiredClaimIsRecoveredAndTwoWorkersCannotClaimSameRequest() throws Exception {
        Fixture fixture = new Fixture();
        fixture.user(1, "000000", 1, 0);
        long expired = fixture.request("000000", 1, "sample.created", "expired", "Recovered");
        fixture.jdbc.update("UPDATE sys_notification_event_request SET status='PROCESSING',"
                + "claim_token='old',lease_until=?,attempt_count=1 WHERE request_id=?",
                Timestamp.from(Instant.now().minusSeconds(5)), expired);
        assertTrue(fixture.delivery.deliverOne(expired));
        assertEquals("DELIVERED", fixture.status(expired));

        long contested = fixture.request("000000", 1, "sample.created", "contested", "Once");
        CountDownLatch ready = new CountDownLatch(2);
        CountDownLatch start = new CountDownLatch(1);
        AtomicInteger claimed = new AtomicInteger();
        NotificationDeliveryService secondNode = new NotificationDeliveryService(
                fixture.jdbc, new DataSourceTransactionManager(fixture.source), false,
                new NotificationTemplateService(fixture.jdbc, ""));
        Runnable firstWorker = () -> {
            ready.countDown();
            try {
                start.await();
                if (fixture.delivery.deliverOne(contested)) claimed.incrementAndGet();
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            }
        };
        Runnable secondWorker = () -> {
            ready.countDown();
            try {
                start.await();
                if (secondNode.deliverOne(contested)) claimed.incrementAndGet();
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            }
        };
        Thread a = new Thread(firstWorker);
        Thread b = new Thread(secondWorker);
        a.start();
        b.start();
        ready.await();
        start.countDown();
        a.join();
        b.join();
        assertEquals(1, claimed.get());
        assertEquals(1, fixture.count("SELECT COUNT(*) FROM sys_notification_inbox WHERE event_key='contested'"));
    }

    @Test
    void transientFailureRetriesThreeTimesWithoutLeakingContentIntoFailureCode() {
        Fixture fixture = new Fixture();
        fixture.user(1, "000000", 1, 0);
        fixture.jdbc.execute("ALTER TABLE sys_notification_inbox ADD CONSTRAINT reject_test_title "
                + "CHECK (title <> 'Trigger')");
        long id = fixture.request("000000", 1, "sample.created", "retry", "Trigger");
        for (int attempt = 1; attempt <= 3; attempt++) {
            assertTrue(fixture.delivery.deliverOne(id));
            assertEquals("DELIVERY_ERROR", fixture.failure(id));
            assertEquals(attempt == 3 ? "FAILED" : "PENDING", fixture.status(id));
            if (attempt < 3) fixture.jdbc.update("UPDATE sys_notification_event_request "
                    + "SET next_attempt_at=? WHERE request_id=?",
                    Timestamp.from(Instant.now().minusSeconds(1)), id);
        }
        assertEquals(0, fixture.count("SELECT COUNT(*) FROM sys_notification_inbox"));
        assertTrue(fixture.delivery.requeueTransientFailure(id));
        assertEquals("PENDING", fixture.status(id));
        assertFalse(fixture.delivery.requeueTransientFailure(id));
    }

    @Test
    void uncommittedAndRolledBackRequestsAreNotConsumed() throws Exception {
        Fixture fixture = new Fixture();
        fixture.user(1, "000000", 1, 0);
        try (Connection connection = fixture.source.getConnection()) {
            connection.setAutoCommit(false);
            try (PreparedStatement insert = connection.prepareStatement("INSERT INTO sys_notification_event_request "
                    + "(tenant_id,recipient_user_id,event_code,event_key,title,body,target,status) "
                    + "VALUES ('000000',1,'sample.created','rolled-back','Hidden','Body','/safe','PENDING')")) {
                insert.executeUpdate();
                fixture.delivery.poll();
                assertEquals(0, fixture.count("SELECT COUNT(*) FROM sys_notification_inbox"));
                connection.rollback();
            }
        }
        fixture.delivery.poll();
        assertEquals(0, fixture.count("SELECT COUNT(*) FROM sys_notification_event_request"));
    }

    static final class Fixture {
        final DriverManagerDataSource source;
        final JdbcTemplate jdbc;
        final NotificationDeliveryService delivery;

        Fixture() {
            source = new DriverManagerDataSource(
                    "jdbc:h2:mem:ntf02_" + UUID.randomUUID() + ";MODE=MySQL;DB_CLOSE_DELAY=-1", "sa", "");
            jdbc = new JdbcTemplate(source);
            jdbc.execute("CREATE TABLE sys_user (user_id bigint PRIMARY KEY,tenant_id varchar(20),"
                    + "status int,is_deleted int)");
            jdbc.execute("CREATE TABLE sys_notification_event_request (request_id bigint AUTO_INCREMENT PRIMARY KEY,"
                    + "tenant_id varchar(20),recipient_user_id bigint,event_code varchar(100),event_key varchar(128),"
                    + "title varchar(200),body varchar(4000),target varchar(1024),status varchar(16),"
                    + "failure_code varchar(40),attempt_count int DEFAULT 0,claim_token varchar(36),"
                    + "lease_until timestamp(3),next_attempt_at timestamp(3),delivered_at timestamp(3),"
                    + "inbox_id bigint,delivery_outcome varchar(16))");
            jdbc.execute("CREATE TABLE sys_notification_inbox (notification_id bigint AUTO_INCREMENT PRIMARY KEY,"
                    + "source_request_id bigint UNIQUE,tenant_id varchar(20),recipient_user_id bigint,"
                    + "event_code varchar(100),event_key varchar(128),title varchar(200),body varchar(4000),"
                    + "target varchar(1024),create_time timestamp(3) DEFAULT CURRENT_TIMESTAMP(3),"
                    + "read_time timestamp(3),UNIQUE(tenant_id,event_code,event_key,recipient_user_id))");
            delivery = new NotificationDeliveryService(jdbc, new DataSourceTransactionManager(source), false,
                    new NotificationTemplateService(jdbc, ""));
        }

        void user(long id, String tenant, int status, int deleted) {
            jdbc.update("INSERT INTO sys_user VALUES (?,?,?,?)", id, tenant, status, deleted);
        }

        long request(String tenant, long user, String code, String key, String title) {
            jdbc.update("INSERT INTO sys_notification_event_request "
                    + "(tenant_id,recipient_user_id,event_code,event_key,title,body,target,status) "
                    + "VALUES (?,?,?,?,?,?,?,'PENDING')", tenant, user, code, key, title, "Body", "/safe");
            return jdbc.queryForObject("SELECT MAX(request_id) FROM sys_notification_event_request", Long.class);
        }

        long count(String sql) { return jdbc.queryForObject(sql, Long.class); }
        String status(long id) { return jdbc.queryForObject("SELECT status FROM sys_notification_event_request "
                + "WHERE request_id=?", String.class, id); }
        String failure(long id) { return jdbc.queryForObject("SELECT failure_code "
                + "FROM sys_notification_event_request WHERE request_id=?", String.class, id); }
    }
}
