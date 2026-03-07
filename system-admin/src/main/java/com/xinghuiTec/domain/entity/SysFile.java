package com.xinghuiTec.domain.entity;

import java.io.Serializable;
import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.EqualsAndHashCode;
import lombok.NoArgsConstructor;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;

/**
 * 文件记录表(SysFile)表实体类
 *
 * @author beidoa23
 * @since 2025-12-25 19:33:19
 */
@SuppressWarnings("serial")
@Data
@EqualsAndHashCode(callSuper = false)
@AllArgsConstructor
@NoArgsConstructor
@TableName("sys_file")
public class SysFile extends BaseEntity {
    @TableId
    // 文件id
    private Long id;

    // 文件访问地址
    private String url;
    // 文件大小(字节)
    private Long size;
    // 原始文件名
    private String filename;
    // 存储文件名
    private String originalName;
    // 基础存储路径
    private String basePath;
    // 存储路径
    private String path;
    // 文件扩展名
    private String ext;
    // 存储平台(minio-local, aliyun-oss)
    private String platform;
    // 缩略图访问路径
    private String thUrl;
    // 缩略图名称
    private String thFilename;
    // 缩略图大小
    private Long thSize;
    // 文件对象ID(可选)
    private String objectId;

}
