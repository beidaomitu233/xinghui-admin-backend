package com.xinghuiTec.domain.entity;

import java.io.Serializable;
import java.util.Date;

import com.fasterxml.jackson.annotation.JsonFormat;
import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.EqualsAndHashCode;
import lombok.NoArgsConstructor;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;

/**
 * 通知公告表(SysNotice)表实体类
 *
 * @author beidoa23
 * @since 2025-12-25 19:33:19
 */
@SuppressWarnings("serial")
@Data
@EqualsAndHashCode(callSuper = false)
@AllArgsConstructor
@NoArgsConstructor
@TableName("sys_notice")
public class SysNotice implements Serializable {
    @TableId
    // 公告ID
    private Integer noticeId;

    // 公告标题
    private String noticeTitle;
    // 公告类型（1通知卡片 2强弹窗）
    private String noticeType;
    // 公告内容(HTML/Text) - 数据库为LONGBLOB，需要转换
    private String noticeContent;
    // 公告状态（0正常 1关闭）
    private String status;
    // 创建者
    private String createBy;

    // 创建时间
    @JsonFormat(pattern = "yyyy-MM-dd HH:mm:ss", timezone = "GMT+8")
    private Date createTime;

    // 生效开始时间
    @JsonFormat(pattern = "yyyy-MM-dd HH:mm:ss", timezone = "GMT+8")
    private Date startTime;

    // 生效结束时间
    @JsonFormat(pattern = "yyyy-MM-dd HH:mm:ss", timezone = "GMT+8")
    private Date endTime;

}
