package com.xinghuiTec.oss;

import cn.hutool.core.io.IoUtil;
import cn.hutool.core.util.IdUtil;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.dromara.x.file.storage.core.FileInfo;
import org.dromara.x.file.storage.core.FileStorageService;
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

    /**
     * 上传文件，返回文件信息
     *
     * @param file     上传的文件
     * @param platform 存储平台标识（如 minio-1, aliyun-oss-1），null 使用默认平台
     */
    public FileInfo upload(MultipartFile file, String platform) {
        FileInfo info = fileStorageService.of(file)
                .setPlatform(platform)
                .setPath(buildPath())
                .setSaveFilename(buildFilename(file.getOriginalFilename()))
                .setContentType(file.getContentType())
                .upload();
        log.info("OSS上传成功: platform={}, url={}, size={}", info.getPlatform(), info.getUrl(), info.getSize());
        return info;
    }

    /**
     * 上传文件（使用默认平台）
     */
    public FileInfo upload(MultipartFile file) {
        return upload(file, null);
    }

    /**
     * 上传字节数组
     */
    public FileInfo upload(byte[] data, String filename, String contentType, String platform) {
        FileInfo info = fileStorageService.of(data)
                .setPlatform(platform)
                .setPath(buildPath())
                .setSaveFilename(buildFilename(filename))
                .setContentType(contentType)
                .upload();
        log.info("OSS上传成功: platform={}, url={}", info.getPlatform(), info.getUrl());
        return info;
    }

    /**
     * 上传输入流
     */
    public FileInfo upload(InputStream inputStream, String filename, String contentType, String platform) {
        return upload(IoUtil.readBytes(inputStream), filename, contentType, platform);
    }

    /**
     * 删除文件
     */
    public boolean delete(String url) {
        return fileStorageService.delete(url);
    }

    /**
     * 判断文件是否存在
     */
    public boolean exists(String url) {
        return fileStorageService.exists(url);
    }

    /**
     * 获取文件信息
     */
    public FileInfo getFileInfo(String url) {
        return fileStorageService.getFileInfoByUrl(url);
    }

    /**
     * 构建存储路径: upload/yyyy/MM/dd/
     */
    private String buildPath() {
        return "upload/" + LocalDate.now().format(DateTimeFormatter.ofPattern("yyyy/MM/dd")) + "/";
    }

    /**
     * 构建存储文件名: uuid.扩展名
     */
    private String buildFilename(String originalFilename) {
        String ext = "";
        if (originalFilename != null && originalFilename.contains(".")) {
            ext = originalFilename.substring(originalFilename.lastIndexOf("."));
        }
        return IdUtil.fastSimpleUUID() + ext;
    }
}
