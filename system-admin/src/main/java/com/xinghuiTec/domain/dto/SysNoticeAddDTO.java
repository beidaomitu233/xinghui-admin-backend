package com.xinghuiTec.domain.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import lombok.Data;

/**
 * 通知公告新增/编辑DTO
 * 
 * @author beidoa23
 * @since 2026-01-24
 */
@Data
public class SysNoticeAddDTO {

    /**
     * 公告ID（编辑时必填）
     */
    private Long noticeId;

    /**
     * 公告标题
     */
    @NotBlank(message = "公告标题不能为空")
    private String noticeTitle;

    /**
     * 公告类型（1通知卡片 2强制弹窗）
     */
    @NotNull(message = "公告类型不能为空")
    private Integer noticeType;

    /**
     * 公告内容（HTML富文本）
     */
    private String noticeContent;

    /**
     * 状态（0关闭 1正常）
     */
    private Integer status = 1;

    /**
     * 备注
     */
    private String remark;

    /**
     * 生效开始时间
     */
    private String startTime;

    /**
     * 生效结束时间
     */
    private String endTime;
}
