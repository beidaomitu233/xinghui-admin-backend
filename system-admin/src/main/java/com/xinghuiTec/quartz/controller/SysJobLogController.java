package com.xinghuiTec.quartz.controller;

import com.xinghuiTec.quartz.service.JobFireLogService;
import com.xinghuiTec.utils.Result;
import jakarta.validation.constraints.Pattern;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.validation.annotation.Validated;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;

/** Safe JOB-02 fire ledger projection. Legacy sys_job_log is never serialized. */
@Validated
@RestController
@RequestMapping("/monitor/jobLog")
public class SysJobLogController {
    private final JobFireLogService logs;

    public SysJobLogController(JobFireLogService logs) {
        this.logs = logs;
    }

    @GetMapping("/list")
    @PreAuthorize("@platformAccess.isPlatformIdentity() and hasAuthority('monitor:jobLog:list')")
    public Result<JobFireLogService.FirePage> list(
            @RequestParam(defaultValue = "1") Integer pageNum,
            @RequestParam(defaultValue = "10") Integer pageSize,
            @RequestParam(required = false) Long jobId,
            @RequestParam(required = false) String jobName,
            @RequestParam(required = false) String status,
            @RequestParam(required = false) String triggerType,
            @RequestParam(defaultValue = "fireId")
            @Pattern(regexp = "^(fireId|jobName|jobGroup|triggerType|scheduledFireTime|status|attemptCount|startedAt|completedAt)$", message = "不支持的执行记录排序字段")
            String orderBy,
            @RequestParam(defaultValue = "desc")
            @Pattern(regexp = "^(asc|desc)$", message = "排序方向只能是 asc 或 desc")
            String order) {
        return Result.ok(logs.list(pageNum, pageSize, jobId, jobName, status, triggerType, orderBy, order));
    }

    @GetMapping("/{fireId}")
    @PreAuthorize("@platformAccess.isPlatformIdentity() and hasAuthority('monitor:jobLog:query')")
    public Result<JobFireLogService.FireLog> detail(@PathVariable Long fireId) {
        return Result.ok(logs.detail(fireId));
    }

    @DeleteMapping("/clean")
    @PreAuthorize("@platformAccess.isPlatformIdentity() and hasAuthority('monitor:jobLog:clean')")
    public Result<Integer> clean() {
        return Result.ok(logs.clean());
    }

    @DeleteMapping("/{fireIds}")
    @PreAuthorize("@platformAccess.isPlatformIdentity() and hasAuthority('monitor:jobLog:remove')")
    public Result<Integer> remove(@PathVariable List<Long> fireIds) {
        return Result.ok(logs.remove(fireIds));
    }
}
