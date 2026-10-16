package com.xinghuiTec.oss;

import cn.hutool.core.io.IoUtil;
import cn.hutool.core.util.IdUtil;
import cn.hutool.core.util.StrUtil;
import com.xinghuiTec.oss.config.OssRuntimeSettings;
import lombok.extern.slf4j.Slf4j;
import org.dromara.x.file.storage.core.FileInfo;
import org.dromara.x.file.storage.core.FileStorageService;
import org.dromara.x.file.storage.core.upload.UploadPretreatment;
import org.springframework.web.multipart.MultipartFile;

import java.io.InputStream;
import java.time.LocalDate;
import java.time.format.DateTimeFormatter;

/**
 * OSS 对象存储服务
 * 基于 x-file-storage，支持 MinIO / 阿里云 OSS / 腾讯云 COS
 *
 * @author xinghuiTec
 */
@Slf4j
public class OssService {

    private final FileStorageService fileStorageService;
    private final OssRuntimeSettings runtime;
    public OssService(FileStorageService storage) { this(storage,null); }
    public OssService(FileStorageService storage,OssRuntimeSettings runtime) { this.fileStorageService=storage;this.runtime=runtime; }

    /** 上传文件，null 或空字符串使用默认平台 */
    public FileInfo upload(MultipartFile file, String platform) {
        return buildUpload(fileStorageService.of(file), platform)
                .setSaveFilename(buildFilename(file.getOriginalFilename()))
                .setContentType(file.getContentType())
                .upload();
    }

    public FileInfo upload(MultipartFile file) {
        return upload(file, null);
    }

    /** 上传字节数组 */
    public FileInfo upload(byte[] data, String filename, String contentType, String platform) {
        return buildUpload(fileStorageService.of(data), platform)
                .setSaveFilename(buildFilename(filename))
                .setContentType(contentType)
                .upload();
    }

    /** 上传输入流 */
    public FileInfo upload(InputStream inputStream, String filename, String contentType, String platform) {
        return buildUpload(fileStorageService.of(inputStream), platform)
                .setSaveFilename(buildFilename(filename))
                .setContentType(contentType)
                .upload();
    }

    /** 流式下载文件到 HttpServletResponse（按 url 回查记录获取原始文件名） */
    public void download(String url, jakarta.servlet.http.HttpServletResponse response) {
        org.dromara.x.file.storage.core.FileInfo fileInfo = getFileInfo(url);
        if (fileInfo == null) {
            throw new RuntimeException("文件不存在");
        }
        download(url, fileInfo.getOriginalFilename(), response);
    }

    /** 流式下载，文件名由调用方提供（调用方已持有记录时避免再按 url 回查一次） */
    public void download(String url, String originalFilename, jakarta.servlet.http.HttpServletResponse response) {
        try {
            FileInfo fileInfo = getFileInfo(url);
            if (fileInfo == null) {
                throw new IllegalStateException("文件记录不存在");
            }
            fileStorageService.download(fileInfo).inputStream(in -> {
                try {
                    // 对象已打开后才写响应头；对象不存在时可返回正常的 JSON 错误状态。
                    response.setContentType("application/octet-stream");
                    response.setHeader("Content-Disposition",
                            "attachment; filename=" + java.net.URLEncoder.encode(originalFilename, java.nio.charset.StandardCharsets.UTF_8));
                    if (fileInfo.getSize() != null) {
                        response.setContentLengthLong(fileInfo.getSize());
                    }
                    java.io.OutputStream out = response.getOutputStream();
                    IoUtil.copy(in, out);
                    out.flush();
                } catch (java.io.IOException e) {
                    throw new RuntimeException(e);
                }
            });
        } catch (Exception e) {
            log.error("文件下载失败", e);
            throw new RuntimeException("文件下载失败: " + e.getMessage());
        }
    }

    public boolean delete(String url) {
        FileInfo fileInfo = getFileInfo(url);
        if (fileInfo == null || !fileStorageService.exists(fileInfo)) {
            return false;
        }
        // 框架 delete(url) 会在 MinIO 接受删除请求后立即删记录；MinIO 对
        // 不存在的对象也返回成功。先删并验证对象，再删数据库记录。
        if (!fileStorageService.getFileStorageVerify(fileInfo).delete(fileInfo)
                || fileStorageService.exists(fileInfo)) {
            return false;
        }
        return fileStorageService.getFileRecorder().delete(url);
    }

    public boolean exists(String url) {
        FileInfo info=getFileInfo(url);
        return info!=null && fileStorageService.exists(info);
    }

    public FileInfo getFileInfo(String url) {
        FileInfo info=fileStorageService.getFileInfoByUrl(url);
        if(info!=null && runtime!=null) runtime.prepareRead(info);
        return info;
    }

    /** 构建上传预处理：只有非空 platform 才设置，null 则使用默认平台 */
    private UploadPretreatment buildUpload(UploadPretreatment up, String platform) {
        up.setPath(buildPath());
        if(runtime!=null) platform=runtime.uploadPlatform(platform);
        if (StrUtil.isNotBlank(platform)) {
            up.setPlatform(platform);
        }
        return up;
    }

    private String buildPath() {
        return LocalDate.now().format(DateTimeFormatter.ofPattern("yyyy/MM/dd")) + "/";
    }

    private String buildFilename(String originalFilename) {
        String ext = "";
        if (originalFilename != null && originalFilename.contains(".")) {
            ext = originalFilename.substring(originalFilename.lastIndexOf("."));
        }
        return IdUtil.fastSimpleUUID() + ext;
    }
}
