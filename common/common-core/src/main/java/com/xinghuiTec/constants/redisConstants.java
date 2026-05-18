package com.xinghuiTec.constants;

/**
 * Redis 常量
 *
 * @author xinghuiTec
 */
public interface RedisConstants {

    String ADMIN_LOGIN_PREFIX = "admin:login:";

    Integer ADMIN_LOGIN_CAPTCHA_TTL_SEC = 120;

    String USER_INFO_PREFIX = "admin:userinfo:";

    Integer USER_INFO_TTL_SEC = 1800;

    String USER_ROUTER_PREFIX = "admin:user:router:";

    Integer USER_ROUTER_TTL_SEC = 1800;
}
