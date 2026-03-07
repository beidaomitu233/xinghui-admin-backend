package com.xinghuiTec.recorder;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.xinghuiTec.domain.entity.SysFile;
import com.xinghuiTec.domain.entity.SysFilePart;
import com.xinghuiTec.mapper.SysFileMapper;
import com.xinghuiTec.mapper.SysFilePartMapper;
import jakarta.annotation.Resource;
import lombok.extern.slf4j.Slf4j;
import org.dromara.x.file.storage.core.FileInfo;
import org.dromara.x.file.storage.core.recorder.FileRecorder;
import org.dromara.x.file.storage.core.upload.FilePartInfo;
import org.springframework.stereotype.Component;

import java.util.Date;

/**
 * X File Storage 文件记录器实现
 * 用于保存文件上传记录到数据库
 * 
 * @author beidoa23
 * @since 2026-01-24
 */
@Component
@Slf4j
public class FileRecorderImpl implements FileRecorder {

    @Resource
    private SysFileMapper sysFileMapper;

    @Resource
    private SysFilePartMapper sysFilePartMapper;

    @Override
    public boolean save(FileInfo fileInfo) {
        SysFile sysFile = toEntity(fileInfo);
        int result = sysFileMapper.insert(sysFile);
        log.info("保存文件记录: {}", fileInfo.getOriginalFilename());
        return result > 0;
    }

    @Override
    public void update(FileInfo fileInfo) {
        SysFile sysFile = toEntity(fileInfo);
        // 更新文件记录，匹配URL
        LambdaQueryWrapper<SysFile> wrapper = new LambdaQueryWrapper<>();
        wrapper.eq(SysFile::getUrl, fileInfo.getUrl());
        sysFileMapper.update(sysFile, wrapper);
        log.info("更新文件记录: {}", fileInfo.getOriginalFilename());
    }

    @Override
    public FileInfo getByUrl(String url) {
        SysFile sysFile = sysFileMapper.selectOne(
                new LambdaQueryWrapper<SysFile>()
                        .eq(SysFile::getUrl, url));
        return sysFile == null ? null : toFileInfo(sysFile);
    }

    @Override
    public boolean delete(String url) {
        int result = sysFileMapper.delete(
                new LambdaQueryWrapper<SysFile>()
                        .eq(SysFile::getUrl, url));
        log.info("删除文件记录: {}", url);
        return result > 0;
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
        // filePart.setHash(filePartInfo.getHash()); // FilePartInfo可能没有getHash方法
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
     * FileInfo 转 SysFile实体
     */
    private SysFile toEntity(FileInfo fileInfo) {
        SysFile sysFile = new SysFile();
        // ID通常由数据库生成或此处生成，如果是更新操作需注意
        // 此处简单以时间戳生成ID，实际生产建议使用雪花算法
        if (fileInfo.getId() == null) {
            sysFile.setId(System.currentTimeMillis());
        } else {
            // 尝试转换String ID, 如果是更新，FileInfo可能带着ID
            try {
                sysFile.setId(Long.parseLong(fileInfo.getId()));
            } catch (Exception e) {
                // 忽略异常
            }
        }
        sysFile.setUrl(fileInfo.getUrl());
        sysFile.setSize(fileInfo.getSize());
        sysFile.setFilename(fileInfo.getFilename());
        sysFile.setOriginalName(fileInfo.getOriginalFilename());
        sysFile.setBasePath(fileInfo.getBasePath());
        sysFile.setPath(fileInfo.getPath());
        sysFile.setExt(fileInfo.getExt());
        sysFile.setPlatform(fileInfo.getPlatform());
        sysFile.setThUrl(fileInfo.getThUrl());
        sysFile.setThFilename(fileInfo.getThFilename());
        sysFile.setThSize(fileInfo.getThSize());
        sysFile.setObjectId(fileInfo.getObjectId());
        sysFile.setCreateTime(fileInfo.getCreateTime());
        return sysFile;
    }

    /**
     * SysFile实体 转 FileInfo
     */
    private FileInfo toFileInfo(SysFile sysFile) {
        FileInfo fileInfo = new FileInfo();
        fileInfo.setUrl(sysFile.getUrl());
        fileInfo.setSize(sysFile.getSize());
        fileInfo.setFilename(sysFile.getFilename());
        fileInfo.setOriginalFilename(sysFile.getOriginalName());
        fileInfo.setBasePath(sysFile.getBasePath());
        fileInfo.setPath(sysFile.getPath());
        fileInfo.setExt(sysFile.getExt());
        fileInfo.setPlatform(sysFile.getPlatform());
        fileInfo.setThUrl(sysFile.getThUrl());
        fileInfo.setThFilename(sysFile.getThFilename());
        fileInfo.setThSize(sysFile.getThSize());
        fileInfo.setObjectId(sysFile.getObjectId());
        fileInfo.setCreateTime(sysFile.getCreateTime());
        // ID需要设置
        fileInfo.setId(String.valueOf(sysFile.getId()));
        return fileInfo;
    }
}
