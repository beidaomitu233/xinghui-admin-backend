package com.xinghuiTec.ai.model;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.xinghuiTec.config.SecretCipher;
import io.lettuce.core.resource.DefaultClientResources;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import org.springframework.data.redis.connection.RedisStandaloneConfiguration;
import org.springframework.data.redis.connection.lettuce.LettuceClientConfiguration;
import org.springframework.data.redis.connection.lettuce.LettuceConnectionFactory;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import java.nio.file.*;
import java.util.List;
import static org.junit.jupiter.api.Assertions.*;

/** Real provider requests. Private per-model ledger prevents accidental repeated runs. */
@EnabledIfEnvironmentVariable(named="CFG02_REAL_ENABLED",matches="true")
class OpenRouterNativeQaTest {
    @Test void invokeOnlyTheTwoAuthorizedModelsOnceEach() throws Exception {
        String qaDirectory=System.getenv("CFG02_QA_DIRECTORY");
        assertNotNull(qaDirectory,"CFG02_QA_DIRECTORY is required before connecting to QA resources");
        assertFalse(qaDirectory.isBlank(),"CFG02_QA_DIRECTORY is required");
        var directory=Path.of(qaDirectory);
        Files.createDirectories(directory);
        var source=new DriverManagerDataSource("jdbc:mysql://127.0.0.1:13318/cfg02_empty_20261012?useSSL=false&allowPublicKeyRetrieval=true&serverTimezone=Asia/Shanghai","qa_ci",System.getenv("CFG02_DB_PASSWORD"));
        var jdbc=new JdbcTemplate(source);
        assertEquals("cfg02_empty_20261012",jdbc.queryForObject("SELECT DATABASE()",String.class));
        var models=new AiModelConfigService(jdbc,new AiModelConfigRules());
        var credentials=new AiModelCredentialService(jdbc,new SecretCipher(),models);
        var settings=new RedisStandaloneConfiguration("127.0.0.1",16379);
        settings.setDatabase(15);settings.setPassword(System.getenv("CFG02_REDIS_PASSWORD"));
        var resources=DefaultClientResources.builder().ioThreadPoolSize(2).computationThreadPoolSize(2).build();
        var factory=new LettuceConnectionFactory(settings,LettuceClientConfiguration.builder().clientResources(resources).build());
        try {
            factory.afterPropertiesSet();factory.start();
            var redis=new StringRedisTemplate(factory);
            var tester=new OpenRouterModelTester(models,credentials,redis,new ObjectMapper());
            int index=0;
            for(String code:List.of("poolside/laguna-s-2.1:free","nvidia/nemotron-3.5-lightning:free")) {
                long id=jdbc.queryForObject("SELECT model_id FROM sys_ai_model_config WHERE provider_code='openrouter' AND model_code=?",Long.class,code);
                assertEquals("DB",models.get(id).credentialMode());
                String suffix="retry".equals(System.getenv("CFG02_REAL_ATTEMPT"))?".retry":"";
                if(!suffix.isEmpty()) assertTrue(Files.exists(directory.resolve("openrouter-model-"+(index+1)+".attempt")),"First attempt must exist before the one permitted repair retry");
                Path ledger=directory.resolve("openrouter-model-"+(++index)+suffix+".attempt");
                Files.writeString(ledger,"Authorized first attempt: "+code+"\n",StandardOpenOption.CREATE_NEW);
                var result=tester.test(id);
                Files.writeString(directory.resolve("openrouter-model-"+index+suffix+".result.json"),new ObjectMapper().writeValueAsString(result),StandardOpenOption.CREATE_NEW);
                System.out.println("CFG02_REAL model="+code+" outcome="+result.outcome()+" http="+result.httpStatus()+" elapsedMs="+result.elapsedMs()+" providerId="+result.providerId());
            }
        } finally { factory.destroy();resources.shutdown(); }
    }
}
