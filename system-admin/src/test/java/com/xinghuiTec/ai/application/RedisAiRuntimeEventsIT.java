package com.xinghuiTec.ai.application;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.xinghuiTec.ai.contract.AiRuntimeException;
import com.xinghuiTec.ai.contract.AiTypes.ErrorCode;
import org.junit.jupiter.api.Test;
import org.springframework.data.redis.connection.RedisPassword;
import org.springframework.data.redis.connection.RedisStandaloneConfiguration;
import org.springframework.data.redis.connection.lettuce.LettuceConnectionFactory;
import org.springframework.data.redis.core.StringRedisTemplate;

import java.util.List;
import java.util.concurrent.ThreadLocalRandom;
import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.*;

/** Explicit PM isolated Redis window only. Never touches default/user Redis, configuration or other prefixes. */
class RedisAiRuntimeEventsIT {
    @Test
    void terminalMarkerRejectsLateDeltasWithoutAdvancingSequence() {
        String password = System.getenv("AI_V04_REDIS_PASSWORD");
        assertNotNull(password, "Private isolated Redis password required");
        var configuration = new RedisStandaloneConfiguration("127.0.0.1", 16381);
        configuration.setPassword(RedisPassword.of(password));
        var factory = new LettuceConnectionFactory(configuration);
        factory.afterPropertiesSet(); factory.start();
        var redis = new StringRedisTemplate(factory); redis.afterPropertiesSet();
        String prefix = "qa:aiv04_b_20261016:";
        String id = Long.toString(ThreadLocalRandom.current().nextLong(9_000_000_000L, 10_000_000_000L));
        String key = prefix + "{" + id + "}";
        List<String> keys = List.of(key + ":events", key + ":sequence", key + ":bytes", key + ":terminal");
        boolean ownFixture = false;
        try {
            for (String fixture : keys) assertFalse(Boolean.TRUE.equals(redis.hasKey(fixture)), "Fixture collision");
            var a = new RedisAiRuntimeEvents(redis, new ObjectMapper(), prefix);
            var b = new RedisAiRuntimeEvents(redis, new ObjectMapper(), prefix);
            a.append(new AiRuntimeEvents.Event(AiRuntimeEvents.Name.ACCEPTED, id, 0, AiRequestStatus.QUEUED, null, null, null, null));
            ownFixture = true;
            b.append(new AiRuntimeEvents.Event(AiRuntimeEvents.Name.CANCELLED, id, 0, AiRequestStatus.CANCELLED, null, null, null, ErrorCode.CANCELLED));
            assertEquals(ErrorCode.CANCELLED, assertThrows(AiRuntimeException.class, () -> a.append(
                    new AiRuntimeEvents.Event(AiRuntimeEvents.Name.DELTA, id, 0, AiRequestStatus.RUNNING, "late neutral fixture", null, null, null))).code());
            assertEquals(2, a.after(id, 0).size());
            assertEquals("2", redis.opsForValue().get(key + ":sequence"));
            assertTrue(redis.getExpire(key + ":terminal", TimeUnit.SECONDS) > 570);
        } finally {
            if (ownFixture) redis.delete(keys);
            factory.destroy();
        }
    }

    @Test
    void twoInstancesShareOrderedEventsAndUtf8BudgetStopsBeforeAnyHistoryIsDiscarded() {
        String password = System.getenv("AI_V04_REDIS_PASSWORD");
        assertNotNull(password, "Private isolated Redis password required");
        var configuration = new RedisStandaloneConfiguration("127.0.0.1", 16381);
        configuration.setDatabase(0);
        configuration.setPassword(RedisPassword.of(password));
        var factory = new LettuceConnectionFactory(configuration);
        factory.afterPropertiesSet();
        factory.start();
        var redis = new StringRedisTemplate(factory);
        redis.afterPropertiesSet();
        String prefix = "qa:aiv04_b_20261016:";
        String id = Long.toString(ThreadLocalRandom.current().nextLong(8_000_000_000L, 9_000_000_000L));
        List<String> keys = List.of(prefix + "{" + id + "}:events", prefix + "{" + id + "}:sequence",
                prefix + "{" + id + "}:bytes", prefix + "{" + id + "}:terminal");
        boolean ownFixture = false;
        try {
            for (String key : keys) assertFalse(Boolean.TRUE.equals(redis.hasKey(key)), "Fixture collision");
            var a = new RedisAiRuntimeEvents(redis, new ObjectMapper(), prefix);
            var b = new RedisAiRuntimeEvents(redis, new ObjectMapper(), prefix);
            var first = a.append(new AiRuntimeEvents.Event(AiRuntimeEvents.Name.ACCEPTED, id, 0,
                    AiRequestStatus.QUEUED, null, null, null, null));
            ownFixture = true;
            assertEquals(1L, first.sequence());
            var delta = b.append(new AiRuntimeEvents.Event(AiRuntimeEvents.Name.DELTA, id, 0,
                    AiRequestStatus.RUNNING, "中性测试", null, null, null));
            assertEquals(2L, delta.sequence());
            assertEquals("中性测试", a.after(id, 1).get(0).text());
            for (String key : keys.subList(0, 3)) {
                Long ttl = redis.getExpire(key, TimeUnit.SECONDS);
                assertNotNull(ttl);
                assertTrue(ttl > 570 && ttl <= 600);
            }
            int accepted = 2;
            while (true) {
                try {
                    a.append(new AiRuntimeEvents.Event(AiRuntimeEvents.Name.DELTA, id, 0,
                            AiRequestStatus.RUNNING, "中".repeat(4096), null, null, null));
                    accepted++;
                    assertTrue(accepted < 30, "UTF-8 budget must stop before 30 large frames");
                } catch (AiRuntimeException bounded) {
                    assertEquals(ErrorCode.OUTPUT_LIMIT, bounded.code());
                    break;
                }
            }
            assertEquals((long) accepted, redis.opsForList().size(keys.get(0)));
            assertEquals("1", a.after(id, 0).get(0).sequence() + "");
            assertTrue(Long.parseLong(redis.opsForValue().get(keys.get(2))) <= 262_144);
            assertEquals((long) accepted, Long.parseLong(redis.opsForValue().get(keys.get(1))));
            System.out.println("AI-V04-B Redis fixture: two readers, sequence preserved, TTL<=600s, UTF-8 budget enforced");
        } finally {
            if (ownFixture) redis.delete(keys);
            factory.destroy();
        }
    }
}
