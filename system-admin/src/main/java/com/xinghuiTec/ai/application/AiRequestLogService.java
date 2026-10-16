package com.xinghuiTec.ai.application;

import com.xinghuiTec.ai.contract.AiRuntimeException;
import com.xinghuiTec.ai.contract.AiTypes.ErrorCode;
import com.xinghuiTec.ai.contract.AiTypes.TrustedActor;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;

import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Timestamp;
import java.time.Instant;
import java.util.*;

/** Metadata queries only: no renderer, credential access, provider invocation or output bodies. */
@Service
public class AiRequestLogService {
    public enum Scope { OWN, AUDIT }
    public record Filters(Scope scope, String appId, String requestId, String businessId, String modelRef,
                          AiRequestStatus status, String tenantRef, String subjectRef, Instant from, Instant to) {}
    public record Row(AiRequestStore.Request request, String appName, String tenantRef, String subjectRef, long elapsedMs) {}
    public record Page(List<Row> records, long total, int pageNum, int pageSize) {}
    public record Attempt(String attemptId, int number, String configVersion, String modelRef, String keyRef,
                          String maskedKeyLabel, String switchReason, String status, String errorCode,
                          Long inputTokens, Long outputTokens, Instant createdAt, Instant finishedAt,
                          String providerCode, String modelCode, String keyVersion, String providerRequestId, Integer outputTokenLimit) {}
    public record Stage(String stageId, String stage, String outcome, String errorCode, String referenceKind,
                        String referenceId, String referenceVersion, Instant createdAt) {}
    public record Detail(Row request, List<Attempt> attempts, List<Stage> stages) {}
    public record Statistics(long requests, long businessRequests, long testRequests, long modelAttempts,
                             Long knownInputTokens, Long knownOutputTokens, long unknownInputAttempts, long unknownOutputAttempts) {}
    private record Where(String sql, List<Object> args, boolean empty) {}
    private final JdbcTemplate jdbc;
    private final AiApplicationStore applications;
    private final AiApplicationService versions;

    public AiRequestLogService(JdbcTemplate jdbc, AiApplicationStore applications, AiApplicationService versions) {
        this.jdbc = jdbc; this.applications = applications; this.versions = versions;
    }

    public Page page(TrustedActor actor, boolean platform, Filters filters, int pageNum, int pageSize) {
        if (pageNum < 1 || pageNum > 100_000 || pageSize < 1 || pageSize > 100) throw new AiRuntimeException(ErrorCode.INVALID_INPUT);
        Where where = where(actor, platform, filters, "list");
        if (where.empty()) return new Page(List.of(), 0, pageNum, pageSize);
        long total = count(where);
        var args = new ArrayList<>(where.args()); args.add(pageSize); args.add((pageNum - 1) * pageSize);
        var rows = jdbc.query(select() + where.sql() + " ORDER BY r.request_id DESC LIMIT ? OFFSET ?", this::row, args.toArray());
        return new Page(List.copyOf(rows), total, pageNum, pageSize);
    }

    public Detail detail(TrustedActor actor, boolean platform, Filters filters, long requestId) {
        if (requestId < 1) throw new AiRuntimeException(ErrorCode.INVALID_INPUT);
        if (filters == null) throw new AiRuntimeException(ErrorCode.INVALID_INPUT);
        if (present(filters.requestId()) && !Long.toString(requestId).equals(filters.requestId())) throw new AiRuntimeException(ErrorCode.NOT_FOUND);
        Filters point = new Filters(filters.scope(), filters.appId(), Long.toString(requestId), filters.businessId(), filters.modelRef(),
                filters.status(), filters.tenantRef(), filters.subjectRef(), filters.from(), filters.to());
        Where where = where(actor, platform, point, "query");
        if (where.empty()) throw new AiRuntimeException(ErrorCode.NOT_FOUND);
        var args = new ArrayList<>(where.args()); args.add(requestId);
        var rows = jdbc.query(select() + where.sql() + " AND r.request_id=?", this::row, args.toArray());
        if (rows.size() != 1) throw new AiRuntimeException(ErrorCode.NOT_FOUND);
        var attempts = jdbc.query("SELECT * FROM sys_ai_request_attempt WHERE request_id=? ORDER BY attempt_number", (rs, index) ->
                new Attempt(Long.toString(rs.getLong("attempt_id")), rs.getInt("attempt_number"), rs.getString("config_version"),
                        rs.getString("model_ref"), rs.getString("key_ref"), rs.getString("masked_key_label"), rs.getString("switch_reason"),
                        rs.getString("status"), rs.getString("error_code"), rs.getObject("input_tokens", Long.class), rs.getObject("output_tokens", Long.class),
                        instant(rs, "create_time"), instant(rs, "finish_time"), rs.getString("provider_code"), rs.getString("model_code"),
                        rs.getString("key_version"), rs.getString("provider_request_id"), rs.getObject("output_token_limit", Integer.class)), requestId);
        var stages = jdbc.query("SELECT * FROM sys_ai_request_stage WHERE request_id=? ORDER BY stage_id", (rs, index) ->
                new Stage(Long.toString(rs.getLong("stage_id")), rs.getString("stage"), rs.getString("outcome"), rs.getString("error_code"),
                        rs.getString("reference_kind"), rs.getString("reference_id"), rs.getString("reference_version"), instant(rs, "create_time")), requestId);
        return new Detail(rows.get(0), List.copyOf(attempts), List.copyOf(stages));
    }

