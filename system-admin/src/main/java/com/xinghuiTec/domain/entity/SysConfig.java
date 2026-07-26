package com.xinghuiTec.domain.entity;

import java.io.Serializable;
import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.EqualsAndHashCode;
import lombok.NoArgsConstructor;
import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;

/**
 * 参数配置表(SysConfig)表实体类
 *
 * @author beidoa23
 * @since 2025-12-25 19:33:06
 */
@SuppressWarnings("serial")
@Data
@EqualsAndHashCode(callSuper = true)
@AllArgsConstructor
@NoArgsConstructor
@TableName("sys_config")
public class SysConfig extends TenantEntity {
    // 参数主键（表主键为 int AUTO_INCREMENT，必须显式指定 AUTO，
    // 否则命中全局 ASSIGN_ID 雪花策略，64 位 ID 截断为 int 产生负数/碰撞主键）
    @TableId(type = IdType.AUTO)
    private Integer configId;

    // 参数名称
    private String configName;
    // 参数键名(sys.account.captchaOn)
    private String configKey;
    // 参数键值(true/false)
    private String configValue;
    // 系统内置（Y是 N否）
    private String configType;
    // 备注
    private String remark;

}
