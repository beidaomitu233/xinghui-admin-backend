package com.xinghuiTec.utils;

import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.data.redis.core.BoundSetOperations;
import org.springframework.data.redis.core.HashOperations;
import org.springframework.data.redis.core.RedisTemplate;
import org.springframework.data.redis.core.ValueOperations;
import org.springframework.stereotype.Component;

import java.util.*;
import java.util.concurrent.TimeUnit;

/**
 * Redis 缓存工具类
 * 统一规则: 所有 get 方法在缓存不存在时返回 null
 * 
 * @author changhui
 */
@Component
@SuppressWarnings({ "unchecked", "rawtypes" })
public class RedisCacheUtils {

    @Autowired
    private RedisTemplate redisTemplate;

    /**
     * 缓存基本的对象，Integer、String、实体类等
     *
     * @param key   缓存的键值
     * @param value 缓存的值
     */
    public <T> void setCacheObject(final String key, final T value) {
        redisTemplate.opsForValue().set(key, value);
    }

    /**
     * 缓存基本的对象，Integer、String、实体类等
     *
     * @param key      缓存的键值
     * @param value    缓存的值
     * @param timeout  时间
     * @param timeUnit 时间颗粒度
     */
    public <T> void setCacheObject(final String key, final T value, final Integer timeout, final TimeUnit timeUnit) {
        redisTemplate.opsForValue().set(key, value, timeout, timeUnit);
    }

    /**
     * 设置有效时间
     *
     * @param key     Redis键
     * @param timeout 超时时间(秒)
     * @return true=设置成功；false=设置失败
     */
    public boolean expire(final String key, final long timeout) {
        return expire(key, timeout, TimeUnit.SECONDS);
    }

    /**
     * 设置有效时间
     *
     * @param key     Redis键
     * @param timeout 超时时间
     * @param unit    时间单位
     * @return true=设置成功；false=设置失败
     */
    public boolean expire(final String key, final long timeout, final TimeUnit unit) {
        Boolean result = redisTemplate.expire(key, timeout, unit);
        return result != null && result;
    }

    /**
     * 获得缓存的基本对象
     *
     * @param key 缓存键值
     * @param <T> 返回类型
     * @return 缓存键值对应的数据,不存在返回null
     */
    public <T> T getCacheObject(final String key) {
        ValueOperations<String, T> operation = redisTemplate.opsForValue();
        return operation.get(key);
    }

    /**
     * 删除单个对象
     *
     * @param key Redis键
     * @return true=删除成功,false=删除失败
     */
    public boolean deleteObject(final String key) {
        Boolean result = redisTemplate.delete(key);
        return result != null && result;
    }

    /**
     * 删除集合对象
     *
     * @param collection 多个对象的集合
     * @return 成功删除的数量
     */
    public long deleteObject(final Collection collection) {
        Long result = redisTemplate.delete(collection);
        return result == null ? 0 : result;
    }

    /**
     * 缓存List数据
     *
     * @param key      缓存的键值
     * @param dataList 待缓存的List数据
     * @param <T>      列表元素类型
     * @return 缓存的元素数量
     */
    public <T> long setCacheList(final String key, final List<T> dataList) {
        Long count = redisTemplate.opsForList().rightPushAll(key, dataList);
        return count == null ? 0 : count;
    }

    /**
     * 缓存List数据并设置过期时间
     *
     * @param key      缓存的键值
     * @param dataList 待缓存的List数据
     * @param timeout  时间
     * @param timeUnit 时间颗粒度
     * @param <T>      列表元素类型
     * @return 缓存的元素数量
     */
    public <T> long setCacheList(final String key, final List<T> dataList, final long timeout,
            final TimeUnit timeUnit) {
        Long count = redisTemplate.opsForList().rightPushAll(key, dataList);
        if (timeout > 0) {
            expire(key, timeout, timeUnit);
        }
        return count == null ? 0 : count;
    }

    /**
     * 获得缓存的list对象
     *
     * @param key 缓存的键值
     * @param <T> 列表元素类型
     * @return 缓存键值对应的数据,不存在返回null
     */
    public <T> List<T> getCacheList(final String key) {
        // 先检查key是否存在
        Boolean exists = redisTemplate.hasKey(key);
        if (exists == null || !exists) {
            return null; // key不存在,返回null
        }
        // key存在,返回列表数据
        return (List<T>) redisTemplate.opsForList().range(key, 0, -1);
    }

    /**
     * 缓存Set
     *
     * @param key     缓存键值
     * @param dataSet 缓存的数据
     * @param <T>     集合元素类型
     * @return 缓存数据的对象
     */
    public <T> BoundSetOperations<String, T> setCacheSet(final String key, final Set<T> dataSet) {
        BoundSetOperations<String, T> setOperation = redisTemplate.boundSetOps(key);
        for (T t : dataSet) {
            setOperation.add(t);
        }
        return setOperation;
    }

