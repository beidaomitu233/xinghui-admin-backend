package com.xinghuiTec.wechat;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import org.springframework.data.redis.connection.RedisStandaloneConfiguration;
import org.springframework.data.redis.connection.lettuce.LettuceConnectionFactory;
import org.springframework.data.redis.core.StringRedisTemplate;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.Executors;
import static org.junit.jupiter.api.Assertions.*;

/** Explicit dedicated real Redis; never silently falls back to default Redis. */
@EnabledIfEnvironmentVariable(named="WX_REDIS_PASSWORD",matches=".+")
class WechatStateRedisTest {
    @Test void expiryBindingReplayConfigurationConcurrencyLimitsAndFailureClosed() throws Exception {
        var config = new RedisStandaloneConfiguration("127.0.0.1",16379);
        config.setDatabase(14); config.setPassword(System.getenv("WX_REDIS_PASSWORD"));
        var factory = new LettuceConnectionFactory(config); factory.afterPropertiesSet();
        var redis = new StringRedisTemplate(factory);
        var store = new WechatStateStore(redis);
        List<String> keys = new ArrayList<>();
        String run = WechatStateStore.random(), binding = WechatStateStore.random();
        try {
            assertTrue(store.available());
            String state = WechatStateStore.random();
            store.create(state,binding,4,run);
            Long ttl=redis.getExpire(WechatStateStore.PREFIX+"state:"+WechatStateStore.hash(state));
            assertNotNull(ttl); assertTrue(ttl > 290 && ttl <= 300);
            assertFalse(store.consume(state,WechatStateStore.random(),4));
            assertFalse(store.consume(state,binding,5));
            assertFalse(store.consume(WechatStateStore.random(),binding,4));
            assertTrue(store.consume(state,binding,4)); assertFalse(store.consume(state,binding,4));
            String expiring = WechatStateStore.random();
            keys.add(WechatStateStore.PREFIX+"state:"+WechatStateStore.hash(expiring));
            store.create(expiring,binding,4,run);
            redis.expire(WechatStateStore.PREFIX+"state:"+WechatStateStore.hash(expiring),Duration.ofMillis(1));
            Thread.sleep(20); assertFalse(store.consume(expiring,binding,4));
            String concurrent = WechatStateStore.random(); store.create(concurrent,binding,4,run);
            var pool = Executors.newFixedThreadPool(8);
            try {
                var calls = new ArrayList<java.util.concurrent.Callable<Boolean>>();
                for(int i=0;i<8;i++) calls.add(() -> store.consume(concurrent,binding,4));
                int successes=0; for(var result:pool.invokeAll(calls)) if(result.get()) successes++;
                assertEquals(1,successes);
            } finally { pool.shutdownNow(); }
            assertEquals(429,assertThrows(WechatFailure.class, () -> store.create(WechatStateStore.random(),binding,4,run)).status);
        } finally {
            // Only exact test keys, no KEYS/flush or shared pending-index deletion.
            keys.add(WechatStateStore.PREFIX+"browser:"+WechatStateStore.hash(binding));
            keys.add(WechatStateStore.PREFIX+"ip:"+WechatStateStore.hash(run));
            for(String key:keys) if(key.startsWith(WechatStateStore.PREFIX+"state:"))
                redis.opsForZSet().remove(WechatStateStore.PREFIX+"pending",key);
            redis.delete(keys); factory.destroy();
        }
        var unavailable = new WechatStateStore(redis);
        assertFalse(unavailable.available());
        assertEquals(503,assertThrows(WechatFailure.class, () -> unavailable.create(WechatStateStore.random(),binding,4,run)).status);
        assertEquals(503,assertThrows(WechatFailure.class, () -> unavailable.consume(WechatStateStore.random(),binding,4)).status);
    }
}
