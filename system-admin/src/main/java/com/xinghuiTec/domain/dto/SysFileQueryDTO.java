package com.xinghuiTec.domain.dto;

import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.Pattern;
import lombok.Data;

import java.util.Date;

/**
 * 文件查询 DTO
 * 
 * @author beidoa23
 * @since 2026-01-23
 */
@Data
public class SysFileQueryDTO {

    /**
     * 原始文件名（模糊查询）
     */
    private String originalName;

    /**
     * 文件类型（image/video/document/audio/other）
     */
    private String fileType;

    /**
     * 存储类型（local/aliyun_oss/minio）
     */
    private String storageType;

    /**
     * 上传用户
     */
    private String uploadUser;

    /**
     * 上传时间范围 - 开始
     */
    private Date uploadTimeStart;

    /**
     * 上传时间范围 - 结束
     */
    private Date uploadTimeEnd;

    /**
     * 当前页码
     */
    @Min(value = 1, message = "页码不能小于1")
    private Integer pageNum = 1;

    /**
     * 每页条数
     */
    @Min(value = 1, message = "每页条数不能小于1")
    @Max(value = 100, message = "每页条数不能超过100")
    private Integer pageSize = 10;

    /**
     * 排序方式
     */
    @Pattern(regexp = "^(asc|desc)$", message = "排序方式只能是 asc 或 desc")
    private String order = "desc";
}