    public Statistics statistics(TrustedActor actor, boolean platform, Filters filters) {
        Where where = where(actor, platform, filters, "list");
        if (where.empty()) return new Statistics(0, 0, 0, 0, null, null, 0, 0);
        long total = count(where);
        Long business = jdbc.queryForObject("SELECT COUNT(*) FROM sys_ai_request r" + where.sql() + " AND r.request_kind='BUSINESS'", Long.class, where.args().toArray());
        return jdbc.queryForObject("SELECT COUNT(*),SUM(a.input_tokens),SUM(a.output_tokens),"
                        + "SUM(CASE WHEN a.input_tokens IS NULL THEN 1 ELSE 0 END),SUM(CASE WHEN a.output_tokens IS NULL THEN 1 ELSE 0 END)"
                        + " FROM sys_ai_request_attempt a JOIN sys_ai_request r ON r.request_id=a.request_id" + where.sql(),
                (rs, index) -> new Statistics(total, business == null ? 0 : business, total - (business == null ? 0 : business),
                        rs.getLong(1), rs.getObject(2, Long.class), rs.getObject(3, Long.class), rs.getLong(4), rs.getLong(5)), where.args().toArray());
    }

    /** JSON export preserves string IDs and cannot execute spreadsheet formula text. */
    public List<Row> export(TrustedActor actor, boolean platform, Filters filters) {
        Where where = where(actor, platform, filters, "export");
        if (where.empty()) return List.of();
        if (count(where) > 5000) throw new AiRuntimeException(ErrorCode.INVALID_INPUT);
        return List.copyOf(jdbc.query(select() + where.sql() + " ORDER BY r.request_id DESC LIMIT 5000", this::row, where.args().toArray()));
    }

    private Where where(TrustedActor actor, boolean platform, Filters filter, String action) {
        if (!actor.authorities().contains("ai:request:" + action)) throw new AiRuntimeException(ErrorCode.FORBIDDEN);
        if (filter == null || filter.scope() == null) throw new AiRuntimeException(ErrorCode.INVALID_INPUT);
        if (filter.scope() == Scope.AUDIT && (!platform || !actor.authorities().contains("ai:request:audit"))) {
            throw new AiRuntimeException(ErrorCode.FORBIDDEN);
        }
        var sql = new StringBuilder(" WHERE 1=1");
        var args = new ArrayList<Object>();
        if (filter.scope() == Scope.OWN) {
            var owner = AiRuntimeService.owner(actor);
            if ((present(filter.tenantRef()) && !owner.tenantRef().equals(filter.tenantRef()))
                    || (present(filter.subjectRef()) && !owner.subjectRef().equals(filter.subjectRef()))) {
                throw new AiRuntimeException(ErrorCode.FORBIDDEN);
            }
            sql.append(" AND r.owner_scope=? AND r.tenant_ref=? AND r.subject_ref=?");
            args.add(owner.scope()); args.add(owner.tenantRef()); args.add(owner.subjectRef());
        } else {
            equal(sql, args, "r.tenant_ref", filter.tenantRef(), 64);
            equal(sql, args, "r.subject_ref", filter.subjectRef(), 128);
        }
        if (present(filter.appId())) { sql.append(" AND r.app_id=?"); args.add(AiApplicationService.positiveId(filter.appId())); }
        if (present(filter.requestId())) { sql.append(" AND r.request_id=?"); args.add(AiApplicationService.positiveId(filter.requestId())); }
        equal(sql, args, "r.business_id", filter.businessId(), 128);
        if (filter.status() != null) { sql.append(" AND r.status=?"); args.add(filter.status().name()); }
        if (present(filter.modelRef())) {
            text(filter.modelRef(), 128);
            sql.append(" AND EXISTS(SELECT 1 FROM sys_ai_request_attempt f WHERE f.request_id=r.request_id AND f.model_ref=?)");
            args.add(filter.modelRef());
        }
        if (!"query".equals(action) || filter.from() != null || filter.to() != null) {
            Instant to = filter.to() == null ? Instant.now() : filter.to();
            Instant from = filter.from() == null ? to.minus(java.time.Duration.ofDays(7)) : filter.from();
            if (from.isAfter(to) || java.time.Duration.between(from, to).compareTo(java.time.Duration.ofDays(31)) > 0) {
                throw new AiRuntimeException(ErrorCode.INVALID_INPUT);
            }
            sql.append(" AND r.create_time>=? AND r.create_time<=?"); args.add(Timestamp.from(from)); args.add(Timestamp.from(to));
        }
        boolean empty = filter.scope() == Scope.OWN && !restrictApplications(sql, args, actor);
        return new Where(sql.toString(), List.copyOf(args), empty);
    }

