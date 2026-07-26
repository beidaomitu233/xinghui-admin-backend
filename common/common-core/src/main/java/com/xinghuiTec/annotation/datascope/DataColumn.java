package com.xinghuiTec.annotation.datascope;

import java.lang.annotation.*;

/**
 * 数据权限列映射
 * 定义 SQL 模板中占位符与数据库列的映射关系
 *
 * @author xinghuiTec
 */
@Target(ElementType.METHOD)
@Retention(RetentionPolicy.RUNTIME)
@Documented
public @interface DataColumn {

    /** 占位符名称，默认 userId */
    String key() default "userId";

    /** 数据库列名，默认 create_by */
    String value() default "create_by";
}
