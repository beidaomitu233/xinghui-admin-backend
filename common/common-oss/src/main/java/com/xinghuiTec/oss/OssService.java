package com.xinghuiTec.oss;

import cn.hutool.core.io.IoUtil;
import cn.hutool.core.util.IdUtil;
import cn.hutool.core.util.StrUtil;
import lombok.RequiredArgsConstructor;
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
@RequiredArgsConstructor
public class OssService {

    private final FileStorageService fileStorageService;

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

    /** 流式下载文件到 HttpServletResponse */
    public void download(String url, jakarta.servlet.http.HttpServletResponse response) {
        try {
            org.dromara.x.file.storage.core.FileInfo fileInfo = getFileInfo(url);
            if (fileInfo == null) {
                throw new RuntimeException("文件不存在");
            }
            response.setContentType("application/octet-stream");
            response.setHeader("Content-Disposition",
                    "attachment; filename=" + java.net.URLEncoder.encode(fileInfo.getOriginalFilename(), java.nio.charset.StandardCharsets.UTF_8));
            fileStorageService.download(url).inputStream(in -> {
                IoUtil.copy(in, response.getOutputStream());
                response.getOutputStream().flush();
            });
        } catch (Exception e) {
            log.error("文件下载失败", e);
            throw new RuntimeException("文件下载失败: " + e.getMessage());
        }
    }

    public boolean delete(String url) {
        return fileStorageService.delete(url);
    }

    public boolean exists(String url) {
        return fileStorageService.exists(url);
    }

    public FileInfo getFileInfo(String url) {
        return fileStorageService.getFileInfoByUrl(url);
    }

    /** 构建上传预处理：只有非空 platform 才设置，null 则使用默认平台 */
    private UploadPretreatment buildUpload(UploadPretreatment up, String platform) {
        up.setPath(buildPath());
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
