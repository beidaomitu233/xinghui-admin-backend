package com.xinghuiTec.social.utils;

import com.xinghuiTec.constants.TenantConstants;
import com.xinghuiTec.utils.RedisCacheUtils;
import com.xinghuiTec.utils.SpringUtils;
import me.zhyd.oauth.cache.AuthStateCache;

import java.util.concurrent.TimeUnit;

/**
 * JustAuth State 参数 Redis 缓存
 * 防止 CSRF 攻击，State 3 分钟过期
 *
 * @author xinghuiTec
 */
public class AuthRedisStateCache implements AuthStateCache {

    private static final String PREFIX = TenantConstants.GLOBAL_REDIS_KEY + "social_auth:";

    @Override
    public void cache(String key, String value) {
        getRedis().setCacheObject(PREFIX + key, value, 3, TimeUnit.MINUTES);
    }

    @Override
    public void cache(String key, String value, long timeout) {
        getRedis().setCacheObject(PREFIX + key, value, Math.toIntExact(timeout), TimeUnit.MILLISECONDS);
    }

    @Override
    public String get(String key) {
        return getRedis().getCacheObject(PREFIX + key);
    }

    @Override
    public boolean containsKey(String key) {
        return getRedis().hasKey(PREFIX + key);
    }

    private RedisCacheUtils getRedis() {
        return SpringUtils.getBean(RedisCacheUtils.class);
    }
}
