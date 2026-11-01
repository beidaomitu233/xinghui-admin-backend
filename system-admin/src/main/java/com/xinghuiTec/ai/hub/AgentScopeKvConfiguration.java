package com.xinghuiTec.ai.hub;

import io.agentscope.extensions.redis.RedisDistributedStore;
import io.agentscope.harness.agent.DistributedStore;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.*;
import redis.clients.jedis.*;

/** Native SDK storage configuration, isolated by prefix and by the SDK's session namespace. */
@Configuration(proxyBeanMethods=false)
public class AgentScopeKvConfiguration {
    @Bean(destroyMethod="close") JedisPooled agentScopeKvClient(
            @Value("${spring.data.redis.host:127.0.0.1}")String host,
            @Value("${spring.data.redis.port:6379}")int port,
            @Value("${spring.data.redis.database:0}")int database,
            @Value("${spring.data.redis.username:}")String username,
            @Value("${spring.data.redis.password:}")String password){
        var options=DefaultJedisClientConfig.builder().database(database).connectionTimeoutMillis(3000).socketTimeoutMillis(5000);
        if(!username.isBlank())options.user(username);if(!password.isBlank())options.password(password);
        return new JedisPooled(new HostAndPort(host,port),options.build());
    }
    @Bean DistributedStore agentScopeDistributedStore(JedisPooled agentScopeKvClient){
        return RedisDistributedStore.fromJedis(agentScopeKvClient,"xinghui:hub:v1:");
    }
}
