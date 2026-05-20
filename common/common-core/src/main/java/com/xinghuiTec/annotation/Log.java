package com.xinghuiTec.annotation;

import com.xinghuiTec.emues.BusinessType;

import java.lang.annotation.*;

/**
 * 自定义操作日志记录注解
 * 用于标记需要记录操作日志的方法
 * 
 * @author beidoa23
 * @since 2026-01-23
 */
@Target({ ElementType.METHOD })
@Retention(RetentionPolicy.RUNTIME)
@Documented
public @interface Log {

    /**
     * 模块标题
     * 例如: "用户管理"、"角色管理"、"菜单管理"
     */
    String title() default "";

    /**
     * 业务操作类型
     * 默认为 OTHER(0) - 其他操作
     */
    BusinessType businessType() default BusinessType.OTHER;
}
