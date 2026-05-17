package com.xinghuiTec.config.mybatis;

import com.baomidou.mybatisplus.annotation.DbType;
import com.baomidou.mybatisplus.extension.plugins.MybatisPlusInterceptor;
import com.baomidou.mybatisplus.extension.plugins.inner.PaginationInnerInterceptor;
import com.baomidou.mybatisplus.extension.plugins.inner.TenantLineInnerInterceptor;
import com.xinghuiTec.config.datascope.DataPermissionPointcutAdvisor;
import com.xinghuiTec.config.datascope.PlusDataPermissionInterceptor;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.beans.factory.config.BeanDefinition;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Role;

@Configuration
public class mybatisConfig {

    /**
     * MyBatis-Plus 拦截器链
     * 顺序：租户插件(第一) → 数据权限(第二) → 分页(第三)
     */
    @Bean
    public MybatisPlusInterceptor mybatisPlusInterceptor(
            ObjectProvider<TenantLineInnerInterceptor> tenantProvider) {
        MybatisPlusInterceptor interceptor = new MybatisPlusInterceptor();

        // 1. 多租户插件（必须第一位）
        tenantProvider.ifAvailable(interceptor::addInnerInterceptor);

        // 2. 数据权限插件
        interceptor.addInnerInterceptor(new PlusDataPermissionInterceptor());

        // 3. 分页插件
        interceptor.addInnerInterceptor(new PaginationInnerInterceptor(DbType.MYSQL));

        return interceptor;
    }

    /**
     * 数据权限 AOP 切面
     * 在 Mapper 方法执行前捕获 @DataPermission 注解并存入 DataPermissionHelper 上下文
     */
    @Bean
    @Role(BeanDefinition.ROLE_INFRASTRUCTURE)
    public DataPermissionPointcutAdvisor dataPermissionPointcutAdvisor() {
        return new DataPermissionPointcutAdvisor();
    }
}
