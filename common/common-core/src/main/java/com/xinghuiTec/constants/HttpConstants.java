package com.xinghuiTec.constants;

/**
 * HTTP 相关常量类
 * 统一管理 HTTP 请求头、URL 路径等常量
 */
public class HttpConstants {

    /**
     * HTTP请求头 - Authorization
     * 用于携带JWT令牌的请求头名称
     */
    public static final String HEADER_AUTHORIZATION = "Authorization";

    /**
     * 登录接口路径
     */
    public static final String PATH_LOGIN = "/user/login";

    /**
     * 根路径
     */
    public static final String PATH_ROOT = "/";

    /**
     * 验证码接口路径
     */
    public static final String PATH_CAPTCHA = "/captcha";
}
