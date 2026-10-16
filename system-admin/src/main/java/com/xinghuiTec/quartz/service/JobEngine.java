package com.xinghuiTec.quartz.service;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.xinghuiTec.exception.ServiceException;
import com.xinghuiTec.quartz.handler.JobHandlerRegistry;
import jakarta.annotation.PreDestroy;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.context.event.ApplicationReadyEvent;
import org.springframework.context.event.EventListener;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

import java.time.Duration;
import java.time.Instant;
import java.time.LocalDateTime;
import java.time.ZoneOffset;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.Executors;
import java.util.concurrent.ArrayBlockingQueue;
import java.util.concurrent.Semaphore;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.ThreadPoolExecutor;
import java.util.concurrent.TimeUnit;

/** DB-backed claim/lease scheduler. All database DATETIME values are UTC wall time. */
@Component
public class JobEngine {
    private static final Logger log = LoggerFactory.getLogger(JobEngine.class);
    private static final Duration LEASE = Duration.ofSeconds(30);
    private static final Duration MISFIRE_GRACE = Duration.ofSeconds(5);
    private final JdbcTemplate jdbc;
    private final TransactionTemplate transactions;
    private final JobHandlerRegistry handlers;
    private final ObjectMapper json;
    private final String nodeId = UUID.randomUUID().toString();
    private final WorkerThreads workers = new WorkerThreads();
    private final Semaphore capacity = new Semaphore(8);

    public JobEngine(JdbcTemplate jdbc, PlatformTransactionManager manager,
            JobHandlerRegistry handlers, ObjectMapper json) {
        this.jdbc = jdbc;
        this.transactions = new TransactionTemplate(manager);
        this.handlers = handlers;
        this.json = json;
    }

    @EventListener(ApplicationReadyEvent.class)
    public void onReady() {
        poll();
    }

    @Scheduled(fixedDelayString = "${job.poll-ms:1000}")
    public void poll() {
        try {
            initializeMissingPlans();
            recoverExpired();
            List<Long> due = jdbc.query(
                    "SELECT job_id FROM sys_job WHERE tenant_id='000000' AND is_deleted=0 "
                            + "AND status='0' AND handler_key IS NOT NULL AND next_fire_time <= ? "
                            + "ORDER BY next_fire_time, job_id LIMIT 32",
                    (rs, row) -> rs.getLong(1), JobSchedule.utc(Instant.now()));
            for (Long jobId : due) {
                if (!capacity.tryAcquire()) {
                    break; // Leave next_fire_time intact; a later poll can claim it.
                }
                try {
                    Claim claim = transactions.execute(tx -> claimDue(jobId));
                    if (claim != null) {
                        dispatch(claim);
                    } else {
                        capacity.release();
                    }
                } catch (Exception e) {
                    capacity.release();
                    log.error("Could not claim job {}", jobId, e);
                }
            }
        } catch (Exception e) {
            log.error("Job poll failed", e);
        }
    }

    /** Manual firing has an independent controller permission and does not move the Cron plan. */
    public Long runNow(Long jobId) {
        if (!capacity.tryAcquire()) {
            throw new ServiceException("本机任务执行容量已满，请稍后重试");
        }
        try {
            Claim claim = transactions.execute(tx -> {
                JobRow job = lockedJob(jobId);
                if (job == null) {
                    throw new ServiceException("任务不存在");
                }
                if (!"0".equals(job.status)) {
                    throw new ServiceException("任务已暂停，不能手动执行");
                }
                handlers.require(job.handlerKey);
                if ("1".equals(job.concurrent) && hasActiveFire(jobId)) {
                    throw new ServiceException("任务正在执行，禁止并发触发");
                }
                return insertClaim(job, "MANUAL", null, "M:" + UUID.randomUUID());
            });
            dispatch(claim);
            return claim.fireId;
        } catch (RuntimeException e) {
            capacity.release();
            throw e;
        }
    }

    public List<String> registeredHandlers() {
        return handlers.keys().stream().sorted().toList();
    }

