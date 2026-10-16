package com.xinghuiTec.quartz.service.impl;

import com.baomidou.mybatisplus.extension.service.impl.ServiceImpl;
import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.xinghuiTec.constants.TenantConstants;
import com.xinghuiTec.exception.ServiceException;
import com.xinghuiTec.quartz.domain.JobChangeRequest;
import com.xinghuiTec.quartz.domain.SysJob;
import com.xinghuiTec.quartz.handler.JobHandlerRegistry;
import com.xinghuiTec.quartz.mapper.SysJobMapper;
import com.xinghuiTec.quartz.service.SysJobService;
import com.xinghuiTec.quartz.service.JobSchedule;
import org.springframework.scheduling.support.CronExpression;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.nio.charset.StandardCharsets;
import java.time.DateTimeException;
import java.time.ZoneId;
import java.time.ZonedDateTime;
import java.time.Instant;
import java.util.Set;
import java.util.List;
import java.util.Objects;

/**
 * 定时任务调度表(SysJob)表服务实现类
 */
@Service
public class SysJobServiceImpl extends ServiceImpl<SysJobMapper, SysJob> implements SysJobService {
    private static final Set<String> MISFIRE = Set.of("2", "3");
    private static final Set<String> BINARY = Set.of("0", "1");
    private final JobHandlerRegistry handlers;
    private final ObjectMapper json;

    public SysJobServiceImpl(JobHandlerRegistry handlers, ObjectMapper json) {
        this.handlers = handlers;
        this.json = json;
    }

    @Override
    @Transactional
    public Long addManaged(JobChangeRequest request) {
        if (request == null || request.jobId() != null) {
            throw new ServiceException("jobId: 新增任务不得指定 ID");
        }
        SysJob job = validated(request);
        job.setTenantId(TenantConstants.DEFAULT_TENANT_ID);
        job.setInvokeTarget(null);
        job.setRevision(0L);
        job.setNextFireTime(nextFor(job));
        if (!save(job)) {
            throw new ServiceException("新增任务失败");
        }
        return job.getJobId();
    }

    @Override
    @Transactional
    public void updateManaged(JobChangeRequest request) {
        if (request == null || request.jobId() == null || request.jobId() <= 0) {
            throw new ServiceException("jobId: 修改任务须指定有效 ID");
        }
        SysJob existing = getManagedForUpdate(request.jobId());
        if (!Objects.equals(existing.getStatus(), request.status())) {
            throw new ServiceException("status: 请使用独立状态修改接口");
        }
        SysJob job = validated(request);
        job.setJobId(request.jobId());
        job.setTenantId(TenantConstants.DEFAULT_TENANT_ID);
        job.setRevision(existing.getRevision() == null ? 1L : existing.getRevision() + 1);
        job.setNextFireTime(nextFor(job));
        // Explicit edit promotes a legacy row only after handler validation; retain invoke_target for audit.
        if (!updateById(job)) {
            throw new ServiceException("修改任务失败");
        }
    }

    @Override
    public SysJob getManaged(Long jobId) {
        SysJob job = getById(jobId);
        if (job == null || !TenantConstants.DEFAULT_TENANT_ID.equals(job.getTenantId())) {
            throw new ServiceException("任务不存在");
        }
        return job;
    }

    /** Serialize edits and status changes with each other and with scheduler claims. */
    public SysJob getManagedForUpdate(Long jobId) {
        SysJob job = baseMapper.selectOne(new LambdaQueryWrapper<SysJob>()
                .eq(SysJob::getJobId, jobId)
                .eq(SysJob::getTenantId, TenantConstants.DEFAULT_TENANT_ID)
                .last("FOR UPDATE"));
        if (job == null) {
            throw new ServiceException("任务不存在");
        }
        return job;
    }

    @Override
    @Transactional
    public void removeManaged(List<Long> jobIds) {
        if (jobIds == null || jobIds.isEmpty()) {
            throw new ServiceException("jobIds: 不能为空");
        }
        jobIds.forEach(this::getManaged);
        if (!removeBatchByIds(jobIds)) {
            throw new ServiceException("删除任务失败");
        }
    }

