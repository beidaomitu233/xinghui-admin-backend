package com.xinghuiTec.controller;

import com.xinghuiTec.oss.OssService;
import com.xinghuiTec.utils.Result;
import lombok.RequiredArgsConstructor;
import org.dromara.x.file.storage.core.FileInfo;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.multipart.MultipartFile;

import java.util.HashMap;
import java.util.Map;

/**
 * OSS 文件管理 Controller
 *
 * @author xinghuiTec
 */
@RestController
@RequestMapping("/system/oss")
@RequiredArgsConstructor
public class SysOssController {

    private final OssService ossService;

    /**
     * 上传文件
     */
    @PreAuthorize("hasAuthority('system:oss:upload')")
    @PostMapping("/upload")
    public Result<Map<String, Object>> upload(@RequestParam("file") MultipartFile file,
                                               @RequestParam(required = false) String platform) {
        FileInfo info = ossService.upload(file, platform);
        Map<String, Object> result = new HashMap<>();
        result.put("url", info.getUrl());
        result.put("filename", info.getOriginalFilename());
        result.put("size", info.getSize());
        result.put("platform", info.getPlatform());
        return Result.ok(result);
    }

    /**
     * 删除文件
     */
    @PreAuthorize("hasAuthority('system:oss:remove')")
    @DeleteMapping
    public Result<Void> delete(@RequestBody Map<String, String> body) {
        String url = body.get("url");
        ossService.delete(url);
        return Result.ok();
    }

    /**
     * 获取文件信息
     */
    @PreAuthorize("hasAuthority('system:oss:query')")
    @GetMapping("/info")
    public Result<Map<String, Object>> getInfo(@RequestParam String url) {
        boolean exists = ossService.exists(url);
        Map<String, Object> result = new HashMap<>();
        result.put("exists", exists);
        if (exists) {
            FileInfo info = ossService.getFileInfo(url);
            result.put("filename", info.getOriginalFilename());
            result.put("size", info.getSize());
            result.put("platform", info.getPlatform());
        }
        return Result.ok(result);
    }
}
