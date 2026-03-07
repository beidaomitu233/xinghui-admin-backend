package com.xinghuiTec.quartz.controller;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import com.xinghuiTec.quartz.domain.SysJob;
import com.xinghuiTec.quartz.service.SysJobService;
import com.xinghuiTec.utils.Result;
import jakarta.annotation.Resource;
import org.springframework.web.bind.annotation.*;
import org.springframework.util.StringUtils;

import java.util.List;

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

    /**
     * 查询定时任务列表
     */
    @GetMapping("/list")
    public Result<Page<SysJob>> list(SysJob job,
            @RequestParam(defaultValue = "1") Integer pageNum,
            @RequestParam(defaultValue = "10") Integer pageSize) {
        LambdaQueryWrapper<SysJob> wrapper = new LambdaQueryWrapper<>();
        wrapper.like(StringUtils.hasText(job.getJobName()), SysJob::getJobName, job.getJobName())
                .eq(StringUtils.hasText(job.getJobGroup()), SysJob::getJobGroup, job.getJobGroup())
                .eq(StringUtils.hasText(job.getStatus()), SysJob::getStatus, job.getStatus());

        Page<SysJob> page = new Page<>(pageNum, pageSize);
        return Result.ok(sysJobService.page(page, wrapper));
    }

    /**
     * 获取定时任务详细信息
     */
    @GetMapping(value = "/{jobId}")
    public Result<SysJob> getInfo(@PathVariable("jobId") Long jobId) {
        return Result.ok(sysJobService.getById(jobId));
    }

    /**
     * 新增定时任务
     */
    @PostMapping
    public Result<Void> add(@RequestBody SysJob job) {
        return sysJobService.save(job) ? Result.ok() : Result.fail(500, "新增任务失败");
    }

    /**
     * 修改定时任务
     */
    @PutMapping
    public Result<Void> edit(@RequestBody SysJob job) {
        return sysJobService.updateById(job) ? Result.ok() : Result.fail(500, "修改任务失败");
    }

    /**
     * 删除定时任务
     */
    @DeleteMapping("/{jobIds}")
    public Result<Void> remove(@PathVariable List<Long> jobIds) {
        return sysJobService.removeBatchByIds(jobIds) ? Result.ok() : Result.fail(500, "删除任务失败");
    }
}
