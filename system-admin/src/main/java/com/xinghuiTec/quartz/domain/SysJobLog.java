package com.xinghuiTec.quartz.domain;

import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import com.fasterxml.jackson.annotation.JsonFormat;
import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.EqualsAndHashCode;
import lombok.NoArgsConstructor;

import java.io.Serializable;
import java.util.Date;

/**
 * 定时任务调度日志表(SysJobLog)表实体类
 * 日志表不继承 BaseEntity，避免 is_deleted 字段问题
 *
 * @author beidoa23
 * @since 2026-01-25
 */
@SuppressWarnings("serial")
@Data
@EqualsAndHashCode(callSuper = false)
@AllArgsConstructor
@NoArgsConstructor
@TableName("sys_job_log")
public class SysJobLog implements Serializable {
    @TableId
    // 任务日志ID
    private Long jobLogId;
    // 任务名称
    private String jobName;
    // 任务组名
    private String jobGroup;
    // 调用目标字符串
    private String invokeTarget;
    // 执行状态（0正常 1失败）
    private String status;
    // 异常信息
    private String exceptionInfo;
    // 耗时(毫秒)
    private Long costTime;
    // 创建时间
    @JsonFormat(pattern = "yyyy-MM-dd HH:mm:ss", timezone = "GMT+8")
    private Date createTime;
}
