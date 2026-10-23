package com.xinghuiTec.notification;

import com.xinghuiTec.domain.entity.LoginUser;
import com.xinghuiTec.domain.entity.SysUser;
import com.xinghuiTec.exception.ServiceException;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DataSourceTransactionManager;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.context.SecurityContextHolder;

import java.util.List;
import java.util.Map;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.*;

/** Opt-in real flow. Never runs on the default database or during ordinary unit tests. */
class NotificationInboxIsolatedDbTest {
    @AfterEach
    void clearLogin() { SecurityContextHolder.clearContext(); }

    @Test
    @EnabledIfEnvironmentVariable(named = "NTF02_ISOLATED_DB", matches = "true")
    void twoEventsDeliverIdempotentlyAndOnlyRecipientCanReadAndMarkThem() {
        if (!"xinghuiadmintest".equals(System.getenv("DB_NAME"))) {
            throw new IllegalStateException("DB_NAME must be xinghuiadmintest");
        }
        String host = System.getenv().getOrDefault("DB_HOST", "127.0.0.1");
        String port = System.getenv().getOrDefault("DB_PORT", "3306");
        DriverManagerDataSource source = new DriverManagerDataSource(
                "jdbc:mysql://" + host + ":" + port + "/xinghuiadmintest"
                        + "?useUnicode=true&characterEncoding=utf8&useSSL=false&serverTimezone=Asia/Shanghai",
                required("DB_USERNAME"), required("DB_PASSWORD"));
        JdbcTemplate jdbc = new JdbcTemplate(source);
        assertEquals(1, jdbc.queryForObject("SELECT COUNT(*) FROM flyway_schema_history "
                + "WHERE version='20260926.70' AND success=1", Integer.class));
        assertEquals(1, jdbc.queryForObject("SELECT COUNT(*) FROM flyway_schema_history "
                + "WHERE version='20260926.80' AND success=1", Integer.class));

        String suffix = UUID.randomUUID().toString().replace("-", "");
        String createdCode = "ntf02.created." + suffix;
        String reviewedCode = "ntf02.reviewed." + suffix;
        String createdTarget = System.getenv().getOrDefault(
                "NTF02_CREATED_TARGET", "/records/{number}");
        NotificationContentRules rules = new NotificationContentRules("");
        NotificationEventPublisher publisher = new NotificationEventService(jdbc, rules);
        NotificationDeliveryService delivery = new NotificationDeliveryService(jdbc,
                new DataSourceTransactionManager(source), false, rules);
        boolean keepFixtures = "true".equalsIgnoreCase(System.getenv("NTF02_KEEP_FIXTURES"));
        try {
            var first = publisher.submit(new NotificationEventPublisher.Event("000000", 1,
                    createdCode, "record:" + suffix, "记录 中性示例 已创建", "编号 N-1", createdTarget.replace("{number}", "N-1")));
            var duplicate = publisher.submit(new NotificationEventPublisher.Event("000000", 1,
                    createdCode, "record:" + suffix, "记录 不同内容 已创建", "编号 N-2", null));
            var second = publisher.submit(new NotificationEventPublisher.Event("000000", 1,
                    reviewedCode, "review:" + suffix, "审核 完成", "请查看 R-1", "/reviews/R-1"));
            assertEquals("PENDING", first.status());
            assertTrue(delivery.deliverOne(first.requestId()));
            assertTrue(delivery.deliverOne(duplicate.requestId()));
            assertTrue(delivery.deliverOne(second.requestId()));
            assertEquals("DUPLICATE", jdbc.queryForObject("SELECT delivery_outcome "
                    + "FROM sys_notification_event_request WHERE request_id=?", String.class, duplicate.requestId()));
            assertEquals(2, jdbc.queryForObject("SELECT COUNT(*) FROM sys_notification_inbox "
                    + "WHERE event_code IN (?,?)", Integer.class, createdCode, reviewedCode));
            assertEquals("记录 中性示例 已创建", jdbc.queryForObject("SELECT title "
                    + "FROM sys_notification_inbox WHERE event_code=?", String.class, createdCode));

            login(1);
            NotificationInboxService inbox = new NotificationInboxService(jdbc, false);
            assertEquals(2, inbox.unreadCount());
            assertEquals(2, inbox.page(1, 20, false).total());
            long firstId = jdbc.queryForObject("SELECT notification_id FROM sys_notification_inbox "
                    + "WHERE event_code=?", Long.class, createdCode);
            assertEquals("记录 中性示例 已创建", inbox.detail(firstId).title());
            assertNull(inbox.detail(firstId).readTime());

            login(2);
            assertThrows(ServiceException.class, () -> inbox.detail(firstId));
            assertThrows(ServiceException.class, () -> inbox.markRead(firstId));
            assertEquals(0, inbox.unreadCount());
        } finally {
            SecurityContextHolder.clearContext();
            if (keepFixtures) {
                System.out.println("NTF02_CREATED_EVENT_CODE=" + createdCode);
                System.out.println("NTF02_REVIEWED_EVENT_CODE=" + reviewedCode);
            } else {
                cleanup(jdbc, createdCode, reviewedCode);
            }
        }
        if (!keepFixtures) {
            assertEquals(0, jdbc.queryForObject("SELECT COUNT(*) FROM sys_notification_inbox "
                    + "WHERE event_code IN (?,?)", Integer.class, createdCode, reviewedCode));
            assertEquals(0, jdbc.queryForObject("SELECT COUNT(*) FROM sys_notification_event_request "
                    + "WHERE event_code IN (?,?)", Integer.class, createdCode, reviewedCode));
        }
    }

    private static void cleanup(JdbcTemplate jdbc, String createdCode, String reviewedCode) {
        jdbc.update("DELETE FROM sys_notification_inbox WHERE event_code IN (?,?)", createdCode, reviewedCode);
        jdbc.update("DELETE FROM sys_notification_event_request WHERE event_code IN (?,?)", createdCode, reviewedCode);
    }

    private static void login(long id) {
        SysUser user = new SysUser();
        user.setUserId(id);
        user.setTenantId("000000");
        LoginUser principal = new LoginUser(user, List.of());
        principal.setTenantId("000000");
        SecurityContextHolder.getContext().setAuthentication(
                new UsernamePasswordAuthenticationToken(principal, null, principal.getAuthorities()));
    }

    private static String required(String name) {
        String value = System.getenv(name);
        if (value == null || value.isBlank()) throw new IllegalArgumentException(name + " is required");
        return value;
    }
}
