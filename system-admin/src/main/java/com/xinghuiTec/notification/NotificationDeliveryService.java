package com.xinghuiTec.notification;

import com.xinghuiTec.constants.TenantConstants;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.support.GeneratedKeyHolder;
import org.springframework.jdbc.support.KeyHolder;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.sql.PreparedStatement;
import java.sql.Timestamp;
import java.time.Instant;
import java.util.List;
import java.util.UUID;

/** Claims only committed requests; the inbox insert and request completion share one transaction. */
@Service
public class NotificationDeliveryService {
    private static final int MAX_ATTEMPTS = 3;
    private static final Logger log = LoggerFactory.getLogger(NotificationDeliveryService.class);
    private final JdbcTemplate jdbc;
    private final TransactionTemplate transactions;
    private final boolean tenantEnabled;
    private final NotificationTemplateService templates;

    public NotificationDeliveryService(JdbcTemplate jdbc, PlatformTransactionManager manager,
            @Value("${tenant.enable:false}") boolean tenantEnabled,
            NotificationTemplateService templates) {
        this.jdbc = jdbc;
        this.transactions = new TransactionTemplate(manager);
        this.tenantEnabled = tenantEnabled;
        this.templates = templates;
    }

    @Scheduled(fixedDelayString = "${notification.delivery.poll-ms:1000}")
    public void poll() {
        try {
            Timestamp now = Timestamp.from(Instant.now());
            List<Long> due = jdbc.query("""
                    SELECT request_id FROM sys_notification_event_request
                     WHERE (status='PENDING' AND (next_attempt_at IS NULL OR next_attempt_at<=?))
                        OR (status='PROCESSING' AND lease_until<?)
                     ORDER BY request_id LIMIT 20
                    """, (rs, row) -> rs.getLong(1), now, now);
            for (Long requestId : due) deliverOne(requestId);
        } catch (RuntimeException ignored) {
            // The next poll retries. Never log request content or raw database exceptions.
            log.warn("Notification delivery poll failed; it will retry");
        }
    }

    /** Returns true only when this caller claimed a due request. Also usable by controlled tests. */
    public boolean deliverOne(long requestId) {
        if (requestId <= 0) return false;
        String token = UUID.randomUUID().toString();
        Timestamp now = Timestamp.from(Instant.now());
        Timestamp lease = Timestamp.from(Instant.now().plusSeconds(60));
        int claimed = jdbc.update("""
                UPDATE sys_notification_event_request
                   SET status='PROCESSING',claim_token=?,lease_until=?,attempt_count=attempt_count+1
                 WHERE request_id=? AND ((status='PENDING' AND (next_attempt_at IS NULL OR next_attempt_at<=?))
                     OR (status='PROCESSING' AND lease_until<?))
                """, token, lease, requestId, now, now);
        if (claimed != 1) return false;
        try {
            transactions.executeWithoutResult(status -> completeClaim(requestId, token));
        } catch (RuntimeException ignored) {
            retryOrFail(requestId, token);
            log.warn("Notification delivery attempt failed for request {}", requestId);
        }
        return true;
    }

    /** Internal-only compensation after the bounded automatic retries are exhausted. */
    public boolean requeueTransientFailure(long requestId) {
        if (requestId <= 0) return false;
        return jdbc.update("""
                UPDATE sys_notification_event_request
                   SET status='PENDING',failure_code=NULL,attempt_count=0,next_attempt_at=NULL
                 WHERE request_id=? AND status='FAILED' AND failure_code='DELIVERY_ERROR'
                """, requestId) == 1;
    }

