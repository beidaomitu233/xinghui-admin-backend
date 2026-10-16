package com.xinghuiTec.quartz.service;

import com.xinghuiTec.exception.ServiceException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Instant;
import java.time.LocalDateTime;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;

/** Read-only diagnostic projection and bounded terminal-ledger retention. */
@Service
public class JobFireLogService {
    private static final int MAX_PAGE = 100;
    private static final int MAX_REMOVE = 100;
    private static final int CLEAN_BATCH = 500;
    private static final Set<String> STATUSES = Set.of("CLAIMED", "SUCCEEDED", "FAILED", "SKIPPED", "CANCELLED");
    private static final Set<String> TERMINAL = Set.of("SUCCEEDED", "FAILED", "SKIPPED", "CANCELLED");
    private static final Set<String> TRIGGERS = Set.of("SCHEDULED", "MANUAL");
    private final JdbcTemplate jdbc;

    public JobFireLogService(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    public record FireLog(Long fireId, Long jobId, String jobName, String jobGroup,
            String triggerType, LocalDateTime scheduledFireTime, String status, int attemptCount,
            LocalDateTime startedAt, LocalDateTime completedAt, String resultSummary, String traceId) {
    }

    public record FirePage(List<FireLog> records, long total, int pageNum, int pageSize) {
    }

    public FirePage list(Integer pageNum, Integer pageSize, Long jobId, String jobName,
            String status, String triggerType) {
        int page = pageNum == null ? 1 : pageNum;
        int size = pageSize == null ? 10 : pageSize;
        if (page < 1 || page > 100000 || size < 1 || size > MAX_PAGE) {
            throw new ServiceException("分页参数无效，pageSize 最大 100");
        }
        if (jobId != null && jobId <= 0) throw new ServiceException("jobId 无效");
        if (jobName != null && jobName.length() > 64) throw new ServiceException("jobName 过长");
        if (status != null && !status.isBlank() && !STATUSES.contains(status)) {
            throw new ServiceException("status 无效");
        }
        if (triggerType != null && !triggerType.isBlank() && !TRIGGERS.contains(triggerType)) {
            throw new ServiceException("triggerType 无效");
        }
        StringBuilder where = new StringBuilder(" WHERE f.tenant_id='000000'");
        List<Object> params = new ArrayList<>();
        if (jobId != null) { where.append(" AND f.job_id=?"); params.add(jobId); }
        if (jobName != null && !jobName.isBlank()) {
            where.append(" AND COALESCE(f.job_name_snapshot,j.job_name)=?");
            params.add(jobName.trim());
        }
        if (status != null && !status.isBlank()) { where.append(" AND f.status=?"); params.add(status); }
        if (triggerType != null && !triggerType.isBlank()) {
            where.append(" AND f.trigger_type=?"); params.add(triggerType);
        }
        String from = " FROM sys_job_fire f LEFT JOIN sys_job j ON j.job_id=f.job_id "
                + "AND j.tenant_id='000000'";
        Long total = jdbc.queryForObject("SELECT COUNT(*)" + from + where, Long.class, params.toArray());
        List<Object> pageParams = new ArrayList<>(params);
        pageParams.add(size);
        pageParams.add((page - 1) * size);
        List<FireLog> records = jdbc.query(select() + from + where + " ORDER BY f.fire_id DESC LIMIT ? OFFSET ?",
                (rs, row) -> new FireLog(rs.getLong(1), rs.getLong(2), rs.getString(3), rs.getString(4),
                        rs.getString(5), rs.getObject(6, LocalDateTime.class), rs.getString(7), rs.getInt(8),
                        rs.getObject(9, LocalDateTime.class), rs.getObject(10, LocalDateTime.class),
                        rs.getString(11), rs.getString(12)), pageParams.toArray());
        return new FirePage(records, total == null ? 0 : total, page, size);
    }

    public FireLog detail(Long fireId) {
        if (fireId == null || fireId <= 0) throw new ServiceException("fireId 无效");
        List<FireLog> found = jdbc.query(select() + " FROM sys_job_fire f LEFT JOIN sys_job j "
                        + "ON j.job_id=f.job_id AND j.tenant_id='000000' "
                        + "WHERE f.tenant_id='000000' AND f.fire_id=?",
                (rs, row) -> new FireLog(rs.getLong(1), rs.getLong(2), rs.getString(3), rs.getString(4),
                        rs.getString(5), rs.getObject(6, LocalDateTime.class), rs.getString(7), rs.getInt(8),
                        rs.getObject(9, LocalDateTime.class), rs.getObject(10, LocalDateTime.class),
                        rs.getString(11), rs.getString(12)), fireId);
        if (found.isEmpty()) throw new ServiceException("执行记录不存在");
        return found.get(0);
    }

    private static String select() {
        return "SELECT f.fire_id,f.job_id,COALESCE(f.job_name_snapshot,j.job_name),"
                + "COALESCE(f.job_group_snapshot,j.job_group),f.trigger_type,f.scheduled_fire_time,"
                + "f.status,f.attempt_count,f.started_at,f.completed_at,f.result_summary,f.trace_id";
    }

    /** Manual deletion preserves at least 30 days of deduplication history. */
    @Transactional
    public int remove(List<Long> fireIds) {
        if (fireIds == null || fireIds.isEmpty() || fireIds.size() > MAX_REMOVE
                || fireIds.stream().anyMatch(id -> id == null || id <= 0)) {
            throw new ServiceException("每次只能删除 1 至 100 条有效记录");
        }
        List<Long> ids = new ArrayList<>(new LinkedHashSet<>(fireIds));
        String placeholders = String.join(",", java.util.Collections.nCopies(ids.size(), "?"));
        LocalDateTime cutoff = LocalDateTime.ofInstant(Instant.now().minusSeconds(30L * 86400), ZoneOffset.UTC);
        List<RetentionRow> rows = jdbc.query("SELECT fire_id,status,completed_at FROM sys_job_fire "
                        + "WHERE tenant_id='000000' AND fire_id IN ("
                        + placeholders + ") FOR UPDATE",
                (rs, row) -> new RetentionRow(rs.getLong(1), rs.getString(2),
                        rs.getObject(3, LocalDateTime.class)), ids.toArray());
        if (rows.size() != ids.size() || rows.stream().anyMatch(row -> !TERMINAL.contains(row.status)
                || row.completedAt == null || !row.completedAt.isBefore(cutoff))) {
            throw new ServiceException("仅可删除已完成且超过 30 天的执行记录");
        }
        return jdbc.update("DELETE FROM sys_job_fire WHERE tenant_id='000000' AND fire_id IN ("
                + placeholders + ")", ids.toArray());
    }

    /** One bounded pass per call; callers may repeat until the returned count is zero. */
    @Transactional
    public int clean() {
        LocalDateTime cutoff = LocalDateTime.ofInstant(Instant.now().minusSeconds(90L * 86400), ZoneOffset.UTC);
        List<Long> ids = jdbc.query("SELECT fire_id FROM sys_job_fire WHERE tenant_id='000000' "
                        + "AND status IN ('SUCCEEDED','FAILED','SKIPPED','CANCELLED') "
                        + "AND completed_at < ? ORDER BY fire_id LIMIT " + CLEAN_BATCH,
                (rs, row) -> rs.getLong(1), cutoff);
        if (ids.isEmpty()) return 0;
        String placeholders = String.join(",", java.util.Collections.nCopies(ids.size(), "?"));
        List<Object> args = new ArrayList<>(ids);
        args.add(cutoff);
        return jdbc.update("DELETE FROM sys_job_fire WHERE tenant_id='000000' AND fire_id IN ("
                + placeholders + ") AND status IN ('SUCCEEDED','FAILED','SKIPPED','CANCELLED') "
                + "AND completed_at < ?", args.toArray());
    }

    private record RetentionRow(Long fireId, String status, LocalDateTime completedAt) {
    }
}