    @Override
    @Transactional
    public void changeStatusManaged(Long jobId, String status) {
        if (!BINARY.contains(status)) {
            throw new ServiceException("status: 仅支持 0 或 1");
        }
        SysJob existing = getManagedForUpdate(jobId);
        if (status.equals(existing.getStatus())) {
            return;
        }
        if ("0".equals(status)) {
            handlers.require(existing.getHandlerKey());
        }
        SysJob change = new SysJob();
        change.setJobId(jobId);
        change.setStatus(status);
        change.setRevision(existing.getRevision() == null ? 1L : existing.getRevision() + 1);
        change.setNextFireTime("0".equals(status)
                ? JobSchedule.nextUtc(existing.getCronExpression(), existing.getTimeZone(), Instant.now()) : null);
        if ("0".equals(status) && change.getNextFireTime() == null) {
            throw new ServiceException("cronExpression: 无下一次执行时间");
        }
        if (!updateById(change)) {
            throw new ServiceException("修改任务状态失败");
        }
    }

    private static java.time.LocalDateTime nextFor(SysJob job) {
        if (!"0".equals(job.getStatus())) {
            return null;
        }
        var next = JobSchedule.nextUtc(job.getCronExpression(), job.getTimeZone(), Instant.now());
        if (next == null) {
            throw new ServiceException("cronExpression: 无下一次执行时间");
        }
        return next;
    }

    private SysJob validated(JobChangeRequest request) {
        if (request.jobName() == null || request.jobName().isBlank() || request.jobName().length() > 64
                || request.jobGroup() == null || request.jobGroup().isBlank() || request.jobGroup().length() > 64) {
            throw new ServiceException("jobName/jobGroup: 必填且最多 64 字符");
        }
        if (!MISFIRE.contains(request.misfirePolicy())) {
            throw new ServiceException("misfirePolicy: 仅支持 2(补执行一次)或 3(跳过)");
        }
        if (!BINARY.contains(request.concurrent()) || !BINARY.contains(request.status())) {
            throw new ServiceException("concurrent/status: 仅支持 0 或 1");
        }
        ZoneId zone;
        try {
            if (request.timeZone() == null || request.timeZone().length() > 64
                    || !ZoneId.getAvailableZoneIds().contains(request.timeZone())) {
                throw new DateTimeException("unknown zone");
            }
            zone = ZoneId.of(request.timeZone());
        } catch (DateTimeException e) {
            throw new ServiceException("timeZone: 需使用 IANA 时区 ID");
        }
        try {
            if (request.cronExpression() == null || request.cronExpression().length() > 255) {
                throw new IllegalArgumentException("missing or too long");
            }
            CronExpression cron = CronExpression.parse(request.cronExpression());
            if (cron.next(ZonedDateTime.now(zone)) == null) {
                throw new IllegalArgumentException("no future fire");
            }
        } catch (IllegalArgumentException | NullPointerException e) {
            throw new ServiceException("cronExpression: 无效的六段 Cron 表达式");
        }
        String params = request.handlerParams() == null || request.handlerParams().isBlank()
                ? "{}" : request.handlerParams();
        if (params.getBytes(StandardCharsets.UTF_8).length > 4096) {
            throw new ServiceException("handlerParams: 最多 4096 字节");
        }
        JsonNode parsed;
        try {
            parsed = json.readTree(params);
        } catch (JsonProcessingException e) {
            throw new ServiceException("handlerParams: 必须是 JSON 对象");
        }
        if (parsed == null || !parsed.isObject()) {
            throw new ServiceException("handlerParams: 必须是 JSON 对象");
        }
        handlers.validate(request.handlerKey(), parsed);
        if (request.remark() != null && request.remark().length() > 500) {
            throw new ServiceException("remark: 最多 500 字符");
        }
        SysJob job = new SysJob();
        job.setJobName(request.jobName().trim());
        job.setJobGroup(request.jobGroup().trim());
        job.setHandlerKey(request.handlerKey());
        job.setHandlerParams(params);
        job.setCronExpression(request.cronExpression().trim());
        job.setTimeZone(zone.getId());
        job.setMisfirePolicy(request.misfirePolicy());
        job.setConcurrent(request.concurrent());
        job.setStatus(request.status());
        job.setRemark(request.remark());
        return job;
    }
}
