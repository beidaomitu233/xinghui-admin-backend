package com.xinghuiTec.social.config;

import com.xinghuiTec.social.utils.AuthRedisStateCache;
import me.zhyd.oauth.cache.AuthStateCache;
import org.springframework.boot.autoconfigure.AutoConfiguration;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;

/**
 * 社交登录自动配置
 *
 * @author xinghuiTec
 */
@AutoConfiguration
@EnableConfigurationProperties(SocialProperties.class)
public class SocialAutoConfiguration {

    @Bean
    public AuthStateCache authStateCache() {
        return new AuthRedisStateCache();
    }
}
