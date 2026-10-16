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
    String USER_INFO_PREFIX = "admin:userinfo:v210:";

    Integer USER_INFO_TTL_SEC = 1800;

    // V180/V210 separate AI navigation and actions; existing sessions fetch fresh grants.
    String USER_ROUTER_PREFIX = "admin:user:router:v210:";

    Integer USER_ROUTER_TTL_SEC = 1800;
}
