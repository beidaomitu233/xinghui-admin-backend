package com.xinghuiTec.quartz.domain;

import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import com.xinghuiTec.domain.entity.BaseEntity;
import com.xinghuiTec.domain.entity.TenantEntity;
import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.EqualsAndHashCode;
import lombok.NoArgsConstructor;

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
    private String invokeTarget;
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
