package com.xinghuiTec.config.tenant;

import com.baomidou.mybatisplus.extension.plugins.inner.TenantLineInnerInterceptor;
import com.xinghuiTec.config.TenantProperties;
import org.springframework.boot.autoconfigure.condition.ConditionalOnClass;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/**
 * 租户自动配置类
 * 通过 @ConditionalOnProperty 控制租户功能是否启用
 *
 * @author xinghuiTec
 */
@Configuration
@EnableConfigurationProperties(TenantProperties.class)
@ConditionalOnProperty(value = "tenant.enable", havingValue = "true")
public class TenantConfig {

    /**
     * MyBatis-Plus 租户拦截器
     * 自动在 SQL 中添加 WHERE tenant_id = 'xxx' 条件
     */
    @Bean
    @ConditionalOnClass(TenantLineInnerInterceptor.class)
    public TenantLineInnerInterceptor tenantLineInnerInterceptor(TenantProperties tenantProperties) {
        return new TenantLineInnerInterceptor(new PlusTenantLineHandler(tenantProperties));
    }
}
