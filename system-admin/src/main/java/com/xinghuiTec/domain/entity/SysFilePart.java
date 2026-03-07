package com.xinghuiTec.domain.entity;

import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;
import lombok.EqualsAndHashCode;

/**
 * 文件分片信息实体类
 * 用于支持文件分片上传
 */
@Data
@EqualsAndHashCode(callSuper = false)
@TableName("sys_file_part")
public class SysFilePart {

    /** ID */
    private String id;

    /** 存储平台 */
    private String platform;

    /** 上传ID */
    private String uploadId;

    /** ETag */
    private String eTag;

    /** 分片号 */
    private Integer partNumber;

    /** 分片大小 */
    private Long partSize;

    /** Hash值 */
    private String hash;

    /** 创建时间 */
    private java.util.Date createTime;
}
