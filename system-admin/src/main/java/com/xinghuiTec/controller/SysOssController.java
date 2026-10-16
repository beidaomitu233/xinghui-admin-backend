package com.xinghuiTec.controller;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import com.xinghuiTec.annotation.Log;
import com.xinghuiTec.emues.BusinessType;
import com.xinghuiTec.oss.OssService;
import com.xinghuiTec.oss.entity.SysOss;
import com.xinghuiTec.oss.service.ISysOssService;
import com.xinghuiTec.utils.Result;
import com.xinghuiTec.utils.SecurityUtils;
import com.xinghuiTec.utils.TenantHelper;
import jakarta.annotation.Resource;
import jakarta.servlet.http.HttpServletResponse;
import lombok.extern.slf4j.Slf4j;
import org.dromara.x.file.storage.core.FileInfo;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.util.StringUtils;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.multipart.MultipartFile;

import java.util.List;
import java.util.Set;

/**
 * 对象存储/文件服务 控制层
 * 替代原有的 SysFileController
 */
@RestController
@RequestMapping("/system/oss")
@Slf4j
public class SysOssController {

    public static final long MAX_UPLOAD_SIZE = 20L * 1024 * 1024;
    private static final Set<String> ALLOWED_SUFFIXES = Set.of(
            "jpg", "jpeg", "png", "gif", "webp", "pdf", "txt", "csv",
            "doc", "docx", "xls", "xlsx", "ppt", "pptx", "zip");

    @Resource
    private OssService ossService;

    @Resource
    private ISysOssService sysOssService;

    /**
     * 上传文件
     *
     * @param file     上传文件
     */
    @PostMapping("/upload")
    @PreAuthorize("hasAuthority('system:oss:upload')")
    @Log(title = "文件上传", businessType = BusinessType.INSERT)
    public Result<SysOss> upload(@RequestParam("file") MultipartFile file) {
        validateUpload(file);
        FileInfo fileInfo = ossService.upload(file);
        // FileRecorderImpl 落库成功后会把生成的主键反写进 fileInfo.id；
        // 反写缺失说明记录未落库（插入失败/记录器未生效），对象已在存储端，须明确报错而非 NPE
        if (fileInfo.getId() == null || fileInfo.getId().isBlank()) {
            ossService.delete(fileInfo.getUrl());
            throw new RuntimeException("文件已上传但记录保存失败，请联系管理员");
        }
        SysOss sysOss = sysOssService.getById(Long.parseLong(fileInfo.getId()));
        if (sysOss == null) {
            ossService.delete(fileInfo.getUrl());
            throw new RuntimeException("文件记录保存失败，请重试");
        }
        return Result.ok(sysOss);
    }

    @GetMapping("/download/{ossId}")
    @PreAuthorize("hasAuthority('system:oss:download')")
    @Log(title = "文件下载", businessType = BusinessType.OTHER)
    public void download(@PathVariable("ossId") Long ossId, HttpServletResponse response) {
        SysOss sysOss = ownedFile(ossId);
        try {
            ossService.download(sysOss.getUrl(), sysOss.getOriginalName(), response);
        } catch (RuntimeException e) {
            log.error("文件下载失败: ossId={}", ossId, e);
            if (response.isCommitted()) {
                // 流传输中断后不能再附加 JSON 到已提交的二进制响应。
                return;
            }
            response.reset();
            throw new FileDownloadException();
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
        wrapper.eq(SysOss::getCreateBy, currentUserId());
        if (TenantHelper.isEnable()) {
            wrapper.eq(SysOss::getTenantId, TenantHelper.getTenantId());
        }
        wrapper.orderByDesc(SysOss::getCreateTime);

        return Result.ok(sysOssService.page(page, wrapper));
    }

    /** 获取单个文件记录详情 */
    @GetMapping("/info/{ossId}")
    @PreAuthorize("hasAuthority('system:oss:query')")
    public Result<SysOss> getInfo(@PathVariable("ossId") Long ossId) {
        return Result.ok(ownedFile(ossId));
    }

    @PostMapping("/remove")
    @PreAuthorize("hasAuthority('system:oss:remove')")
    @Log(title = "文件删除", businessType = BusinessType.DELETE)
    public Result<Void> remove(@RequestBody List<Long> ossIds) {
        if (ossIds == null || ossIds.isEmpty()) {
            throw new IllegalArgumentException("请选择要删除的文件");
        }
        // 删除前逐条验权，避免批量请求先删除部分文件后才发现越权或缺失。
        List<SysOss> records = ossIds.stream().distinct().map(this::ownedFile).toList();
        for (SysOss sysOss : records) {
            if (!ossService.delete(sysOss.getUrl())) {
                throw new RuntimeException("文件删除失败，请重试");
            }
        }
        return Result.ok();
    }

    private SysOss ownedFile(Long ossId) {
        SysOss record = sysOssService.getById(ossId);
        if (record == null) {
            throw new FileMissingException();
        }
        if (!currentUserId().equals(record.getCreateBy()) ||
                (TenantHelper.isEnable() && !java.util.Objects.equals(TenantHelper.getTenantId(), record.getTenantId()))) {
            throw new AccessDeniedException("无权访问该文件");
        }
        return record;
    }

    private String currentUserId() {
        Long id = SecurityUtils.getUserId();
        if (id == null) {
            throw new AccessDeniedException("请先登录");
        }
        return String.valueOf(id);
    }

    private void validateUpload(MultipartFile file) {
        if (file.isEmpty()) {
            throw new IllegalArgumentException("文件不能为空");
        }
        if (file.getSize() > MAX_UPLOAD_SIZE) {
            throw new IllegalArgumentException("文件不能超过 20 MB");
        }
        String name = file.getOriginalFilename();
        int dot = name == null ? -1 : name.lastIndexOf('.');
        String suffix = dot < 0 ? "" : name.substring(dot + 1).toLowerCase(java.util.Locale.ROOT);
        if (!ALLOWED_SUFFIXES.contains(suffix)) {
            throw new IllegalArgumentException("不支持此文件类型");
        }
    }

    private static class FileMissingException extends RuntimeException {
    }

    private static class FileDownloadException extends RuntimeException {
    }

    @ExceptionHandler(FileMissingException.class)
    public ResponseEntity<Result<Void>> missing() {
        return ResponseEntity.status(HttpStatus.NOT_FOUND).body(Result.fail("文件不存在或已删除"));
    }

    @ExceptionHandler(AccessDeniedException.class)
    public ResponseEntity<Result<Void>> forbidden() {
        return ResponseEntity.status(HttpStatus.FORBIDDEN).body(Result.fail(403, "无权访问该文件"));
    }

    @ExceptionHandler(FileDownloadException.class)
    public ResponseEntity<Result<Void>> downloadFailed() {
        return ResponseEntity.status(HttpStatus.BAD_GATEWAY).body(Result.fail("文件下载失败"));
    }
}
