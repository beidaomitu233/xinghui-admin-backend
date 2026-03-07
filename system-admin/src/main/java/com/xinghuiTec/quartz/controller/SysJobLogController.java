package com.xinghuiTec.quartz.controller;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import com.xinghuiTec.quartz.domain.SysJobLog;
import com.xinghuiTec.quartz.service.SysJobLogService;
import com.xinghuiTec.utils.Result;
import jakarta.annotation.Resource;
import org.springframework.web.bind.annotation.*;
import org.springframework.util.StringUtils;

import java.util.List;

/**
 * 定时任务调度日志表(SysJobLog)表控制层
 *
 * @author beidoa23
 * @since 2026-01-25
 */
@RestController
@RequestMapping("/monitor/jobLog")
public class SysJobLogController {
    @Resource
    private SysJobLogService sysJobLogService;

    /**
     * 查询定时任务日志列表
     */
    @GetMapping("/list")
    public Result<Page<SysJobLog>> list(SysJobLog jobLog,
            @RequestParam(defaultValue = "1") Integer pageNum,
            @RequestParam(defaultValue = "10") Integer pageSize) {
        LambdaQueryWrapper<SysJobLog> wrapper = new LambdaQueryWrapper<>();
        wrapper.like(StringUtils.hasText(jobLog.getJobName()), SysJobLog::getJobName, jobLog.getJobName())
                .eq(StringUtils.hasText(jobLog.getJobGroup()), SysJobLog::getJobGroup, jobLog.getJobGroup())
                .eq(StringUtils.hasText(jobLog.getStatus()), SysJobLog::getStatus, jobLog.getStatus())
                .orderByDesc(SysJobLog::getCreateTime);

        Page<SysJobLog> page = new Page<>(pageNum, pageSize);
        return Result.ok(sysJobLogService.page(page, wrapper));
    }

    /**
     * 删除定时任务日志
     */
    @DeleteMapping("/{jobLogIds}")
    public Result<Void> remove(@PathVariable List<Long> jobLogIds) {
        return sysJobLogService.removeBatchByIds(jobLogIds) ? Result.ok() : Result.fail(500, "删除日志失败");
    }

    /**
     * 清空定时任务日志
     */
    @DeleteMapping("/clean")
    public Result<Void> clean() {
        sysJobLogService.remove(new LambdaQueryWrapper<>()); // 清空所有
        return Result.ok();
    }
}