    /**
     * 获得缓存的set
     *
     * @param key 缓存键值
     * @param <T> 集合元素类型
     * @return 缓存的Set对象,不存在返回null
     */
    public <T> Set<T> getCacheSet(final String key) {
        // 先检查key是否存在
        Boolean exists = redisTemplate.hasKey(key);
        if (exists == null || !exists) {
            return null; // key不存在,返回null
        }
        return (Set<T>) redisTemplate.opsForSet().members(key);
    }

    /**
     * 缓存Map
     *
     * @param key     Redis键
     * @param dataMap 缓存的Map数据
     * @param <T>     Map值类型
     */
    public <T> void setCacheMap(final String key, final Map<String, T> dataMap) {
        if (dataMap != null) {
            redisTemplate.opsForHash().putAll(key, dataMap);
        }
    }

    /**
     * 获得缓存的Map
     *
     * @param key Redis键
     * @param <T> Map值类型
     * @return 缓存的Map对象,不存在返回null
     */
    public <T> Map<String, T> getCacheMap(final String key) {
        // 先检查key是否存在
        Boolean exists = redisTemplate.hasKey(key);
        if (exists == null || !exists) {
            return null; // key不存在,返回null
        }
        return (Map<String, T>) redisTemplate.opsForHash().entries(key);
    }

    /**
     * 往Hash中存入数据
     *
     * @param key   Redis键
     * @param hKey  Hash键
     * @param value 值
     * @param <T>   值类型
     */
    public <T> void setCacheMapValue(final String key, final String hKey, final T value) {
        redisTemplate.opsForHash().put(key, hKey, value);
    }

    /**
     * 获取Hash中的数据
     *
     * @param key  Redis键
     * @param hKey Hash键
     * @param <T>  值类型
     * @return Hash中的对象,不存在返回null
     */
    public <T> T getCacheMapValue(final String key, final String hKey) {
        HashOperations<String, String, T> opsForHash = redisTemplate.opsForHash();
        return opsForHash.get(key, hKey);
    }

    /**
     * 删除Hash中的数据
     *
     * @param key  Redis键
     * @param hKey Hash键
     * @return 删除的数量
     */
    public long delCacheMapValue(final String key, final String hKey) {
        Long result = redisTemplate.opsForHash().delete(key, hKey);
        return result == null ? 0 : result;
    }

    /**
     * 获取多个Hash中的数据
     *
     * @param key   Redis键
     * @param hKeys Hash键集合
     * @param <T>   值类型
     * @return Hash对象集合
     */
    public <T> List<T> getMultiCacheMapValue(final String key, final Collection<Object> hKeys) {
        return (List<T>) redisTemplate.opsForHash().multiGet(key, hKeys);
    }

    /**
     * 获得缓存的基本对象列表
     *
     * @param pattern 字符串前缀
     * @return 对象列表
     */
    public Collection<String> keys(final String pattern) {
        return redisTemplate.keys(pattern);
    }

    /**
     * 判断key是否存在
     *
     * @param key Redis键
     * @return true=存在,false=不存在
     */
    public boolean hasKey(final String key) {
        Boolean result = redisTemplate.hasKey(key);
        return result != null && result;
    }

    /**
     * 获取key的过期时间
     *
     * @param key Redis键
     * @return 过期时间(秒),-1表示永久,-2表示key不存在
     */
    public long getExpire(final String key) {
        Long result = redisTemplate.getExpire(key, TimeUnit.SECONDS);
        return result == null ? -2 : result;
    }

    /**
     * 原子递增计数器（用于限流等场景）
     *
     * @param key Redis键
     * @return 递增后的值
     */
    public Long increment(final String key) {
        return redisTemplate.opsForValue().increment(key);
    }

    /**
     * 原子递增计数器并设置步长
     */
    public Long increment(final String key, long delta) {
        return redisTemplate.opsForValue().increment(key, delta);
    }

    /**
     * 仅当 key 不存在时设置值（SETNX），用于防重提交等场景
     *
     * @param key     Redis键
     * @param value   缓存值
     * @param timeout 过期时间
     * @param unit    时间单位
     * @return true=设置成功(key不存在), false=设置失败(key已存在)
     */
    public <T> boolean setObjectIfAbsent(final String key, final T value, final long timeout, final TimeUnit unit) {
        Boolean result = redisTemplate.opsForValue().setIfAbsent(key, value, timeout, unit);
        return result != null && result;
    }
}