    public List<FireView> recentFires(Long jobId) {
        int visible = jdbc.queryForObject(
                "SELECT COUNT(*) FROM sys_job WHERE job_id=? AND tenant_id='000000' AND is_deleted=0",
                Integer.class, jobId);
        if (visible == 0) {
            throw new ServiceException("任务不存在");
        }
        return jdbc.query("SELECT fire_id, trigger_type, scheduled_fire_time, status, attempt_count, "
                        + "started_at, completed_at, result_summary FROM sys_job_fire "
                        + "WHERE job_id=? ORDER BY fire_id DESC LIMIT 50",
                (rs, row) -> new FireView(rs.getLong(1), rs.getString(2),
                        rs.getObject(3, LocalDateTime.class), rs.getString(4), rs.getInt(5),
                        rs.getObject(6, LocalDateTime.class), rs.getObject(7, LocalDateTime.class),
                        rs.getString(8)), jobId);
    }

    private void initializeMissingPlans() {
        List<Long> missing = jdbc.query("SELECT job_id FROM sys_job WHERE tenant_id='000000' AND is_deleted=0 "
                        + "AND status='0' AND next_fire_time IS NULL LIMIT 32",
                (rs, row) -> rs.getLong(1));
        for (Long jobId : missing) {
            transactions.executeWithoutResult(tx -> {
                JobRow job = lockedJob(jobId);
                if (job == null || !"0".equals(job.status) || job.nextFireTime != null) {
                    return;
                }
                if (job.handlerKey == null || !handlers.keys().contains(job.handlerKey)) {
                    jdbc.update("UPDATE sys_job SET status='1', revision=revision+1 WHERE job_id=?", jobId);
                    return;
                }
                LocalDateTime next = JobSchedule.nextUtc(job.cron, job.timeZone, Instant.now());
                if (next == null) {
                    jdbc.update("UPDATE sys_job SET status='1', revision=revision+1 WHERE job_id=?", jobId);
                } else {
                    jdbc.update("UPDATE sys_job SET next_fire_time=? WHERE job_id=?", next, jobId);
                }
            });
        }
    }

    private Claim claimDue(Long jobId) {
        JobRow job = lockedJob(jobId);
        Instant now = Instant.now();
        LocalDateTime nowUtc = JobSchedule.utc(now);
        if (job == null || !"0".equals(job.status) || job.handlerKey == null
                || job.nextFireTime == null || job.nextFireTime.isAfter(nowUtc)) {
            return null;
        }
        if (!handlers.keys().contains(job.handlerKey)) {
            jdbc.update("UPDATE sys_job SET status='1', next_fire_time=NULL, revision=revision+1 WHERE job_id=?",
                    jobId);
            return null;
        }
        LocalDateTime due = job.nextFireTime;
        LocalDateTime next = JobSchedule.nextUtc(job.cron, job.timeZone, now);
        String key = "S:" + jobId + ":" + due.toInstant(ZoneOffset.UTC).toEpochMilli();
        if (next == null) {
            recordSkipped(job, due, key, "no future occurrence");
            jdbc.update("UPDATE sys_job SET status='1', next_fire_time=NULL, revision=revision+1 WHERE job_id=?",
                    jobId);
            return null;
        }
        jdbc.update("UPDATE sys_job SET next_fire_time=? WHERE job_id=?", next, jobId);
        if (alreadyRecorded(key)) {
            return null;
        }
        if ("3".equals(job.misfirePolicy)
                && Duration.between(due.toInstant(ZoneOffset.UTC), now).compareTo(MISFIRE_GRACE) > 0) {
            recordSkipped(job, due, key, "misfire skipped");
            return null;
        }
        if ("1".equals(job.concurrent) && hasActiveFire(jobId)) {
            recordSkipped(job, due, key, "overlap skipped");
            return null;
        }
        return insertClaim(job, "SCHEDULED", due, key);
    }

    private JobRow lockedJob(Long jobId) {
        List<JobRow> rows = jdbc.query("SELECT job_id, revision, handler_key, handler_params, cron_expression, "
                        + "time_zone, misfire_policy, concurrent, status, next_fire_time "
                        + "FROM sys_job WHERE job_id=? AND tenant_id='000000' AND is_deleted=0 FOR UPDATE",
                (rs, row) -> new JobRow(rs.getLong(1), rs.getLong(2), rs.getString(3), rs.getString(4),
                        rs.getString(5), rs.getString(6), rs.getString(7), rs.getString(8),
                        rs.getString(9), rs.getObject(10, LocalDateTime.class)), jobId);
        return rows.isEmpty() ? null : rows.get(0);
    }

