package com.xinghuiTec.config.redis;


import org.springframework.cache.annotation.EnableCaching;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.data.redis.connection.RedisConnectionFactory;
import org.springframework.data.redis.core.RedisTemplate;
import org.springframework.data.redis.serializer.StringRedisSerializer;



/**
 * Redis缓存配置类
 * 用于配置RedisTemplate和CacheManager，实现数据在Redis中的序列化存储
 */
@Configuration
@EnableCaching
@SuppressWarnings("all")
public class RedisConfig {

    /**
     * 创建并配置RedisTemplate Bean
     * 用于操作Redis数据库，设置了键值的序列化方式
     *
     * @param connectionFactory Redis连接工厂
     * @return 配置好的RedisTemplate对象
     */
    @Bean
    public RedisTemplate<Object, ?> redisTemplate(final RedisConnectionFactory connectionFactory) {
        return createTemplate(connectionFactory);
    }

    /**
     * 创建RedisTemplate实例并配置序列化器
     * 使用FastJson2RedisSerializer进行值的序列化，提高序列化效率
     *
     * @param connectionFactory Redis连接工厂
     * @return 配置好的RedisTemplate对象
     */
    public static RedisTemplate<Object, ?> createTemplate(
            final RedisConnectionFactory connectionFactory) {
        RedisTemplate<Object, ?> template = new RedisTemplate<>();
        template.setConnectionFactory(connectionFactory);

        FastJson2RedisSerializer serializer = new FastJson2RedisSerializer(Object.class);

        template.setValueSerializer(serializer);
        //使用StringRedisSerializer来序列化和反序列化redis的key值
        template.setKeySerializer(new StringRedisSerializer());
        template.setHashKeySerializer(serializer);
        template.setHashValueSerializer(serializer);
        template.setDefaultSerializer(serializer);
        template.setStringSerializer(serializer);
        template.afterPropertiesSet();
        return template;
    }

}