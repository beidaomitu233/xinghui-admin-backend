package com.xinghuiTec.service.impl;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import com.baomidou.mybatisplus.extension.service.impl.ServiceImpl;
import com.xinghuiTec.domain.dto.SysFileQueryDTO;
import com.xinghuiTec.domain.entity.SysFile;
import com.xinghuiTec.mapper.SysFileMapper;
import com.xinghuiTec.service.SysFileService;
import jakarta.annotation.Resource;
import jakarta.servlet.http.HttpServletResponse;
import lombok.extern.slf4j.Slf4j;
import org.dromara.x.file.storage.core.FileInfo;
import org.dromara.x.file.storage.core.FileStorageService;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.util.StringUtils;
import org.springframework.web.multipart.MultipartFile;

import java.io.OutputStream;
import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.util.List;

/**
 * 文件服务实现类（使用 X File Storage 框架）
 * 
 * @author beidoa23
 * @since 2026-01-24
 */
@Service
@Slf4j
public class SysFileServiceImpl extends ServiceImpl<SysFileMapper, SysFile> implements SysFileService {
    
    @Resource
    private FileStorageService fileStorageService;
    
@Override
@Transactional(rollbackFor = Exception.class)
public SysFile uploadFile(MultipartFile file) {
    try {
        // 判断是否为图片文件
        boolean isImage = isImageFile(file);

        // 使用 X File Storage 框架上传文件
        org.dromara.x.file.storage.core.UploadPretreatment pre = fileStorageService.of(file)
                .setPath("upload/");  // 设置路径

        // 仅对图片文件生成缩略图
        if (isImage) {
            pre.thumbnail(th -> th.size(200, 200));  // 仅图片生成缩略图
        }

        org.dromara.x.file.storage.core.FileInfo fileInfo = pre.upload();  // 执行上传

        // 转换为数据库实体
        SysFile sysFile = toEntity(fileInfo);

        log.info("文件上传成功: {}, 是否为图片: {}", file.getOriginalFilename(), isImage);
        return sysFile;

    } catch (Exception e) {
        log.error("文件上传失败", e);
        throw new RuntimeException("文件上传失败: " + e.getMessage());
    }
}

/**
 * 判断是否为图片文件
 */
private boolean isImageFile(MultipartFile file) {
    String contentType = file.getContentType();
    String originalFilename = file.getOriginalFilename();

    // 检查 Content-Type
    if (contentType != null) {
        return contentType.startsWith("image/");
    }

    // 如果 Content-Type 无法确定，检查文件扩展名
    if (originalFilename != null) {
        String extension = getFileExtension(originalFilename).toLowerCase();
        return isImageExtension(extension);
    }

    return false;
}

/**
 * 获取文件扩展名
 */
private String getFileExtension(String filename) {
    int lastDotIndex = filename.lastIndexOf('.');
    if (lastDotIndex > 0 && lastDotIndex < filename.length() - 1) {
        return filename.substring(lastDotIndex + 1);
    }
    return "";
}

/**
 * 判断是否为图片扩展名
 */
private boolean isImageExtension(String extension) {
    return "jpg".equals(extension) ||
           "jpeg".equals(extension) ||
           "png".equals(extension) ||
           "gif".equals(extension) ||
           "bmp".equals(extension) ||
           "webp".equals(extension) ||
           "svg".equals(extension);
}

    
    /**
     * 分页查询文件列表
     */
    /**
     * 分页查询文件列表
     */
    @Override
    public Page<SysFile> getFileList(SysFileQueryDTO queryDTO) {
        Page<SysFile> page = new Page<>(queryDTO.getPageNum(), queryDTO.getPageSize());
        LambdaQueryWrapper<SysFile> wrapper = new LambdaQueryWrapper<>();

        // 1. 文件名模糊查询
        // 痛点修正：使用 hasText 过滤掉 " " 或 ""，避免生成 WHERE filename LIKE '%%'
        // 字段修正：SQL中 `filename` 是原始文件名，`original_name` 是存储名，搜索通常指前者
        wrapper.like(StringUtils.hasText(queryDTO.getOriginalName()),
                SysFile::getFilename,
                queryDTO.getOriginalName());

        // 2. 文件类型精准过滤
        wrapper.eq(StringUtils.hasText(queryDTO.getFileType()),
                SysFile::getExt,
                queryDTO.getFileType());

        // 3. 存储平台过滤
        wrapper.eq(StringUtils.hasText(queryDTO.getStorageType()),
                SysFile::getPlatform,
                queryDTO.getStorageType());

        // 4. 时间范围查询
        // 只有当对象非空时才拼接 SQL，避免 NullPointerException
        wrapper.ge(queryDTO.getUploadTimeStart() != null,
                SysFile::getCreateTime,
                queryDTO.getUploadTimeStart());

        wrapper.le(queryDTO.getUploadTimeEnd() != null,
                SysFile::getCreateTime,
                queryDTO.getUploadTimeEnd());

        // 5. 排序逻辑
        // 默认按时间倒序（最新的在最前），仅在明确指定 "asc" 时正序
        boolean isAsc = "asc".equalsIgnoreCase(queryDTO.getOrder());
        wrapper.orderBy(true, isAsc, SysFile::getCreateTime);

        return this.page(page, wrapper);
    }
    
    /**
     * 删除文件
     */
    @Override
    @Transactional(rollbackFor = Exception.class)
    public void deleteFile(String fileId) {
        SysFile file = this.getById(fileId);
        if (file == null) {
            throw new RuntimeException("文件不存在");
        }
        
        // 使用框架删除文件（会自动删除缩略图）
        boolean deleted = fileStorageService.delete(file.getUrl());
        if (!deleted) {
            log.warn("文件删除失败，但继续删除数据库记录: {}", file.getUrl());
        }
        
        log.info("文件删除成功: {}", file.getOriginalName());
    }
    
    /**
     * 批量删除文件
     */
    @Override
    @Transactional(rollbackFor = Exception.class)
    public void deleteFiles(List<String> fileIds) {
        for (String fileId : fileIds) {
            deleteFile(fileId);
        }
    }
    
    /**
     * 下载文件
     */
    @Override
    public void downloadFile(String fileId, HttpServletResponse response) {
        try {
            SysFile file = this.getById(fileId);
            if (file == null) {
                throw new RuntimeException("文件不存在");
            }
            
            // 使用框架下载文件
            byte[] bytes = fileStorageService.download(file.getUrl()).bytes();
            
            // 设置响应头
            response.setContentType("application/octet-stream");
            response.setHeader("Content-Disposition", 
                "attachment; filename=" + URLEncoder.encode(file.getOriginalName(), StandardCharsets.UTF_8));
            
            // 写入响应流
            OutputStream outputStream = response.getOutputStream();
            outputStream.write(bytes);
            outputStream.flush();
            
            log.info("文件下载: {}", file.getOriginalName());
            
        } catch (Exception e) {
            log.error("文件下载失败", e);
            throw new RuntimeException("文件下载失败: " + e.getMessage());
        }
    }
    
    /**
     * 获取文件访问URL
     */
    @Override
    public String getFileUrl(String fileId) {
        SysFile file = this.getById(fileId);
        return file == null ? null : file.getUrl();
    }
    
    /**
     * X File Storage FileInfo 转 SysFile实体
     */
    private SysFile toEntity(org.dromara.x.file.storage.core.FileInfo fileInfo) {
        SysFile sysFile = new SysFile();
        sysFile.setId(System.currentTimeMillis());
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
        return sysFile;
    }
}
