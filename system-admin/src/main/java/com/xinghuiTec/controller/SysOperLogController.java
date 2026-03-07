package com.xinghuiTec.controller;

import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import com.xinghuiTec.domain.dto.SysOperLogQueryDTO;
import com.xinghuiTec.domain.entity.SysOperLog;
import com.xinghuiTec.service.SysOperLogService;
import com.xinghuiTec.utils.Result;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.validation.annotation.Validated;
import org.springframework.web.bind.annotation.*;
import jakarta.annotation.Resource;

import java.util.List;

/**
 * 操作日志记录(SysOperLog)表控制层
 *
 * @author beidoa23
 * @since 2025-12-25 19:33:19
 */

@RestController
@RequestMapping("/monitor/operlog")
public class SysOperLogController {
    @Resource
    private SysOperLogService sysOperLogService;

    /**
     * 查询操作日志列表
     * 支持分页和多条件查询
     * 
     * @param queryDTO 查询条件DTO
     * @return Result<Page<SysOperLog>> 分页结果
     */
    @GetMapping("/list")
    @PreAuthorize("hasAuthority('monitor:operlog:list')")
    public Result<Page<SysOperLog>> getOperLogList(@Validated SysOperLogQueryDTO queryDTO) {
        Page<SysOperLog> page = sysOperLogService.getOperLogList(queryDTO);
        return Result.ok(page);
    }

    /**
     * 批量删除操作日志
     * 
     * @param operIds 日志ID列表
     * @return Result<Void>
     */
    @PostMapping("/remove")
    @PreAuthorize("hasAuthority('monitor:operlog:remove')")
    public Result<Void> deleteOperLog(@RequestBody List<Long> operIds) {
        sysOperLogService.deleteOperLog(operIds);
        return Result.ok();
    }

    /**
     * 清空操作日志
     * 
     * @return Result<Void>
     */
    @PostMapping("/clean")
    @PreAuthorize("hasAuthority('monitor:operlog:remove')")
    public Result<Void> cleanOperLog() {
        sysOperLogService.cleanOperLog();
        return Result.ok();
    }
}