    private void completeClaim(long requestId, String token) {
        List<Request> requests = jdbc.query("""
                SELECT request_id,tenant_id,recipient_user_id,event_code,event_key,title,body,target
                  FROM sys_notification_event_request
                 WHERE request_id=? AND status='PROCESSING' AND claim_token=? FOR UPDATE
                """, (rs, row) -> new Request(rs.getLong(1), rs.getString(2), rs.getLong(3),
                rs.getString(4), rs.getString(5), rs.getString(6), rs.getString(7), rs.getString(8)),
                requestId, token);
        if (requests.isEmpty()) return;
        Request request = requests.get(0);
        String failure = recipientFailure(request);
        if (failure == null && (request.title() == null || request.body() == null)) {
            failure = "REQUEST_CONTENT_INVALID";
        }
        if (failure == null) {
            try {
                templates.rules().validateRenderedTarget(request.target());
            } catch (NotificationContractException invalidTarget) {
                failure = "TARGET_INVALID";
            }
        }
        if (failure != null) {
            jdbc.update("""
                    UPDATE sys_notification_event_request
                       SET status='FAILED',failure_code=?,claim_token=NULL,lease_until=NULL,
                           next_attempt_at=NULL WHERE request_id=? AND claim_token=?
                    """, failure, requestId, token);
            return;
        }
        KeyHolder holder = new GeneratedKeyHolder();
        long inboxId;
        String outcome;
        try {
            jdbc.update(connection -> {
                PreparedStatement statement = connection.prepareStatement("""
                        INSERT INTO sys_notification_inbox
                          (source_request_id,tenant_id,recipient_user_id,event_code,event_key,title,body,target)
                        VALUES (?,?,?,?,?,?,?,?)
                        """, new String[] { "notification_id" });
                statement.setLong(1, request.id());
                statement.setString(2, request.tenantId());
                statement.setLong(3, request.recipientId());
                statement.setString(4, request.eventCode());
                statement.setString(5, request.eventKey());
                statement.setString(6, request.title());
                statement.setString(7, request.body());
                statement.setString(8, request.target());
                return statement;
            }, holder);
            inboxId = holder.getKey().longValue();
            outcome = "CREATED";
        } catch (DuplicateKeyException duplicate) {
            inboxId = jdbc.queryForObject("""
                    SELECT notification_id FROM sys_notification_inbox
                     WHERE tenant_id=? AND recipient_user_id=? AND event_code=? AND event_key=?
                    """, Long.class, request.tenantId(), request.recipientId(),
                    request.eventCode(), request.eventKey());
            outcome = "DUPLICATE"; // Preserve the first successfully delivered content.
        }
        jdbc.update("""
                UPDATE sys_notification_event_request
                   SET status='DELIVERED',failure_code=NULL,claim_token=NULL,lease_until=NULL,
                       next_attempt_at=NULL,delivered_at=?,inbox_id=?,delivery_outcome=?
                 WHERE request_id=? AND claim_token=?
                """, Timestamp.from(Instant.now()), inboxId, outcome, requestId, token);
    }

    private String recipientFailure(Request request) {
        if (!tenantEnabled && !TenantConstants.DEFAULT_TENANT_ID.equals(request.tenantId())) {
            return "RECIPIENT_TENANT_MISMATCH";
        }
        List<Recipient> users = jdbc.query("""
                SELECT tenant_id,status,is_deleted FROM sys_user WHERE user_id=?
                """, (rs, row) -> new Recipient(rs.getString(1), rs.getInt(2), rs.getInt(3)),
                request.recipientId());
        if (users.isEmpty()) return "RECIPIENT_NOT_FOUND";
        Recipient user = users.get(0);
        if (user.deleted() != 0 || user.status() != 1) return "RECIPIENT_DISABLED";
        String expected = tenantEnabled ? request.tenantId() : TenantConstants.DEFAULT_TENANT_ID;
        return expected.equals(user.tenantId()) ? null : "RECIPIENT_TENANT_MISMATCH";
    }

    private void retryOrFail(long requestId, String token) {
        try {
            List<Integer> attempts = jdbc.query("""
                    SELECT attempt_count FROM sys_notification_event_request
                     WHERE request_id=? AND status='PROCESSING' AND claim_token=?
                    """, (rs, row) -> rs.getInt(1), requestId, token);
            if (attempts.isEmpty()) return;
            int attempt = attempts.get(0);
            if (attempt >= MAX_ATTEMPTS) {
                jdbc.update("""
                        UPDATE sys_notification_event_request
                           SET status='FAILED',failure_code='DELIVERY_ERROR',claim_token=NULL,
                               lease_until=NULL,next_attempt_at=NULL
                         WHERE request_id=? AND status='PROCESSING' AND claim_token=?
                        """, requestId, token);
            } else {
                long delaySeconds = (long) Math.pow(5, attempt);
                jdbc.update("""
                        UPDATE sys_notification_event_request
                           SET status='PENDING',failure_code='DELIVERY_ERROR',claim_token=NULL,
                               lease_until=NULL,next_attempt_at=?
                         WHERE request_id=? AND status='PROCESSING' AND claim_token=?
                        """, Timestamp.from(Instant.now().plusSeconds(delaySeconds)), requestId, token);
            }
        } catch (RuntimeException ignored) {
            // If the database is down, the lease expires and another poll can reclaim the row.
        }
    }

    private record Request(long id, String tenantId, long recipientId, String eventCode,
                           String eventKey, String title, String body, String target) {}
    private record Recipient(String tenantId, int status, int deleted) {}
}
