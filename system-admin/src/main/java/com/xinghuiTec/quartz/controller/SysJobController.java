package com.xinghuiTec.quartz.controller;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import com.xinghuiTec.quartz.domain.SysJob;
import com.xinghuiTec.quartz.domain.JobChangeRequest;
import com.xinghuiTec.quartz.service.SysJobService;
import com.xinghuiTec.quartz.service.JobEngine;
import com.xinghuiTec.utils.Result;
import com.xinghuiTec.constants.TenantConstants;
import jakarta.annotation.Resource;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.*;
import org.springframework.util.StringUtils;
import org.springframework.validation.annotation.Validated;

import java.util.List;
import jakarta.validation.constraints.NotBlank;

/**
 * 定时任务调度表(SysJob)表控制层
 *
 * @author beidoa23
 * @since 2026-01-25
 */
@RestController
@RequestMapping("/monitor/job")
public class SysJobController {
    @Resource
    private SysJobService sysJobService;
    @Resource
    private JobEngine jobEngine;

    public record StatusChangeRequest(@NotBlank String status) {
    }

    @GetMapping("/handlers")
    @PreAuthorize("@platformAccess.isPlatformIdentity() and hasAuthority('monitor:job:list')")
    public Result<List<String>> handlers() {
        return Result.ok(jobEngine.registeredHandlers());
    }

    @GetMapping("/{jobId}/fires")
    @PreAuthorize("@platformAccess.isPlatformIdentity() and hasAuthority('monitor:job:query')")
    public Result<List<JobEngine.FireView>> fires(@PathVariable Long jobId) {
        return Result.ok(jobEngine.recentFires(jobId));
    }

    @PostMapping("/{jobId}/run")
    @PreAuthorize("@platformAccess.isPlatformIdentity() and hasAuthority('monitor:job:run')")
    public Result<Long> run(@PathVariable Long jobId) {
        return Result.ok(jobEngine.runNow(jobId));
    }

    @PutMapping("/{jobId}/status")
    @PreAuthorize("@platformAccess.isPlatformIdentity() and hasAuthority('monitor:job:changeStatus')")
    public Result<Void> changeStatus(@PathVariable Long jobId,
            @Validated @RequestBody StatusChangeRequest request) {
        sysJobService.changeStatusManaged(jobId, request.status());
        return Result.ok();
    }

    /**
     * 查询定时任务列表
     */
    @GetMapping("/list")
    @PreAuthorize("@platformAccess.isPlatformIdentity() and hasAuthority('monitor:job:list')")
    public Result<Page<SysJob>> list(SysJob job,
            @RequestParam(defaultValue = "1") Integer pageNum,
            @RequestParam(defaultValue = "10") Integer pageSize) {
        LambdaQueryWrapper<SysJob> wrapper = new LambdaQueryWrapper<>();
        wrapper.like(StringUtils.hasText(job.getJobName()), SysJob::getJobName, job.getJobName())
                .eq(SysJob::getTenantId, TenantConstants.DEFAULT_TENANT_ID)
                .eq(StringUtils.hasText(job.getJobGroup()), SysJob::getJobGroup, job.getJobGroup())
                .eq(StringUtils.hasText(job.getStatus()), SysJob::getStatus, job.getStatus());

        Page<SysJob> page = new Page<>(pageNum, pageSize);
        return Result.ok(sysJobService.page(page, wrapper));
    }

    /**
     * 获取定时任务详细信息
     */
    @GetMapping(value = "/{jobId}")
    @PreAuthorize("@platformAccess.isPlatformIdentity() and hasAuthority('monitor:job:query')")
    public Result<SysJob> getInfo(@PathVariable("jobId") Long jobId) {
        return Result.ok(sysJobService.getManaged(jobId));
    }

    /**
     * 新增定时任务
     */
    @PostMapping
    @PreAuthorize("@platformAccess.isPlatformIdentity() and hasAuthority('monitor:job:add')")
    public Result<Long> add(@Validated @RequestBody JobChangeRequest job) {
        return Result.ok(sysJobService.addManaged(job));
    }

    /**
     * 修改定时任务
     */
    @PutMapping
    @PreAuthorize("@platformAccess.isPlatformIdentity() and hasAuthority('monitor:job:edit')")
    public Result<Void> edit(@Validated @RequestBody JobChangeRequest job) {
        sysJobService.updateManaged(job);
        return Result.ok();
    }

    /**
     * 删除定时任务
     */
    @DeleteMapping("/{jobIds}")
    @PreAuthorize("@platformAccess.isPlatformIdentity() and hasAuthority('monitor:job:remove')")
    public Result<Void> remove(@PathVariable List<Long> jobIds) {
        sysJobService.removeManaged(jobIds);
        return Result.ok();
    }
}
