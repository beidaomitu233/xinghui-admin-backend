package com.xinghuiTec.oss.entity;

import com.baomidou.mybatisplus.annotation.*;
import com.fasterxml.jackson.annotation.JsonIgnore;
import com.fasterxml.jackson.databind.annotation.JsonSerialize;
import com.fasterxml.jackson.databind.ser.std.ToStringSerializer;
import com.xinghuiTec.domain.entity.BaseEntity;
import lombok.Data;
import lombok.EqualsAndHashCode;

/**
 * OSS 文件记录
 *
 * @author xinghuiTec
 */
@Data
@EqualsAndHashCode(callSuper = true)
@TableName("sys_oss")
public class SysOss extends BaseEntity {

    @TableId(type = IdType.AUTO)
    @JsonSerialize(using = ToStringSerializer.class)
    private Long ossId;

    /** 原始文件名 */
    private String originalName;

    /** 存储文件名 */
    private String fileName;

    /** 文件后缀 */
    private String fileSuffix;

    /** 访问 URL */
    @JsonIgnore
    private String url;

    /** 文件大小(字节) */
    private Long size;

    /** 存储平台（minio-1 / aliyun-oss-1 / tencent-cos-1） */
    private String platform;

    /** 上传时绑定的租户；与创建人共同限定文件访问范围。 */
    private String tenantId;
}
