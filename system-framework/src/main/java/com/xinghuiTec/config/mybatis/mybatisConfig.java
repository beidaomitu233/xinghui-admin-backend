package com.xinghuiTec.config.mybatis;

import com.baomidou.mybatisplus.annotation.DbType;
import com.baomidou.mybatisplus.extension.plugins.MybatisPlusInterceptor;
import com.baomidou.mybatisplus.extension.plugins.inner.PaginationInnerInterceptor;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

@Configuration
public class mybatisConfig {
    /**
     * 配置Mybatis-Plus拦截器
     *
     * @return MybatisPlusInterceptor实例，用于配置Mybatis-Plus的各种拦截器
     */
    @Bean
    public MybatisPlusInterceptor mybatisPlusInterceptor(){
        // 创建MybatisPlusInterceptor实例
        MybatisPlusInterceptor mybatisPlusInterceptor = new MybatisPlusInterceptor();

        // 添加内置的分页拦截器，用于支持分页查询
        // 参数DbType.MYSQL指定数据库类型为MySQL
        mybatisPlusInterceptor.addInnerInterceptor(new PaginationInnerInterceptor(DbType.MYSQL));

        // 返回配置好的MybatisPlusInterceptor实例
        return mybatisPlusInterceptor;
    }
}
