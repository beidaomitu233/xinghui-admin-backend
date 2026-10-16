package com.xinghuiTec.quartz.domain;

import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import com.baomidou.mybatisplus.annotation.TableField;
import com.baomidou.mybatisplus.annotation.FieldStrategy;
import com.xinghuiTec.domain.entity.TenantEntity;
import com.fasterxml.jackson.annotation.JsonIgnore;
import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.EqualsAndHashCode;
import lombok.NoArgsConstructor;

import java.time.LocalDateTime;

/**
 * 定时任务调度表(SysJob)表实体类
 *
 * @author beidoa23
 * @since 2026-01-25
 */
@SuppressWarnings("serial")
@Data
@EqualsAndHashCode(callSuper = true)
@AllArgsConstructor
@NoArgsConstructor
@TableName("sys_job")
public class SysJob extends TenantEntity {
    @TableId
    // 任务ID
    private Long jobId;
    // 任务名称
    private String jobName;
    // 任务组名
    private String jobGroup;
    // 调用目标字符串
    @JsonIgnore
    private String invokeTarget;
    // 显式注册的处理器键；历史任务为空时不可执行
    private String handlerKey;
    // 处理器参数，仅允许 JSON 对象
    private String handlerParams;
    // IANA 时区 ID
    private String timeZone;
    // Next scheduled instant stored as UTC wall time in DATETIME(3).
    @TableField(updateStrategy = FieldStrategy.ALWAYS)
    private LocalDateTime nextFireTime;
    // Incremented on every managed change; invalidates stale claims.
    private Long revision;
    // cron执行表达式
    private String cronExpression;
    // 计划执行错误策略（1立即执行 2执行一次 3放弃执行）
    private String misfirePolicy;
    // 是否并发执行（0允许 1禁止）
    private String concurrent;
    // 状态（0正常 1暂停）
    private String status;
    // 备注信息
    private String remark;
}
