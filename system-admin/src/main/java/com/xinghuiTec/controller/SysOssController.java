package com.xinghuiTec.controller;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import com.xinghuiTec.annotation.Log;
import com.xinghuiTec.emues.BusinessType;
import com.xinghuiTec.oss.OssService;
import com.xinghuiTec.oss.entity.SysOss;
import com.xinghuiTec.oss.service.ISysOssService;
import com.xinghuiTec.utils.Result;
import jakarta.annotation.Resource;
import jakarta.servlet.http.HttpServletResponse;
import org.dromara.x.file.storage.core.FileInfo;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.util.StringUtils;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.multipart.MultipartFile;

import java.util.List;

/**
 * 对象存储/文件服务 控制层
 * 替代原有的 SysFileController
 */
@RestController
@RequestMapping("/system/oss")
public class SysOssController {

    @Resource
    private OssService ossService;

    @Resource
    private ISysOssService sysOssService;

    @PostMapping("/upload")
    @PreAuthorize("hasAuthority('system:oss:upload')")
    @Log(title = "文件上传", businessType = BusinessType.INSERT)
    public Result<SysOss> upload(@RequestParam("file") MultipartFile file) {
        FileInfo fileInfo = ossService.upload(file);
        // 通过 fileInfo.getId() 拿回 FileRecorderImpl 中反写的 ID
        SysOss sysOss = sysOssService.getById(Long.parseLong(fileInfo.getId()));
        return Result.ok(sysOss);
    }

    @GetMapping("/download/{ossId}")
    @PreAuthorize("hasAuthority('system:oss:download')")
    @Log(title = "文件下载", businessType = BusinessType.OTHER)
    public void download(@PathVariable("ossId") Long ossId, HttpServletResponse response) {
        SysOss sysOss = sysOssService.getById(ossId);
        if (sysOss != null) {
            ossService.download(sysOss.getUrl(), response);
        }
    }

    @GetMapping("/list")
    @PreAuthorize("hasAuthority('system:oss:list')")
    public Result<Page<SysOss>> getList(
            @RequestParam(defaultValue = "1") Integer pageNum,
            @RequestParam(defaultValue = "10") Integer pageSize,
            @RequestParam(required = false) String originalName,
            @RequestParam(required = false) String fileSuffix) {
        
        Page<SysOss> page = new Page<>(pageNum, pageSize);
        LambdaQueryWrapper<SysOss> wrapper = new LambdaQueryWrapper<>();
        wrapper.like(StringUtils.hasText(originalName), SysOss::getOriginalName, originalName);
        wrapper.eq(StringUtils.hasText(fileSuffix), SysOss::getFileSuffix, fileSuffix);
        wrapper.orderByDesc(SysOss::getCreateTime);

        return Result.ok(sysOssService.page(page, wrapper));
    }

    @PostMapping("/remove")
    @PreAuthorize("hasAuthority('system:oss:remove')")
    @Log(title = "文件删除", businessType = BusinessType.DELETE)
    public Result<Void> remove(@RequestBody List<Long> ossIds) {
        for (Long id : ossIds) {
            SysOss sysOss = sysOssService.getById(id);
            if (sysOss != null) {
                ossService.delete(sysOss.getUrl());
                sysOssService.removeById(id);
            }
        }
        return Result.ok();
    }
}
