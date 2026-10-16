package com.xinghuiTec.recorder;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.xinghuiTec.domain.entity.SysFilePart;
import com.xinghuiTec.mapper.SysFilePartMapper;
import com.xinghuiTec.oss.entity.SysOss;
import com.xinghuiTec.oss.service.ISysOssService;
import com.xinghuiTec.utils.SecurityUtils;
import com.xinghuiTec.utils.TenantHelper;
import jakarta.annotation.Resource;
import lombok.extern.slf4j.Slf4j;
import org.dromara.x.file.storage.core.FileInfo;
import org.dromara.x.file.storage.core.recorder.FileRecorder;
import org.dromara.x.file.storage.core.upload.FilePartInfo;
import org.springframework.stereotype.Component;

import java.util.Date;

/**
 * X File Storage 文件记录器实现
 * 用于保存文件上传记录到 OSS 数据库表
 * 
 * @author beidoa23
 * @since 2026-01-24
 */
@Component
@Slf4j
public class FileRecorderImpl implements FileRecorder {

    @Resource
    private ISysOssService sysOssService;

    @Resource
    private SysFilePartMapper sysFilePartMapper;

    @Override
    public boolean save(FileInfo fileInfo) {
        SysOss sysOss = toEntity(fileInfo);
        if (SecurityUtils.getUserId() != null) {
            sysOss.setCreateBy(String.valueOf(SecurityUtils.getUserId()));
        }
        if (TenantHelper.isEnable()) {
            sysOss.setTenantId(TenantHelper.getTenantId());
        }
        boolean result = sysOssService.save(sysOss);
        if (result && sysOss.getOssId() != null) {
            // 将生成的自增主键反写回 FileInfo，解决 Issue #2
            fileInfo.setId(String.valueOf(sysOss.getOssId()));
        }
        log.info("保存文件记录: {}", fileInfo.getOriginalFilename());
        return result;
    }

    @Override
    public void update(FileInfo fileInfo) {
        SysOss sysOss = toEntity(fileInfo);
        // 更新文件记录，匹配URL
        LambdaQueryWrapper<SysOss> wrapper = new LambdaQueryWrapper<>();
        wrapper.eq(SysOss::getUrl, fileInfo.getUrl());
        sysOssService.update(sysOss, wrapper);
        log.info("更新文件记录: {}", fileInfo.getOriginalFilename());
    }

    @Override
    public FileInfo getByUrl(String url) {
        SysOss sysOss = sysOssService.getOne(
                new LambdaQueryWrapper<SysOss>()
                        .eq(SysOss::getUrl, url), false);
        return sysOss == null ? null : toFileInfo(sysOss);
    }

    @Override
    public boolean delete(String url) {
        boolean result = sysOssService.remove(
                new LambdaQueryWrapper<SysOss>()
                        .eq(SysOss::getUrl, url));
        log.info("删除文件记录: {}", url);
        return result;
    }

    @Override
    public void saveFilePart(FilePartInfo filePartInfo) {
        SysFilePart filePart = new SysFilePart();
        filePart.setId(filePartInfo.getId());
        filePart.setPlatform(filePartInfo.getPlatform());
        filePart.setUploadId(filePartInfo.getUploadId());
        filePart.setETag(filePartInfo.getETag());
        filePart.setPartNumber(filePartInfo.getPartNumber());
        filePart.setPartSize(filePartInfo.getPartSize());
        filePart.setCreateTime(new Date());
        sysFilePartMapper.insert(filePart);
        log.info("保存分片记录: PartNumber={}, UploadId={}", filePartInfo.getPartNumber(), filePartInfo.getUploadId());
    }

    @Override
    public void deleteFilePartByUploadId(String uploadId) {
        sysFilePartMapper.delete(new LambdaQueryWrapper<SysFilePart>()
                .eq(SysFilePart::getUploadId, uploadId));
        log.info("删除分片记录: UploadId={}", uploadId);
    }

    /**
     * FileInfo 转 SysOss实体
     */
    private SysOss toEntity(FileInfo fileInfo) {
        SysOss sysOss = new SysOss();
        if (fileInfo.getId() != null) {
            try {
                sysOss.setOssId(Long.parseLong(fileInfo.getId()));
            } catch (Exception e) {
                // ignore
            }
        }
        sysOss.setUrl(fileInfo.getUrl());
        sysOss.setSize(fileInfo.getSize());
        sysOss.setFileName(fileInfo.getFilename());
        sysOss.setOriginalName(fileInfo.getOriginalFilename());
        sysOss.setFileSuffix(fileInfo.getExt());
        sysOss.setPlatform(fileInfo.getPlatform());
        if (fileInfo.getCreateTime() != null) {
            sysOss.setCreateTime(fileInfo.getCreateTime());
        }
        return sysOss;
    }

    /**
     * SysOss实体 转 FileInfo
     */
    private FileInfo toFileInfo(SysOss sysOss) {
        FileInfo fileInfo = new FileInfo();
        // 当前 MinIO 记录的 url 是桶内完整对象键：basePath + path + filename。
        // 旧表未保存 basePath/path，必须从对象键恢复，否则下载只找 filename，
        // 删除会对错误对象键返回成功并误删数据库记录。
        String url = sysOss.getUrl();
        String filename = sysOss.getFileName();
        if (url == null || filename == null || filename.isBlank()
                || filename.contains("/") || filename.contains("\\")
                || url.contains("://") || url.contains("..")
                || !url.endsWith("/" + filename)) {
            throw new IllegalStateException("文件对象路径无法恢复");
        }
        fileInfo.setBasePath("");
        fileInfo.setPath(url.substring(0, url.length() - filename.length()));
        fileInfo.setUrl(sysOss.getUrl());
        fileInfo.setSize(sysOss.getSize());
        fileInfo.setFilename(sysOss.getFileName());
        fileInfo.setOriginalFilename(sysOss.getOriginalName());
        fileInfo.setExt(sysOss.getFileSuffix());
        fileInfo.setPlatform(sysOss.getPlatform());
        fileInfo.setCreateTime(sysOss.getCreateTime());
        // ID需要设置
        if (sysOss.getOssId() != null) {
            fileInfo.setId(String.valueOf(sysOss.getOssId()));
        }
        return fileInfo;
    }
}
