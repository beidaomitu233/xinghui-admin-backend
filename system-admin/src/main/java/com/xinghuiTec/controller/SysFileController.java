package com.xinghuiTec.controller;

import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import com.xinghuiTec.annotation.Log;
import com.xinghuiTec.domain.dto.SysFileQueryDTO;
import com.xinghuiTec.domain.entity.SysFile;
import com.xinghuiTec.emues.BusinessType;
import com.xinghuiTec.service.SysFileService;
import com.xinghuiTec.utils.Result;
import jakarta.annotation.Resource;
import jakarta.servlet.http.HttpServletResponse;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.validation.annotation.Validated;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.multipart.MultipartFile;

import java.util.List;

/**
 * 文件管理控制器
 * 
 * @author beidoa23
 * @since 2026-01-23
 */
@RestController
@RequestMapping("/file")
public class SysFileController {

    @Resource
    private SysFileService sysFileService;

    @PostMapping("/upload")
    @PreAuthorize("hasAuthority('system:file:upload')")
    @Log(title = "文件管理", businessType = BusinessType.INSERT)
    public Result<SysFile> uploadFile(@RequestParam("file") MultipartFile file) {
        SysFile sysFile = sysFileService.uploadFile(file);
        return Result.ok(sysFile);
    }

    @GetMapping("/list")
    @PreAuthorize("hasAuthority('system:file:list')")
    public Result<Page<SysFile>> getFileList(@Validated SysFileQueryDTO queryDTO) {
        Page<SysFile> page = sysFileService.getFileList(queryDTO);
        return Result.ok(page);
    }

    @PostMapping("/remove")
    @PreAuthorize("hasAuthority('system:file:remove')")
    @Log(title = "文件管理", businessType = BusinessType.DELETE)
    public Result<Void> deleteFile(@RequestParam("fileId") String fileId) {
        sysFileService.deleteFile(fileId);
        return Result.ok();
    }

    @PostMapping("/removeBatch")
    @PreAuthorize("hasAuthority('system:file:remove')")
    @Log(title = "文件管理", businessType = BusinessType.DELETE)
    public Result<Void> deleteFiles(@RequestBody List<String> fileIds) {
        sysFileService.deleteFiles(fileIds);
        return Result.ok();
    }

    @GetMapping("/download")
    @PreAuthorize("hasAuthority('system:file:download')")
    public void downloadFile(@RequestParam("fileId") String fileId, HttpServletResponse response) {
        sysFileService.downloadFile(fileId, response);
    }

    @GetMapping("/url/{fileId}")
    @PreAuthorize("hasAuthority('system:file:query')")
    public Result<String> getFileUrl(@PathVariable("fileId") String fileId) {
        String url = sysFileService.getFileUrl(fileId);
        return Result.ok(url);
    }
}
