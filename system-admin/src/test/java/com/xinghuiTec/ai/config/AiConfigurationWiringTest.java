package com.xinghuiTec.ai.config;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.xinghuiTec.ai.contract.AiProviderRuntime;
import com.xinghuiTec.ai.model.AiModelConfigRules;
import com.xinghuiTec.ai.model.AiModelConfigService;
import com.xinghuiTec.ai.model.AiModelCredentialService;
import com.xinghuiTec.config.SecretCipher;
import com.xinghuiTec.controller.AiConfigurationController;
import org.junit.jupiter.api.Test;
import org.springframework.context.annotation.AnnotationConfigApplicationContext;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DataSourceTransactionManager;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import org.springframework.test.util.ReflectionTestUtils;
import org.springframework.transaction.support.TransactionTemplate;
import javax.sql.DataSource;
import java.net.http.HttpClient;
import static org.junit.jupiter.api.Assertions.*;

/** Real Spring constructor selection and full A bean graph. No manually supplied service instances. */
class AiConfigurationWiringTest {
    @Test void springInstantiatesTheProductionTransportAndAllNewConfigurationBeans() {
        try(var context=new AnnotationConfigApplicationContext()) {
            context.registerBean(ObjectMapper.class,()->new ObjectMapper());
            context.registerBean(DataSource.class,()->new DriverManagerDataSource("jdbc:h2:mem:ai_wiring;MODE=MySQL","sa",""));
            context.registerBean(JdbcTemplate.class,()->new JdbcTemplate(context.getBean(DataSource.class)));
            context.registerBean(TransactionTemplate.class,()->new TransactionTemplate(new DataSourceTransactionManager(context.getBean(DataSource.class))));
            context.register(SecretCipher.class,AiModelConfigRules.class,AiModelConfigService.class,AiModelCredentialService.class,
                    OpenRouterTransport.class,AiConfigurationService.class,AiConfigurationTests.class,
                    AiConfiguredProviderRuntime.class,AiConfigurationController.class);
            context.refresh();
            var transport=context.getBean(OpenRouterTransport.class);
            assertSame(context.getBean(ObjectMapper.class),ReflectionTestUtils.getField(transport,"json"));
            assertSame(context.getBean(AiModelConfigRules.class),ReflectionTestUtils.getField(transport,"addresses"));
            assertInstanceOf(HttpClient.class,ReflectionTestUtils.getField(transport,"client"));
            assertSame(context.getBean(AiConfiguredProviderRuntime.class),context.getBean(AiProviderRuntime.class));
            assertNotNull(context.getBean(AiConfigurationService.class));
            assertNotNull(context.getBean(AiConfigurationTests.class));
            assertNotNull(context.getBean(AiConfigurationController.class));
            // No ApplicationReadyEvent, DB query, provider call, master-key use or fake reference service.
        }
    }
}
