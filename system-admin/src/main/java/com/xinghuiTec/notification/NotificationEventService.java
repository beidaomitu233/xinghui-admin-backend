package com.xinghuiTec.notification;

import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.support.GeneratedKeyHolder;
import org.springframework.jdbc.support.KeyHolder;
import org.springframework.stereotype.Service;

import java.sql.PreparedStatement;
import java.sql.Statement;
import java.util.regex.Pattern;

/** Persists requests for the inbox worker. PENDING means accepted, not delivered. */
@Service
public class NotificationEventService implements NotificationEventPublisher {
    private static final Pattern TENANT = Pattern.compile("[A-Za-z0-9_-]{1,20}");
    private static final Pattern EVENT_CODE = Pattern.compile("[a-z][a-z0-9_.:-]{0,99}");
    private static final Pattern EVENT_KEY = Pattern.compile("[A-Za-z0-9._:-]{1,128}");
    private static final Pattern FAILURE_CODE = Pattern.compile("[A-Z][A-Z0-9_]{0,39}");

    private final JdbcTemplate jdbc;
    private final NotificationTemplateService templates;

    public NotificationEventService(JdbcTemplate jdbc, NotificationTemplateService templates) {
        this.jdbc = jdbc;
        this.templates = templates;
    }

    @Override
    public Submission submit(Event event) {
        if (event == null) throw new NotificationContractException("EVENT_INVALID", "事件请求不能为空");
        try {
            validateEnvelope(event);
            NotificationTemplateService.View template = templates.enabledByCode(event.eventCode());
            NotificationTemplateRules.Rendered rendered = templates.rules().render(
                    template.titleTemplate(), template.bodyTemplate(), template.targetTemplate(),
                    template.allowedVariables(), event.variables());
            KeyHolder holder = new GeneratedKeyHolder();
            jdbc.update(connection -> {
                PreparedStatement statement = connection.prepareStatement("""
                        INSERT INTO sys_notification_event_request
                          (tenant_id,recipient_user_id,event_code,event_key,template_id,title,body,target,status)
                        VALUES (?,?,?,?,?,?,?,?,?)
                        """, Statement.RETURN_GENERATED_KEYS);
                statement.setString(1, event.tenantId());
                statement.setLong(2, event.recipientUserId());
                statement.setString(3, event.eventCode());
                statement.setString(4, event.eventKey());
                statement.setLong(5, template.id());
                statement.setString(6, rendered.title());
                statement.setString(7, rendered.body());
                statement.setString(8, rendered.target());
                statement.setString(9, "PENDING");
                return statement;
            }, holder);
            return new Submission(holder.getKey().longValue(), "PENDING");
        } catch (NotificationContractException rejected) {
            recordRejected(event, rejected.code());
            throw rejected;
        }
    }

    @Override
    public void markFailed(long requestId, String failureCode) {
        if (requestId <= 0 || failureCode == null || !FAILURE_CODE.matcher(failureCode).matches()) {
            throw new IllegalArgumentException("Invalid request ID or failure code");
        }
        int changed = jdbc.update("""
                UPDATE sys_notification_event_request
                   SET status='FAILED',failure_code=? WHERE request_id=? AND status='PENDING'
                """, failureCode, requestId);
        if (changed != 1) throw new IllegalStateException("Pending notification request not found");
    }

    private static void validateEnvelope(Event event) {
        if (event.tenantId() == null || !TENANT.matcher(event.tenantId()).matches()
                || event.recipientUserId() <= 0 || event.eventCode() == null
                || !EVENT_CODE.matcher(event.eventCode()).matches() || event.eventKey() == null
                || !EVENT_KEY.matcher(event.eventKey()).matches()) {
            throw new NotificationContractException("EVENT_INVALID", "事件收件人、编码或事件键无效");
        }
    }

    private void recordRejected(Event event, String code) {
        jdbc.update("""
                INSERT INTO sys_notification_event_request
                  (tenant_id,recipient_user_id,event_code,event_key,status,failure_code)
                VALUES (?,?,?,?,?,?)
                """, safe(event.tenantId(), TENANT), Math.max(0, event.recipientUserId()),
                safe(event.eventCode(), EVENT_CODE), safe(event.eventKey(), EVENT_KEY),
                "REJECTED", code);
    }

    private static String safe(String value, Pattern pattern) {
        return value != null && pattern.matcher(value).matches() ? value : "invalid";
    }
}
