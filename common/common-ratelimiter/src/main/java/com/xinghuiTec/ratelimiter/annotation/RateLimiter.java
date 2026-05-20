package com.xinghuiTec.ratelimiter.annotation;

import com.xinghuiTec.ratelimiter.enums.LimitType;

import java.lang.annotation.*;

/**
 * 限流注解
 *
 * <pre>
 * // 示例1: 全局限流，60秒内最多100次
 * &#64;RateLimiter(time = 60, count = 100)
 *
 * // 示例2: 按IP限流，60秒内每个IP最多1次（短信验证码场景）
 * &#64;RateLimiter(key = "#phone", time = 60, count = 1, limitType = LimitType.IP)
 *
 * // 示例3: 自定义key限流，60秒内同一手机号最多1次
 * &#64;RateLimiter(key = "#phonenumber", time = 60, count = 1)
 * </pre>
 *
 * @author xinghuiTec
 */
@Target(ElementType.METHOD)
@Retention(RetentionPolicy.RUNTIME)
@Documented
public @interface RateLimiter {

    /** 限流 key（支持 SpEL 表达式获取方法参数，如 #phone） */
    String key() default "";

    /** 限流时间窗口（秒） */
    int time() default 60;

    /** 时间窗口内允许的最大请求次数 */
    int count() default 100;

    /** 限流类型 */
    LimitType limitType() default LimitType.DEFAULT;

    /** 触发限流时的提示消息 */
    String message() default "访问过于频繁，请稍候再试";
}
