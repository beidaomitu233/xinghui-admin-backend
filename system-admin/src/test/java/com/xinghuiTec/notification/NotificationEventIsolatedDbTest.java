package com.xinghuiTec.notification;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DriverManagerDataSource;

import java.util.List;
import java.util.Map;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.*;

/** Opt-in smoke test: exercises production publisher code against the dedicated migrated database. */
class NotificationEventIsolatedDbTest {
    @Test
    @EnabledIfEnvironmentVariable(named = "NTF01_ISOLATED_DB", matches = "true")
    void persistsPendingAndSafeRejectedRequestsThenRemovesOnlyItsOwnRows() {
        if (!"xinghuiadmintest".equals(System.getenv("DB_NAME"))) {
            throw new IllegalStateException("DB_NAME must be xinghuiadmintest");
        }
        String host = System.getenv().getOrDefault("DB_HOST", "127.0.0.1");
        String port = System.getenv().getOrDefault("DB_PORT", "3306");
        String url = "jdbc:mysql://" + host + ":" + port + "/xinghuiadmintest"
                + "?useUnicode=true&characterEncoding=utf8&useSSL=false&serverTimezone=Asia/Shanghai";
        JdbcTemplate jdbc = new JdbcTemplate(new DriverManagerDataSource(url,
                required("DB_USERNAME"), required("DB_PASSWORD")));
        assertEquals(1, jdbc.queryForObject("SELECT COUNT(*) FROM flyway_schema_history "
                + "WHERE version='20260926.60' AND success=1", Integer.class));

        String suffix = UUID.randomUUID().toString().replace("-", "");
        String eventCode = "ntf01.integration." + suffix;
        String pendingKey = "created:" + suffix;
        String rejectedKey = "rejected:" + suffix;
        NotificationTemplateService templates = new NotificationTemplateService(jdbc, "");
        NotificationEventPublisher publisher = new NotificationEventService(jdbc, templates);
        long templateId = templates.create(new NotificationTemplateService.Create(
                eventCode, "NTF01 隔离验证", "记录 {name} 已创建", "编号 {number}",
                "/records/{number}", List.of("name", "number"), true));
        try {
            NotificationEventPublisher.Submission accepted = publisher.submit(
                    new NotificationEventPublisher.Event("000000", 1L, eventCode, pendingKey,
                            Map.of("name", "中性示例", "number", "N-1")));
            assertEquals("PENDING", accepted.status());
            Map<String, Object> pending = jdbc.queryForMap("SELECT tenant_id,recipient_user_id,"
                    + "event_code,event_key,template_id,title,body,target,status,failure_code "
                    + "FROM sys_notification_event_request WHERE request_id=?", accepted.requestId());
            assertEquals("000000", pending.get("tenant_id"));
            assertEquals(1L, ((Number) pending.get("recipient_user_id")).longValue());
            assertEquals(templateId, ((Number) pending.get("template_id")).longValue());
            assertEquals("记录 中性示例 已创建", pending.get("title"));
            assertEquals("编号 N-1", pending.get("body"));
            assertEquals("/records/N-1", pending.get("target"));
            assertEquals("PENDING", pending.get("status"));
            assertNull(pending.get("failure_code"));

            NotificationContractException rejected = assertThrows(NotificationContractException.class,
                    () -> publisher.submit(new NotificationEventPublisher.Event(
                            "000000", 1L, eventCode, rejectedKey, Map.of("name", "不应持久化"))));
            assertEquals("VARIABLE_MISSING", rejected.code());
            Map<String, Object> failure = jdbc.queryForMap("SELECT tenant_id,recipient_user_id,"
                    + "event_code,event_key,template_id,title,body,target,status,failure_code "
                    + "FROM sys_notification_event_request WHERE event_code=? AND event_key=?",
                    eventCode, rejectedKey);
            assertEquals("000000", failure.get("tenant_id"));
            assertEquals("REJECTED", failure.get("status"));
            assertEquals("VARIABLE_MISSING", failure.get("failure_code"));
            assertNull(failure.get("template_id"));
            assertNull(failure.get("title"));
            assertNull(failure.get("body"));
            assertNull(failure.get("target"));
        } finally {
            jdbc.update("DELETE FROM sys_notification_event_request WHERE event_code=? "
                    + "AND event_key IN (?,?)", eventCode, pendingKey, rejectedKey);
            jdbc.update("DELETE FROM sys_notification_template WHERE template_id=? AND event_code=?",
                    templateId, eventCode);
        }
        assertEquals(0, jdbc.queryForObject("SELECT COUNT(*) FROM sys_notification_event_request "
                + "WHERE event_code=?", Integer.class, eventCode));
        assertEquals(0, jdbc.queryForObject("SELECT COUNT(*) FROM sys_notification_template "
                + "WHERE event_code=?", Integer.class, eventCode));
    }

    private static String required(String name) {
        String value = System.getenv(name);
        if (value == null || value.isBlank()) throw new IllegalArgumentException(name + " is required");
        return value;
    }
}
