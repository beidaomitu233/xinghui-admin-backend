package com.xinghuiTec.controller;

import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import com.xinghuiTec.annotation.Log;
import com.xinghuiTec.domain.dto.SysNoticeAddDTO;
import com.xinghuiTec.domain.dto.SysNoticeQueryDTO;
import com.xinghuiTec.domain.entity.SysNotice;
import com.xinghuiTec.emues.BusinessType;
import com.xinghuiTec.service.SysNoticeService;
import com.xinghuiTec.utils.Result;
import jakarta.annotation.Resource;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.validation.annotation.Validated;
import org.springframework.web.bind.annotation.*;

import java.util.List;

/**
 * 通知公告管理控制器
 * 
 * @author beidoa23
 * @since 2026-01-24
 */
@RestController
@RequestMapping("/system/notice")
public class SysNoticeController {

    @Resource
    private SysNoticeService sysNoticeService;

    /**
     * 查询公告列表
     */
    @GetMapping("/list")
    @PreAuthorize("hasAuthority('system:notice:list')")
    public Result<Page<SysNotice>> getNoticeList(@Validated SysNoticeQueryDTO queryDTO) {
        Page<SysNotice> page = sysNoticeService.getNoticeList(queryDTO);
        return Result.ok(page);
    }

    /**
     * 查询公告详情
     */
    @GetMapping("/{noticeId}")
    @PreAuthorize("hasAuthority('system:notice:query')")
    public Result<SysNotice> getNoticeById(@PathVariable Long noticeId) {
        SysNotice notice = sysNoticeService.getNoticeById(noticeId);
        return Result.ok(notice);
    }

    /**
     * 新增公告
     */
    @PostMapping("/add")
    @PreAuthorize("hasAuthority('system:notice:add')")
    @Log(title = "通知公告", businessType = BusinessType.INSERT)
    public Result<Long> addNotice(@Validated @RequestBody SysNoticeAddDTO addDTO) {
        Long noticeId = sysNoticeService.addNotice(addDTO);
        return Result.ok(noticeId);
    }

    /**
     * 修改公告
     */
    @PostMapping("/edit")
    @PreAuthorize("hasAuthority('system:notice:edit')")
    @Log(title = "通知公告", businessType = BusinessType.UPDATE)
    public Result<Void> updateNotice(@Validated @RequestBody SysNoticeAddDTO addDTO) {
        sysNoticeService.updateNotice(addDTO);
        return Result.ok();
    }

    /**
     * 删除公告
     */
    @PostMapping("/remove/{noticeId}")
    @PreAuthorize("hasAuthority('system:notice:remove')")
    @Log(title = "通知公告", businessType = BusinessType.DELETE)
    public Result<Void> deleteNotice(@PathVariable Long noticeId) {
        sysNoticeService.deleteNotice(noticeId);
        return Result.ok();
    }

    /**
     * 批量删除公告
     */
    @PostMapping("/removeBatch")
    @PreAuthorize("hasAuthority('system:notice:remove')")
    @Log(title = "通知公告", businessType = BusinessType.DELETE)
    public Result<Void> deleteNotices(@RequestBody List<Long> noticeIds) {
        sysNoticeService.deleteNotices(noticeIds);
        return Result.ok();
    }

    /**
     * 修改公告状态
     */
    @PostMapping("/changeStatus")
    @PreAuthorize("hasAuthority('system:notice:edit')")
    @Log(title = "通知公告", businessType = BusinessType.UPDATE)
    public Result<Void> changeStatus(@RequestParam Long noticeId, @RequestParam Integer status) {
        sysNoticeService.changeStatus(noticeId, status);
        return Result.ok();
    }
}
