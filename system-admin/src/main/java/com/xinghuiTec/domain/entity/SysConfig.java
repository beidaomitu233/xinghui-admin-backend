package com.xinghuiTec.domain.entity;

import java.io.Serializable;
import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.EqualsAndHashCode;
import lombok.NoArgsConstructor;
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
@EqualsAndHashCode(callSuper = false)
@AllArgsConstructor
@NoArgsConstructor
@TableName("sys_config")
public class SysConfig extends BaseEntity {
    // 参数主键
    @TableId
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
