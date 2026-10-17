package com.xinghuiTec.ai.application;

import org.springframework.dao.DuplicateKeyException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.support.GeneratedKeyHolder;
import org.springframework.stereotype.Repository;
import org.springframework.transaction.annotation.Transactional;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.sql.PreparedStatement;
import java.sql.Statement;
import java.sql.Timestamp;
import java.time.Instant;
import java.util.HexFormat;
import java.util.List;

/**
 * Persistence only. Callers must authorize the application and business reference before admission.
 * No messages, raw inputs, outputs, exception text or credentials belong in this store.
 */
@Repository
public class AiRequestStore {
    private final JdbcTemplate jdbc;

    public AiRequestStore(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    public enum Stage { ACCEPTED, DATA, RENDER, SELECT, PROVIDER, FINISHED }
    public enum Kind { BUSINESS, TEST }

    /** Scope must be made from the authenticated server identity, never a caller-supplied userId. */
    public record Owner(String subjectRef, String tenantRef) {
        public Owner {
            requireText(subjectRef, 128);
            requireText(tenantRef, 64);
        }
        public String scope() { return digest(subjectRef, tenantRef); }
    }

    /** inputFingerprint is a keyed digest of canonical validated input, not the input itself. */
    public record Admission(long appId, Long appVersionId, Integer draftRevision, Kind kind,
                            Owner owner, String businessType, String businessId,
                            String idempotencyKey, String inputFingerprint, Instant deadline,String sceneKey,String sceneVersionId) {
        public Admission(long appId,Long appVersionId,Integer draftRevision,Kind kind,Owner owner,String businessType,String businessId,
                         String idempotencyKey,String inputFingerprint,Instant deadline) {
            this(appId,appVersionId,draftRevision,kind,owner,businessType,businessId,idempotencyKey,inputFingerprint,deadline,null,null);
        }
        public Admission {
            if (appId <= 0 || kind == null || owner == null || deadline == null) {
                throw new IllegalArgumentException("Invalid admission");
            }
            if ((kind == Kind.BUSINESS && (appVersionId == null || appVersionId <= 0 || draftRevision != null))
                    || (kind == Kind.TEST && sceneKey==null && (appVersionId != null || draftRevision == null || draftRevision <= 0))
                    || (sceneKey!=null && (appVersionId==null || appVersionId<=0 || draftRevision!=null || sceneVersionId==null))) {
                throw new IllegalArgumentException("Invalid version binding");
            }
            requireText(businessType, 64);
            requireText(businessId, 128);
            requireText(idempotencyKey, 128);
            if (inputFingerprint == null || !inputFingerprint.matches("[0-9a-f]{64}")) {
                throw new IllegalArgumentException("Invalid input fingerprint");
            }
        }
        @Override public String toString() { return "Admission[redacted]"; }
    }

    public record Request(String requestId, String appId, String appVersionId, Integer draftRevision,
                          Kind kind, String businessType, String businessId, AiRequestStatus status,
                          Stage stage, String errorCode, boolean outputStarted, int attemptCount,
                          Long inputTokens, Long outputTokens, boolean usageKnown,
                          Instant deadline, Instant createdAt, Instant finishedAt,String sceneKey,String sceneVersionId) {
        public Request(String requestId,String appId,String appVersionId,Integer draftRevision,Kind kind,String businessType,String businessId,
                       AiRequestStatus status,Stage stage,String errorCode,boolean outputStarted,int attemptCount,Long inputTokens,Long outputTokens,
                       boolean usageKnown,Instant deadline,Instant createdAt,Instant finishedAt) {
            this(requestId,appId,appVersionId,draftRevision,kind,businessType,businessId,status,stage,errorCode,outputStarted,attemptCount,inputTokens,outputTokens,
                    usageKnown,deadline,createdAt,finishedAt,null,null);
        }
    }
    public record Accepted(Request request, boolean created) {}

    /** Same owner+app+key returns one request, including after a new version is published. */
    @Transactional
    public Accepted accept(Admission admission, Instant now) {
        if (now == null || !admission.deadline().isAfter(now)) {
            throw new IllegalArgumentException("Deadline has expired");
        }
        String scope = digest(admission.owner().scope(), Long.toString(admission.appId()),
                admission.kind().name(), admission.idempotencyKey());
        var keys = new GeneratedKeyHolder();
        try {
            jdbc.update(connection -> {
                boolean scene=admission.sceneKey()!=null;
                PreparedStatement statement = connection.prepareStatement("""
                        INSERT INTO sys_ai_request
                        (app_id,app_version_id,draft_revision,request_kind,owner_scope,subject_ref,tenant_ref,
                         business_type,business_id,idempotency_scope,input_fingerprint,status,stage,
                         deadline,create_time,update_time
                        """+(scene?",scene_key,scene_version_id)":")")
                        +" VALUES (?,?,?,?,?,?,?,?,?,?,?,'QUEUED','ACCEPTED',?,?,?"+(scene?",?,?)":")"), Statement.RETURN_GENERATED_KEYS);
                statement.setLong(1, admission.appId());
                statement.setObject(2, admission.appVersionId());
                statement.setObject(3, admission.draftRevision());
                statement.setString(4, admission.kind().name());
                statement.setString(5, admission.owner().scope());
                statement.setString(6, admission.owner().subjectRef());
                statement.setString(7, admission.owner().tenantRef());
                statement.setString(8, admission.businessType());
                statement.setString(9, admission.businessId());
                statement.setString(10, scope);
                statement.setString(11, admission.inputFingerprint());
                statement.setTimestamp(12, Timestamp.from(admission.deadline()));
                statement.setTimestamp(13, Timestamp.from(now));
                statement.setTimestamp(14, Timestamp.from(now));
                if(scene){statement.setString(15,admission.sceneKey());statement.setLong(16,Long.parseLong(admission.sceneVersionId()));}
                return statement;
            }, keys);
        } catch (DuplicateKeyException duplicate) {
            // The unique key serializes simultaneous admissions; no worker is scheduled for a replay.
            var rows = jdbc.query("SELECT input_fingerprint,business_type,business_id,request_id FROM sys_ai_request"
                            + " WHERE idempotency_scope=? FOR UPDATE", (rs, index) -> new Object[] {
                            rs.getString(1), rs.getString(2), rs.getString(3), rs.getLong(4)}, scope);
            if (rows.size() != 1) throw duplicate;
            Object[] existing = rows.get(0);
            if (!MessageDigest.isEqual(admission.inputFingerprint().getBytes(StandardCharsets.US_ASCII),
                    ((String) existing[0]).getBytes(StandardCharsets.US_ASCII))
                    || !admission.businessType().equals(existing[1]) || !admission.businessId().equals(existing[2])) {
                throw new IllegalArgumentException("IDEMPOTENCY_CONFLICT");
            }
            // Do not return to a consistent read: an outer MySQL RR transaction may have
            // established its snapshot before the concurrent winner committed.
            return new Accepted(read((Long) existing[3], admission.owner(), true), false);
        }
        Number id = keys.getKey();
        if (id == null) throw new IllegalStateException("Request ID unavailable");
        return new Accepted(read(id.longValue(), admission.owner(), true), true);
    }

    /** Owner-scoped read deliberately makes missing and other-owner IDs indistinguishable. */
    public Request owned(long requestId, Owner owner) {
        return read(requestId, owner, false);
    }

    /** Read-only replay lookup in the existing admission ledger; never admits or schedules work. */
    public Request sceneTestReplay(long appId,String sceneKey,Owner owner,String nonce,String fingerprint) {
        String scope=digest(owner.scope(),Long.toString(appId),Kind.TEST.name(),nonce);
        var rows=jdbc.query("SELECT input_fingerprint,request_id FROM sys_ai_request WHERE idempotency_scope=?"
                        +" AND owner_scope=? AND subject_ref=? AND tenant_ref=? AND app_id=? AND scene_key=? AND request_kind='TEST' FOR UPDATE",
                (rs,n)->new Object[]{rs.getString(1),rs.getLong(2)},scope,owner.scope(),owner.subjectRef(),owner.tenantRef(),appId,sceneKey);
        if(rows.isEmpty())return null;
        if(!MessageDigest.isEqual(fingerprint.getBytes(StandardCharsets.US_ASCII),((String)rows.get(0)[0]).getBytes(StandardCharsets.US_ASCII)))
            throw new IllegalArgumentException("IDEMPOTENCY_CONFLICT");
        return read((Long)rows.get(0)[1],owner,true);
    }

    Request currentOwned(long requestId, Owner owner) { return read(requestId, owner, true); }

    private Request read(long requestId, Owner owner, boolean currentRead) {
        List<Request> rows = jdbc.query("SELECT * FROM sys_ai_request WHERE request_id=? AND owner_scope=?"
                        + " AND subject_ref=? AND tenant_ref=?" + (currentRead ? " FOR UPDATE" : ""), (rs, index) -> {
                    Long version = rs.getObject("app_version_id", Long.class);
                    Timestamp finished = rs.getTimestamp("finish_time");
                    return new Request(Long.toString(rs.getLong("request_id")),
                            Long.toString(rs.getLong("app_id")), version == null ? null : version.toString(),
                            rs.getObject("draft_revision", Integer.class), Kind.valueOf(rs.getString("request_kind")),
                            rs.getString("business_type"), rs.getString("business_id"),
                            AiRequestStatus.valueOf(rs.getString("status")), Stage.valueOf(rs.getString("stage")),
                            rs.getString("error_code"), rs.getBoolean("output_started"), rs.getInt("attempt_count"),
                            rs.getObject("input_tokens", Long.class), rs.getObject("output_tokens", Long.class),
                            rs.getBoolean("usage_known"), rs.getTimestamp("deadline").toInstant(),
                            rs.getTimestamp("create_time").toInstant(), finished == null ? null : finished.toInstant(),optionalColumn(rs,"scene_key"),optionalColumn(rs,"scene_version_id"));
                }, requestId, owner.scope(), owner.subjectRef(), owner.tenantRef());
        if (rows.size() != 1) throw new IllegalArgumentException("REQUEST_NOT_FOUND");
        return rows.get(0);
    }

    private static String optionalColumn(java.sql.ResultSet rs,String name)throws java.sql.SQLException {
        var metadata=rs.getMetaData();for(int i=1;i<=metadata.getColumnCount();i++)if(name.equalsIgnoreCase(metadata.getColumnLabel(i)))return rs.getString(i);
        return null;
    }

    public boolean start(long requestId, Instant now) {
        return jdbc.update("UPDATE sys_ai_request SET status='RUNNING',update_time=?"
                + " WHERE request_id=? AND status='QUEUED' AND deadline>?",
                Timestamp.from(now), requestId, Timestamp.from(now)) == 1;
    }

    public boolean stage(long requestId, Stage stage, Instant now) {
        if (stage == null || stage == Stage.FINISHED) throw new IllegalArgumentException("Invalid stage");
        return jdbc.update("UPDATE sys_ai_request SET stage=?,update_time=? WHERE request_id=? AND status IN ('QUEUED','RUNNING')",
                stage.name(), Timestamp.from(now), requestId) == 1;
    }

    /** Persist before forwarding the first fragment; failed CAS must suppress that fragment. */
    public boolean markOutputStarted(long requestId, Instant now) {
        return jdbc.update("UPDATE sys_ai_request SET output_started=TRUE,update_time=?"
                        + " WHERE request_id=? AND status='RUNNING' AND deadline>?",
                Timestamp.from(now), requestId, Timestamp.from(now)) == 1;
    }

    /** Terminal CAS also arbitrates completion versus cancellation; unknown usage stays null. */
    public boolean finish(long requestId, AiRequestStatus terminal, String safeErrorCode,
                          Long inputTokens, Long outputTokens, Instant now) {
        if (terminal == null || !terminal.terminal()) throw new IllegalArgumentException("Terminal status required");
        if (terminal == AiRequestStatus.SUCCEEDED && safeErrorCode != null) {
            throw new IllegalArgumentException("Success cannot carry an error code");
        }
        if (safeErrorCode != null && !safeErrorCode.matches("[A-Z][A-Z0-9_]{0,63}")) {
            throw new IllegalArgumentException("Safe error code required");
        }
        if ((inputTokens != null && inputTokens < 0) || (outputTokens != null && outputTokens < 0)) {
            throw new IllegalArgumentException("Invalid usage");
        }
        String allowed = terminal == AiRequestStatus.SUCCEEDED ? "status='RUNNING' AND deadline>?"
                : "status IN ('QUEUED','RUNNING')";
        var args = new java.util.ArrayList<Object>(List.of(terminal.name()));
        args.add(safeErrorCode);
        args.add(inputTokens);
        args.add(outputTokens);
        args.add(inputTokens != null && outputTokens != null);
        args.add(Timestamp.from(now));
        args.add(Timestamp.from(now));
        args.add(requestId);
        if (terminal == AiRequestStatus.SUCCEEDED) args.add(Timestamp.from(now));
        return jdbc.update("UPDATE sys_ai_request SET status=?,stage='FINISHED',error_code=?,input_tokens=?,"
                + "output_tokens=?,usage_known=?,finish_time=?,update_time=? WHERE request_id=? AND " + allowed,
                args.toArray()) == 1;
    }

    /** Cluster safe: never declare another healthy instance's in-flight requests interrupted. */
    @Transactional
    public int expire(Instant now) {
        int changed = jdbc.update("UPDATE sys_ai_request SET status=CASE WHEN output_started=TRUE THEN 'INCOMPLETE'"
                        + " ELSE 'TIMED_OUT' END,stage='FINISHED',error_code='DEADLINE_EXCEEDED',"
                        + "finish_time=?,update_time=? WHERE status IN ('QUEUED','RUNNING') AND deadline<=?",
                Timestamp.from(now), Timestamp.from(now), Timestamp.from(now));
        jdbc.update("""
                UPDATE sys_ai_request_attempt
                   SET status=CASE WHEN request_id IN (SELECT request_id FROM sys_ai_request WHERE status='TIMED_OUT')
                                   THEN 'TIMED_OUT' ELSE 'INCOMPLETE' END,
                       error_code=CASE WHEN request_id IN (SELECT request_id FROM sys_ai_request WHERE status='CANCELLED')
                                       THEN 'CANCELLED' ELSE 'DEADLINE_EXCEEDED' END,
                       finish_time=?
                 WHERE status='RUNNING' AND request_id IN
                       (SELECT request_id FROM sys_ai_request WHERE deadline<=? AND status IN ('TIMED_OUT','INCOMPLETE','CANCELLED','FAILED'))
                """, Timestamp.from(now), Timestamp.from(now));
        return changed;
    }

    private static String digest(String... values) {
        try {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            for (String value : values) {
                byte[] bytes = value.getBytes(StandardCharsets.UTF_8);
                digest.update(java.nio.ByteBuffer.allocate(4).putInt(bytes.length).array());
                digest.update(bytes);
            }
            return HexFormat.of().formatHex(digest.digest());
        } catch (NoSuchAlgorithmException impossible) {
            throw new IllegalStateException("SHA-256 unavailable");
        }
    }

    private static void requireText(String value, int max) {
        if (value == null || value.isBlank() || value.length() > max
                || value.codePoints().anyMatch(Character::isISOControl)) {
            throw new IllegalArgumentException("Invalid metadata");
        }
    }
}
