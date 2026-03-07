package com.xinghuiTec.domain.dto;

import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.Pattern;
import lombok.Data;

/**
 * 通知公告查询DTO
 * 
 * @author beidoa23
 * @since 2026-01-24
 */
@Data
public class SysNoticeQueryDTO {

    /**
     * 公告标题（模糊查询）
     */
    private String noticeTitle;

    /**
     * 公告类型（1通知卡片 2强制弹窗）
     */
    private Integer noticeType;

    /**
     * 状态（0正常 1关闭）
     */
    private String status;

    /**
     * 创建者
     */
    private String createBy;

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
