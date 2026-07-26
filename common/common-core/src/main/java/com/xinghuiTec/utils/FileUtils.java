package com.xinghuiTec.utils;

import lombok.extern.slf4j.Slf4j;
import org.springframework.web.multipart.MultipartFile;

import java.io.IOException;
import java.io.InputStream;
import java.security.MessageDigest;
import java.util.UUID;

/**
 * 文件工具类
 * 
 * @author beidoa23
 * @since 2026-01-23
 */
@Slf4j
public class FileUtils {

    /**
     * 生成唯一文件名（UUID + 扩展名）
     */
    public static String generateUniqueFileName(String originalFileName) {
        String ext = getFileExtension(originalFileName);
        return UUID.randomUUID().toString().replace("-", "") + ext;
    }

    /**
     * 获取文件扩展名（包含点）
     */
    public static String getFileExtension(String fileName) {
        if (fileName == null || fileName.isEmpty()) {
            return "";
        }
        int dotIndex = fileName.lastIndexOf(".");
        return dotIndex > 0 ? fileName.substring(dotIndex) : "";
    }

    /**
     * 获取文件扩展名（不包含点）
     */
    public static String getFileExtensionWithoutDot(String fileName) {
        String ext = getFileExtension(fileName);
        return ext.startsWith(".") ? ext.substring(1) : ext;
    }

    /**
     * 判断文件类型
     * 
     * @param fileName 文件名或MIME类型
     * @return 文件类型：image/video/audio/document/other
     */
    public static String getFileType(String fileName) {
        String ext = getFileExtensionWithoutDot(fileName).toLowerCase();

        // 图片类型
        if (ext.matches("jpg|jpeg|png|gif|bmp|webp|svg|ico")) {
            return "image";
        }
        // 视频类型
        if (ext.matches("mp4|avi|mov|wmv|flv|mkv|webm")) {
            return "video";
        }
        // 音频类型
        if (ext.matches("mp3|wav|flac|aac|ogg|m4a")) {
            return "audio";
        }
        // 文档类型
        if (ext.matches("pdf|doc|docx|xls|xlsx|ppt|pptx|txt|md")) {
            return "document";
        }
        // 压缩文件
        if (ext.matches("zip|rar|7z|tar|gz")) {
            return "archive";
        }

        return "other";
    }

    /**
     * 判断是否为图片文件
     */
    public static boolean isImage(String fileName) {
        return "image".equals(getFileType(fileName));
    }

    /**
     * 计算文件MD5值
     */
    public static String calculateMD5(MultipartFile file) {
        try {
            return calculateMD5(file.getInputStream());
        } catch (IOException e) {
            log.error("计算文件MD5失败", e);
            return null;
        }
    }

    /**
     * 计算输入流MD5值
     */
    public static String calculateMD5(InputStream inputStream) {
        try {
            MessageDigest md = MessageDigest.getInstance("MD5");
            byte[] buffer = new byte[8192];
            int bytesRead;

            while ((bytesRead = inputStream.read(buffer)) != -1) {
                md.update(buffer, 0, bytesRead);
            }

            byte[] digest = md.digest();
            StringBuilder sb = new StringBuilder();
            for (byte b : digest) {
                sb.append(String.format("%02x", b));
            }

            return sb.toString();
        } catch (Exception e) {
            log.error("计算MD5失败", e);
            return null;
        }
    }

    /**
     * 格式化文件大小
     */
    public static String formatFileSize(long size) {
        if (size < 1024) {
            return size + " B";
        } else if (size < 1024 * 1024) {
            return String.format("%.2f KB", size / 1024.0);
        } else if (size < 1024 * 1024 * 1024) {
            return String.format("%.2f MB", size / (1024.0 * 1024));
        } else {
            return String.format("%.2f GB", size / (1024.0 * 1024 * 1024));
        }
    }

    /**
     * 根据日期生成文件路径（用于组织文件目录）
     * 格式：yyyy/MM/dd/
     */
    public static String generateDatePath() {
        java.time.LocalDate date = java.time.LocalDate.now();
        return String.format("%d/%02d/%02d/",
                date.getYear(), date.getMonthValue(), date.getDayOfMonth());
    }
}
