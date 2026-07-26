package com.xinghuiTec.redis;

import com.xinghuiTec.utils.RedisCacheUtils;
import jakarta.annotation.Resource;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.SpringBootTest;

import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.*;

@SpringBootTest
@DisplayName("Redis 缓存工具测试")
public class RedisCacheUtilsTest {

    @Resource
    private RedisCacheUtils redis;

    private static final String TEST_KEY = "test:junit:key";

    @Test
    @DisplayName("set + get + delete 基本操作")
    void testSetGetDelete() {
        // set
        redis.setCacheObject(TEST_KEY, "hello");
        // get
        String value = redis.getCacheObject(TEST_KEY);
        assertEquals("hello", value);
        // delete
        assertTrue(redis.deleteObject(TEST_KEY));
        // 删除后为 null
        assertNull(redis.getCacheObject(TEST_KEY));
        System.out.println("✓ set/get/delete 通过");
    }

    @Test
    @DisplayName("带过期时间的缓存")
    void testExpire() {
        redis.setCacheObject(TEST_KEY, "expired-value", 1, TimeUnit.SECONDS);
        assertEquals("expired-value", redis.getCacheObject(TEST_KEY));
        assertTrue(redis.hasKey(TEST_KEY));
        System.out.println("✓ 过期时间设置通过");
        redis.deleteObject(TEST_KEY);
    }

    @Test
    @DisplayName("INCR 原子递增")
    void testIncrement() {
        redis.deleteObject(TEST_KEY);
        Long v1 = redis.increment(TEST_KEY);
        assertEquals(1L, v1);
        Long v2 = redis.increment(TEST_KEY);
        assertEquals(2L, v2);
        Long v3 = redis.increment(TEST_KEY, 3L);
        assertEquals(5L, v3);
        redis.deleteObject(TEST_KEY);
        System.out.println("✓ INCR 递增通过");
    }

    @Test
    @DisplayName("SETNX 原子不存在才设值")
    void testSetObjectIfAbsent() {
        redis.deleteObject(TEST_KEY);
        assertTrue(redis.setObjectIfAbsent(TEST_KEY, "first", 10, TimeUnit.SECONDS));
        assertFalse(redis.setObjectIfAbsent(TEST_KEY, "second", 10, TimeUnit.SECONDS));
        assertEquals("first", redis.getCacheObject(TEST_KEY));
        redis.deleteObject(TEST_KEY);
        System.out.println("✓ SETNX 通过");
    }

    @Test
    @DisplayName("不存在的 key 返回 null")
    void testKeyNotExists() {
        assertNull(redis.getCacheObject("test:key:not:exists:99999"));
        assertFalse(redis.hasKey("test:key:not:exists:99999"));
        System.out.println("✓ 不存在 key 返回 null");
    }
}
