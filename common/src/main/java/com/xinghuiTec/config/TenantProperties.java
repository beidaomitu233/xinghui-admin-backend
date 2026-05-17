package com.xinghuiTec.config;

import lombok.Data;
import org.springframework.boot.context.properties.ConfigurationProperties;

import java.util.ArrayList;
import java.util.List;

/**
 * 租户配置属性
 *
 * @author xinghuiTec
 */
@Data
@ConfigurationProperties(prefix = "tenant")
public class TenantProperties {

    /**
     * 是否启用多租户
     */
    private Boolean enable;

    /**
     * 排除表列表（不进行租户隔离的表）
     */
    private List<String> excludes = new ArrayList<>();
}
