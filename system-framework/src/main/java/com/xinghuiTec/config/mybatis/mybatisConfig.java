package com.xinghuiTec.config.mybatis;

import com.baomidou.mybatisplus.annotation.DbType;
import com.baomidou.mybatisplus.extension.plugins.MybatisPlusInterceptor;
import com.baomidou.mybatisplus.extension.plugins.inner.PaginationInnerInterceptor;
import com.baomidou.mybatisplus.extension.plugins.inner.TenantLineInnerInterceptor;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

@Configuration
public class mybatisConfig {

    /**
     * 配置Mybatis-Plus拦截器
     * 拦截器顺序：租户插件(第一) → 分页插件
     * 使用 ObjectProvider 延迟获取可选租户拦截器，避免循环依赖
     */
    @Bean
    public MybatisPlusInterceptor mybatisPlusInterceptor(
            ObjectProvider<TenantLineInnerInterceptor> tenantProvider) {
        MybatisPlusInterceptor interceptor = new MybatisPlusInterceptor();

        // 多租户插件 必须放到第一位
        // 当 tenant.enable=true 时 TenantConfig 创建此 Bean 后自动注入
        tenantProvider.ifAvailable(interceptor::addInnerInterceptor);

        // 分页插件
        interceptor.addInnerInterceptor(new PaginationInnerInterceptor(DbType.MYSQL));

        return interceptor;
    }
}
