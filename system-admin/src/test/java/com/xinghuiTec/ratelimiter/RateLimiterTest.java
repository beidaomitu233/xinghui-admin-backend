package com.xinghuiTec.ratelimiter;

import com.xinghuiTec.ratelimiter.annotation.RateLimiter;
import com.xinghuiTec.ratelimiter.enums.LimitType;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.SpringBootTest;

import static org.junit.jupiter.api.Assertions.*;

@SpringBootTest
@DisplayName("限流模块测试")
public class RateLimiterTest {

    @Test
    @DisplayName("LimitType 枚举值")
    void testLimitType() {
        assertEquals(LimitType.DEFAULT, LimitType.valueOf("DEFAULT"));
        assertEquals(LimitType.IP, LimitType.valueOf("IP"));
        System.out.println("✓ LimitType 枚举: DEFAULT, IP");
    }

    @Test
    @DisplayName("@RateLimiter 注解属性默认值")
    void testAnnotationDefaults() throws NoSuchMethodException {
        RateLimiter annotation = TestService.class
            .getMethod("testDefault")
            .getAnnotation(RateLimiter.class);

        assertNotNull(annotation);
        assertEquals(60, annotation.time());
        assertEquals(100, annotation.count());
        assertEquals(LimitType.DEFAULT, annotation.limitType());
        assertEquals("访问过于频繁，请稍候再试", annotation.message());
        System.out.println("✓ @RateLimiter 默认值验证通过");
    }

    static class TestService {
        @RateLimiter
        public void testDefault() {}
    }
}
