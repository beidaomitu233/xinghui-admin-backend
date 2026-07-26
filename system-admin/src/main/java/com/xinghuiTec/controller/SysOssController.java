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

    /**
     * 上传文件
     *
     * @param file     上传文件
     * @param platform 目标存储平台（可选，如 minio-1 / aliyun-oss-1，空则使用默认平台）
     */
    @PostMapping("/upload")
    @PreAuthorize("hasAuthority('system:oss:upload')")
    @Log(title = "文件上传", businessType = BusinessType.INSERT)
    public Result<SysOss> upload(@RequestParam("file") MultipartFile file,
                                 @RequestParam(required = false) String platform) {
        FileInfo fileInfo = ossService.upload(file, platform);
        // FileRecorderImpl 落库成功后会把生成的主键反写进 fileInfo.id；
        // 反写缺失说明记录未落库（插入失败/记录器未生效），对象已在存储端，须明确报错而非 NPE
        if (fileInfo.getId() == null || fileInfo.getId().isBlank()) {
            throw new RuntimeException("文件已上传但记录保存失败，请联系管理员");
        }
        SysOss sysOss = sysOssService.getById(Long.parseLong(fileInfo.getId()));
        return Result.ok(sysOss);
    }

    @GetMapping("/download/{ossId}")
    @PreAuthorize("hasAuthority('system:oss:download')")
    @Log(title = "文件下载", businessType = BusinessType.OTHER)
    public void download(@PathVariable("ossId") Long ossId, HttpServletResponse response) {
        SysOss sysOss = sysOssService.getById(ossId);
        if (sysOss == null) {
            throw new RuntimeException("文件不存在或已删除");
        }
        // 传入已加载记录的原始文件名，避免存储层再按 url 回查一次
        ossService.download(sysOss.getUrl(), sysOss.getOriginalName(), response);
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

    /** 获取单个文件记录详情 */
    @GetMapping("/info/{ossId}")
    @PreAuthorize("hasAuthority('system:oss:query')")
    public Result<SysOss> getInfo(@PathVariable("ossId") Long ossId) {
        SysOss sysOss = sysOssService.getById(ossId);
        if (sysOss == null) {
            throw new RuntimeException("文件不存在或已删除");
        }
        return Result.ok(sysOss);
    }

    @PostMapping("/remove")
    @PreAuthorize("hasAuthority('system:oss:remove')")
    @Log(title = "文件删除", businessType = BusinessType.DELETE)
    public Result<Void> remove(@RequestBody List<Long> ossIds) {
        if (ossIds == null || ossIds.isEmpty()) {
            return Result.ok();
        }
        // 一次批量加载替代逐个 getById；存储删除时 FileRecorderImpl 会按 url 同步
        // 逻辑删除记录，无需再 removeById（原实现的第二次删除恒匹配 0 行）
        List<SysOss> records = sysOssService.listByIds(ossIds);
        for (SysOss sysOss : records) {
            ossService.delete(sysOss.getUrl());
        }
        return Result.ok();
    }
}