    private boolean hasActiveFire(Long jobId) {
        return jdbc.queryForObject("SELECT COUNT(*) FROM sys_job_fire WHERE job_id=? AND status='CLAIMED'",
                Integer.class, jobId) > 0;
    }

    private boolean alreadyRecorded(String key) {
        return jdbc.queryForObject("SELECT COUNT(*) FROM sys_job_fire WHERE fire_key=?", Integer.class, key) > 0;
    }

    private void recordSkipped(JobRow job, LocalDateTime due, String key, String reason) {
        JobSnapshot snapshot = snapshot(job.jobId);
        jdbc.update("INSERT INTO sys_job_fire (job_id, job_revision, fire_key, trigger_type, "
                        + "scheduled_fire_time, status, attempt_count, completed_at, result_summary, "
                        + "trace_id, job_name_snapshot, job_group_snapshot) "
                        + "VALUES (?, ?, ?, 'SCHEDULED', ?, 'SKIPPED', 0, ?, ?, ?, ?, ?)",
                job.jobId, job.revision, key, due, JobSchedule.utc(Instant.now()), reason,
                UUID.randomUUID().toString(), snapshot.name, snapshot.group);
    }

    private Claim insertClaim(JobRow job, String trigger, LocalDateTime due, String key) {
        String token = UUID.randomUUID().toString();
        Instant now = Instant.now();
        JobSnapshot snapshot = snapshot(job.jobId);
        jdbc.update("INSERT INTO sys_job_fire (job_id, job_revision, fire_key, trigger_type, "
                        + "scheduled_fire_time, status, attempt_count, claim_token, claimed_by, lease_until, "
                        + "started_at, trace_id, job_name_snapshot, job_group_snapshot) "
                        + "VALUES (?, ?, ?, ?, ?, 'CLAIMED', 1, ?, ?, ?, ?, ?, ?, ?)",
                job.jobId, job.revision, key, trigger, due, token, nodeId,
                JobSchedule.utc(now.plus(LEASE)), JobSchedule.utc(now), UUID.randomUUID().toString(),
                snapshot.name, snapshot.group);
        Long fireId = jdbc.queryForObject("SELECT fire_id FROM sys_job_fire WHERE fire_key=?", Long.class, key);
        return new Claim(fireId, job, token, "SCHEDULED".equals(trigger));
    }

    private JobSnapshot snapshot(Long jobId) {
        return jdbc.queryForObject("SELECT job_name, job_group FROM sys_job WHERE job_id=?",
                (rs, row) -> new JobSnapshot(rs.getString(1), rs.getString(2)), jobId);
    }

    private void recoverExpired() {
        LocalDateTime now = JobSchedule.utc(Instant.now());
        List<Long> expired = jdbc.query("SELECT fire_id FROM sys_job_fire WHERE status='CLAIMED' "
                        + "AND lease_until < ? ORDER BY lease_until LIMIT 32",
                (rs, row) -> rs.getLong(1), now);
        for (Long fireId : expired) {
            if (!capacity.tryAcquire()) {
                break;
            }
            String token = UUID.randomUUID().toString();
            try {
                int reclaimed = jdbc.update("UPDATE sys_job_fire SET claim_token=?, claimed_by=?, "
                                + "lease_until=?, attempt_count=attempt_count+1 WHERE fire_id=? "
                                + "AND status='CLAIMED' AND lease_until < ? AND attempt_count < 2",
                        token, nodeId, JobSchedule.utc(Instant.now().plus(LEASE)), fireId, now);
                if (reclaimed == 1) {
                    List<Claim> claims = jdbc.query("SELECT f.fire_id, f.job_revision, f.trigger_type, "
                                    + "j.job_id, j.revision, j.handler_key, j.handler_params, j.cron_expression, "
                                    + "j.time_zone, j.misfire_policy, j.concurrent, j.status, j.next_fire_time "
                                    + "FROM sys_job_fire f JOIN sys_job j ON f.job_id=j.job_id "
                                    + "WHERE f.fire_id=? AND j.tenant_id='000000' AND j.is_deleted=0",
                            (rs, row) -> {
                                JobRow job = new JobRow(rs.getLong(4), rs.getLong(5), rs.getString(6),
                                        rs.getString(7), rs.getString(8), rs.getString(9), rs.getString(10),
                                        rs.getString(11), rs.getString(12), rs.getObject(13, LocalDateTime.class));
                                if (rs.getLong(2) != job.revision) {
                                    return null;
                                }
                                return new Claim(rs.getLong(1), job, token, "SCHEDULED".equals(rs.getString(3)));
                            }, fireId);
                    if (claims.isEmpty() || claims.get(0) == null) {
                        finish(fireId, token, "CANCELLED", "job changed or deleted");
                        capacity.release();
                    } else {
                        dispatch(claims.get(0));
                    }
                } else {
                    jdbc.update("UPDATE sys_job_fire SET status='FAILED', completed_at=?, "
                                + "result_summary='lease expired after retry', claim_token=NULL, lease_until=NULL "
                                + "WHERE fire_id=? AND status='CLAIMED' AND lease_until < ? AND attempt_count >= 2",
                            now, fireId, now);
                    capacity.release();
                }
            } catch (RuntimeException e) {
                capacity.release();
                throw e;
            }
        }
    }

