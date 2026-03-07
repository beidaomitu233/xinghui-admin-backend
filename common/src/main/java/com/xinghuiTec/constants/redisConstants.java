package com.xinghuiTec.constants;

/**
 * Redis 相关常量类
 * 统一管理 Redis 键前缀、TTL等常量
 */
public class redisConstants {

    /**
     * 管理员登录 Redis 前缀
     */
    public static final String ADMIN_LOGIN_PREFIX = "admin:login:";

    /**
     * 管理员登录验证码 TTL (秒)
     */
    public static final Integer ADMIN_LOGIN_CAPTCHA_TTL_SEC = 120;

    /**
     * 用户登录信息 Redis 前缀
     * 格式: login:{userId}
     */
    public static final String USER_LOGIN_PREFIX = "login:";

    /**
     * 用户信息缓存 Redis 前缀
     * 格式: userinfo:{userId}
     */
    public static final String USER_INFO_PREFIX = "admin:userinfo:";

    /**
     * 用户信息缓存 TTL (秒) - 30分钟
     */
    public static final Integer USER_INFO_TTL_SEC = 1800;

    /**
     * 用户路由缓存 Redis 前缀
     * 格式: userrouter:{userId}
     */
    public static final String USER_ROUTER_PREFIX = "admin:user:router:";

    /**
     * 用户路由缓存 TTL (秒) - 30分钟
     */
    public static final Integer USER_ROUTER_TTL_SEC = 1800;
}
