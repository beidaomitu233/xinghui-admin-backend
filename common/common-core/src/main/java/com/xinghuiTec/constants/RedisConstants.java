package com.xinghuiTec.constants;

/**
 * Redis 常量
 *
 * @author xinghuiTec
 */
public interface RedisConstants {

    String ADMIN_LOGIN_PREFIX = "admin:login:";

    Integer ADMIN_LOGIN_CAPTCHA_TTL_SEC = 120;

    // Menu/permission schema revision: upgrades must not read pre-cleanup caches.
    String USER_INFO_PREFIX = "admin:userinfo:v227:";

    Integer USER_INFO_TTL_SEC = 1800;

    // V227 restores the independent log-list action; do not reuse older permission/navigation caches.
    String USER_ROUTER_PREFIX = "admin:user:router:v227:";

    Integer USER_ROUTER_TTL_SEC = 1800;
}