    private void dispatch(Claim claim) {
        // Renew while queued as well as while running: the worker pool is bounded.
        ScheduledFuture<?> heartbeat = workers.heartbeats.scheduleAtFixedRate(() -> {
            try {
                jdbc.update("UPDATE sys_job_fire SET lease_until=? WHERE fire_id=? "
                                + "AND claim_token=? AND status='CLAIMED'",
                        JobSchedule.utc(Instant.now().plus(LEASE)), claim.fireId, claim.token);
            } catch (Exception e) {
                log.warn("Job lease heartbeat failed for fire {}", claim.fireId, e);
            }
        }, 5, 5, TimeUnit.SECONDS);
        try {
            workers.pool.execute(() -> runClaim(claim, heartbeat));
        } catch (RuntimeException e) {
            heartbeat.cancel(false);
            throw e;
        }
    }

    private void runClaim(Claim claim, ScheduledFuture<?> heartbeat) {
        try {
            int current = jdbc.queryForObject("SELECT COUNT(*) FROM sys_job WHERE job_id=? AND revision=? "
                            + "AND tenant_id='000000' AND is_deleted=0 AND status='0'",
                    Integer.class, claim.job.jobId, claim.job.revision);
            if (current == 0) {
                finish(claim.fireId, claim.token, "CANCELLED", "job changed or deleted");
                return;
            }
            handlers.require(claim.job.handlerKey).execute(json.readTree(claim.job.handlerParams));
            finish(claim.fireId, claim.token, "SUCCEEDED", "completed");
        } catch (Exception e) {
            log.error("Job fire {} failed with {}", claim.fireId, e.getClass().getSimpleName());
            finish(claim.fireId, claim.token, "FAILED", "handler failed: " + e.getClass().getSimpleName());
        } finally {
            heartbeat.cancel(false);
            capacity.release();
        }
    }

    private void finish(Long fireId, String token, String status, String summary) {
        jdbc.update("UPDATE sys_job_fire SET status=?, result_summary=?, completed_at=?, "
                        + "claim_token=NULL, lease_until=NULL WHERE fire_id=? AND claim_token=? AND status='CLAIMED'",
                status, summary, JobSchedule.utc(Instant.now()), fireId, token);
    }

    @PreDestroy
    public void close() {
        workers.pool.shutdownNow();
        workers.heartbeats.shutdownNow();
    }

    public record FireView(Long fireId, String triggerType, LocalDateTime scheduledFireTime,
            String status, int attemptCount, LocalDateTime startedAt,
            LocalDateTime completedAt, String resultSummary) {
    }

    private record JobRow(Long jobId, long revision, String handlerKey, String handlerParams,
            String cron, String timeZone, String misfirePolicy, String concurrent,
            String status, LocalDateTime nextFireTime) {
    }

    private record Claim(Long fireId, JobRow job, String token, boolean scheduled) {
    }

    private record JobSnapshot(String name, String group) {
    }

    private static final class WorkerThreads {
        private final java.util.concurrent.ExecutorService pool = new ThreadPoolExecutor(
                4, 4, 0L, TimeUnit.MILLISECONDS, new ArrayBlockingQueue<>(4), task -> {
            Thread thread = new Thread(task, "job-worker");
            thread.setDaemon(true);
            return thread;
        });
        private final ScheduledExecutorService heartbeats = Executors.newSingleThreadScheduledExecutor(task -> {
            Thread thread = new Thread(task, "job-heartbeat");
            thread.setDaemon(true);
            return thread;
        });
    }
}
