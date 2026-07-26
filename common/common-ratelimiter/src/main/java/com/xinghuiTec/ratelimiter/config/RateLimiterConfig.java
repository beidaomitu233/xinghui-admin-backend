package com.xinghuiTec.ratelimiter.config;

import com.xinghuiTec.ratelimiter.aspect.RateLimiterAspect;
import org.springframework.boot.autoconfigure.AutoConfiguration;
import org.springframework.context.annotation.Bean;

/**
 * 限流模块自动配置
 *
 * @author xinghuiTec
 */
@AutoConfiguration
public class RateLimiterConfig {

    @Bean
    public RateLimiterAspect rateLimiterAspect() {
        return new RateLimiterAspect();
    }
}