    private boolean restrictApplications(StringBuilder sql, List<Object> args, TrustedActor actor) {
        var candidates = jdbc.query("SELECT DISTINCT r.app_id,r.app_version_id,r.request_kind FROM sys_ai_request r" + sql + " LIMIT 501",
                (rs, index) -> new Object[] {rs.getLong(1), rs.getObject(2, Long.class), rs.getString(3)}, args.toArray());
        if (candidates.size() > 500) throw new AiRuntimeException(ErrorCode.INVALID_INPUT); // Narrow the app/time filters.
        var versionsAllowed = new LinkedHashSet<Long>();
        var testAppsAllowed = new LinkedHashSet<Long>();
        for (Object[] candidate : candidates) {
            long appId = (Long) candidate[0]; Long version = (Long) candidate[1];
            if (version != null) {
                if (versions.version(appId, version, false).snapshot().policy().permits(actor)) versionsAllowed.add(version);
            } else if ("TEST".equals(candidate[2]) && applications.get(appId).draft().permitsSubject(actor)) testAppsAllowed.add(appId);
        }
        // No SQL constant-false predicate: Druid wall rejects it. Callers must short-circuit
        // only after all action/scope/filter checks above and the actual application permission check.
        if (versionsAllowed.isEmpty() && testAppsAllowed.isEmpty()) return false;
        sql.append(" AND (");
        boolean both = !versionsAllowed.isEmpty() && !testAppsAllowed.isEmpty();
        if (!versionsAllowed.isEmpty()) {
            sql.append("(r.request_kind='BUSINESS' AND r.app_version_id IN (").append(placeholders(versionsAllowed.size())).append("))");
            args.addAll(versionsAllowed);
        }
        if (both) sql.append(" OR ");
        if (!testAppsAllowed.isEmpty()) {
            sql.append("(r.request_kind='TEST' AND r.app_id IN (").append(placeholders(testAppsAllowed.size())).append("))");
            args.addAll(testAppsAllowed);
        }
        sql.append(")");
        return true;
    }

    private String select() { return "SELECT r.*,a.name AS app_name FROM sys_ai_request r JOIN sys_ai_application a ON a.app_id=r.app_id"; }
    private long count(Where where) {
        Long count = jdbc.queryForObject("SELECT COUNT(*) FROM sys_ai_request r" + where.sql(), Long.class, where.args().toArray());
        return count == null ? 0 : count;
    }
    private Row row(ResultSet rs, int index) throws SQLException {
        Long version = rs.getObject("app_version_id", Long.class);
        Instant created = instant(rs, "create_time"), finished = instant(rs, "finish_time");
        var request = new AiRequestStore.Request(Long.toString(rs.getLong("request_id")), Long.toString(rs.getLong("app_id")),
                version == null ? null : version.toString(), rs.getObject("draft_revision", Integer.class), AiRequestStore.Kind.valueOf(rs.getString("request_kind")),
                rs.getString("business_type"), rs.getString("business_id"), AiRequestStatus.valueOf(rs.getString("status")), AiRequestStore.Stage.valueOf(rs.getString("stage")),
                rs.getString("error_code"), rs.getBoolean("output_started"), rs.getInt("attempt_count"), rs.getObject("input_tokens", Long.class),
                rs.getObject("output_tokens", Long.class), rs.getBoolean("usage_known"), instant(rs, "deadline"), created, finished);
        return new Row(request, rs.getString("app_name"), rs.getString("tenant_ref"), rs.getString("subject_ref"),
                Math.max(0, java.time.Duration.between(created, finished == null ? Instant.now() : finished).toMillis()));
    }
    private static Instant instant(ResultSet rs, String name) throws SQLException { Timestamp timestamp = rs.getTimestamp(name); return timestamp == null ? null : timestamp.toInstant(); }
    private static boolean present(String value) { return value != null && !value.isBlank(); }
    private static String placeholders(int count) { return String.join(",", Collections.nCopies(count, "?")); }
    private static void equal(StringBuilder sql, List<Object> args, String column, String value, int max) {
        if (present(value)) { text(value, max); sql.append(" AND ").append(column).append("=?"); args.add(value); }
    }
    private static void text(String value, int max) {
        if (value.length() > max || value.codePoints().anyMatch(Character::isISOControl)) throw new AiRuntimeException(ErrorCode.INVALID_INPUT);
    }
}
