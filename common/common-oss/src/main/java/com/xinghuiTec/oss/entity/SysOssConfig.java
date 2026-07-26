package com.xinghuiTec.oss.entity;

import com.baomidou.mybatisplus.annotation.*;
import com.xinghuiTec.domain.entity.BaseEntity;
import lombok.Data;
import lombok.EqualsAndHashCode;

/**
 * OSS 存储配置（支持运行时切换）
 *
 * @author xinghuiTec
 */
@Data
@EqualsAndHashCode(callSuper = true)
@TableName("sys_oss_config")
public class SysOssConfig extends BaseEntity {

    @TableId(type = IdType.AUTO)
    private Long ossConfigId;

    /** 配置标识（minio-1 / aliyun-oss-1） */
    private String configKey;

    /** 访问密钥 */
    private String accessKey;

    /** 密钥 */
    private String secretKey;

    /** 存储桶名称 */
    private String bucketName;

    /** 访问端点 */
    private String endpoint;

    /** 自定义域名 */
    private String domain;

    /** 是否 HTTPS (Y/N) */
    private String isHttps;

    /** 桶权限 (0=私有 1=公开) */
    private String accessPolicy;

    /** 备注 */
    private String remark;

    /** 状态 (0=正常 1=停用) */
    private String status;
}
