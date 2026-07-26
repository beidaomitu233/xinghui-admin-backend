package com.xinghuiTec.sms.core;

import com.xinghuiTec.constants.TenantConstants;
import com.xinghuiTec.utils.RedisCacheUtils;
import com.xinghuiTec.utils.SpringUtils;
import org.dromara.sms4j.api.dao.SmsDao;

import java.util.concurrent.TimeUnit;

/**
 * sms4j 缓存实现（基于 Redis）
 * 用于短信拦截计数、重试缓存等
 *
 * @author xinghuiTec
 */
public class PlusSmsDao implements SmsDao {

    private static final String SMS_PREFIX = TenantConstants.GLOBAL_REDIS_KEY + "sms:";

    @Override
    public void set(String key, Object value, long cacheTime) {
        getRedis().setCacheObject(SMS_PREFIX + key, value, Math.toIntExact(cacheTime), TimeUnit.SECONDS);
    }

    @Override
    public void set(String key, Object value) {
        getRedis().setCacheObject(SMS_PREFIX + key, value);
    }

    @Override
    public Object get(String key) {
        return getRedis().getCacheObject(SMS_PREFIX + key);
    }

    @Override
    public Object remove(String key) {
        Object val = getRedis().getCacheObject(SMS_PREFIX + key);
        getRedis().deleteObject(SMS_PREFIX + key);
        return val;
    }

    @Override
    public void clean() {
        getRedis().deleteObject(SMS_PREFIX + "*");
    }

    private RedisCacheUtils getRedis() {
        return SpringUtils.getBean(RedisCacheUtils.class);
    }
}
