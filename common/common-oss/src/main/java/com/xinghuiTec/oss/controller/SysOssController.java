package com.xinghuiTec.oss.controller;

import cn.hutool.core.util.StrUtil;
import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import com.xinghuiTec.oss.OssService;
import com.xinghuiTec.oss.entity.SysOss;
import com.xinghuiTec.oss.service.ISysOssService;
import com.xinghuiTec.utils.Result;
import lombok.RequiredArgsConstructor;
import org.dromara.x.file.storage.core.FileInfo;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.multipart.MultipartFile;

import java.util.Arrays;
import java.util.HashMap;
import java.util.Map;

/**
 * OSS 文件管理
 *
 * @author xinghuiTec
 */
@RestController
@RequestMapping("/system/oss")
@RequiredArgsConstructor
public class SysOssController {

    private final OssService ossService;
    private final ISysOssService ossRecordService;

    /** 分页查询文件记录 */
    @PreAuthorize("hasAuthority('system:oss:list')")
    @GetMapping("/list")
    public Result<Page<SysOss>> list(@RequestParam(defaultValue = "1") Integer pageNum,
                                      @RequestParam(defaultValue = "10") Integer pageSize,
                                      @RequestParam(required = false) String originalName) {
        LambdaQueryWrapper<SysOss> qw = new LambdaQueryWrapper<>();
        qw.like(StrUtil.isNotBlank(originalName), SysOss::getOriginalName, originalName);
        qw.orderByDesc(SysOss::getCreateTime);
        return Result.ok(ossRecordService.page(new Page<>(pageNum, pageSize), qw));
    }

    /** 上传文件 */
    @PreAuthorize("hasAuthority('system:oss:upload')")
    @PostMapping("/upload")
    public Result<Map<String, Object>> upload(@RequestParam("file") MultipartFile file,
                                               @RequestParam(required = false) String platform) {
        FileInfo info = ossService.upload(file, platform);

        // 记录上传元数据
        SysOss record = new SysOss();
        record.setOriginalName(info.getOriginalFilename());
        record.setFileName(info.getFilename());
        record.setFileSuffix(StrUtil.subAfter(info.getOriginalFilename(), ".", true));
        record.setUrl(info.getUrl());
        record.setSize(info.getSize());
        record.setPlatform(info.getPlatform());
        ossRecordService.save(record);

        Map<String, Object> result = new HashMap<>();
        result.put("ossId", record.getOssId());
        result.put("url", info.getUrl());
        result.put("filename", info.getOriginalFilename());
        result.put("size", info.getSize());
        result.put("platform", info.getPlatform());
        return Result.ok(result);
    }

    /** 删除文件 */
    @PreAuthorize("hasAuthority('system:oss:remove')")
    @DeleteMapping("/{ossIds}")
    public Result<Void> remove(@PathVariable Long[] ossIds) {
        for (Long id : ossIds) {
            SysOss record = ossRecordService.getById(id);
            if (record != null) {
                ossService.delete(record.getUrl());
                ossRecordService.removeById(id);
            }
        }
        return Result.ok();
    }

    /** 获取文件信息 */
    @PreAuthorize("hasAuthority('system:oss:query')")
    @GetMapping("/info/{ossId}")
    public Result<SysOss> getInfo(@PathVariable Long ossId) {
        return Result.ok(ossRecordService.getById(ossId));
    }
}
