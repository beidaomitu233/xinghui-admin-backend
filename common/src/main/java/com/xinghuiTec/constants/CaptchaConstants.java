package com.xinghuiTec.constants;

/**
 * 验证码相关常量类
 * 统一管理验证码类型、Redis键前缀等常量
 */
public class CaptchaConstants {

    /**
     * 验证码 Redis 键前缀
     * 格式: captcha:{uuid}
     */
    public static final String REDIS_KEY_PREFIX = "captcha:";

    /**
     * 验证码类型 - 数字
     * 生成纯数字验证码
     */
    public static final String TYPE_NUMERIC = "numeric";

    /**
     * 验证码类型 - 算术运算
     * 生成算术表达式验证码
     */
    public static final String TYPE_ARITHMETIC = "arithmetic";
}
