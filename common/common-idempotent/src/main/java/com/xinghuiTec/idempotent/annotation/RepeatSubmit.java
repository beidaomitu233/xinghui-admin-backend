package com.xinghuiTec.idempotent.annotation;

import java.lang.annotation.*;

/**
 * 防重提交注解
 * 在指定时间间隔内，相同请求参数和 Token 视为重复提交
 *
 * @author xinghuiTec
 */
@Inherited
@Target(ElementType.METHOD)
@Retention(RetentionPolicy.RUNTIME)
@Documented
public @interface RepeatSubmit {

    /** 间隔时间 */
    int interval() default 5000;

    /** 时间单位 */
    java.util.concurrent.TimeUnit timeUnit() default java.util.concurrent.TimeUnit.MILLISECONDS;

    /** 提示消息 */
    String message() default "不允许重复提交，请稍候再试";
}
