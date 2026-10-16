package com.xinghuiTec.ai.config;

import com.alibaba.druid.DbType;
import com.alibaba.druid.filter.stat.StatFilter;
import com.alibaba.druid.pool.DruidDataSource;
import com.alibaba.druid.wall.WallConfig;
import com.alibaba.druid.wall.WallFilter;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.xinghuiTec.ai.contract.AiApplicationReferences;
import com.xinghuiTec.ai.model.*;
import com.xinghuiTec.config.SecretCipher;
import com.xinghuiTec.controller.AiConfigurationController;
import org.junit.jupiter.api.*;
import org.springframework.beans.factory.support.DefaultListableBeanFactory;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DataSourceTransactionManager;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import org.springframework.transaction.support.TransactionTemplate;
import java.util.List;
import java.util.UUID;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

/** Real Druid stat+MySQL Wall -> JDBC -> real list service -> HTTP parameter binding. */
class AiConfigurationDruidHttpTest {
    static DruidDataSource dataSource(String url,String username,String password) {
        var source=new DruidDataSource(); source.setUrl(url); source.setUsername(username); source.setPassword(password);
        source.setDbType(DbType.mysql); source.setInitialSize(1); source.setMinIdle(0); source.setMaxActive(2);
        source.setMaxWait(3000); source.setValidationQuery("SELECT 1");
        var config=new WallConfig(); config.setMultiStatementAllow(true);
        var wall=new WallFilter(); wall.setDbType(DbType.mysql); wall.setConfig(config);
        source.setProxyFilters(List.of(new StatFilter(),wall));
        return source;
    }
    static AiConfigurationService service(DruidDataSource source) {
        var jdbc=new JdbcTemplate(source); var cipher=new SecretCipher(); var rules=new AiModelConfigRules();
        var legacy=new AiModelConfigService(jdbc,rules);
        return new AiConfigurationService(jdbc,new ObjectMapper().findAndRegisterModules(),cipher,rules,
                new TransactionTemplate(new DataSourceTransactionManager(source)),
                new DefaultListableBeanFactory().getBeanProvider(AiApplicationReferences.class),legacy,new AiModelCredentialService(jdbc,cipher,legacy));
    }
    DruidDataSource emptySource() {
        var source=dataSource("jdbc:h2:mem:ai_druid_"+UUID.randomUUID()+";MODE=MySQL","sa","");
        source.setDriverClassName("org.h2.Driver");
        var jdbc=new JdbcTemplate(source);
        jdbc.execute("CREATE TABLE sys_ai_configuration(config_id BIGINT PRIMARY KEY,config_name VARCHAR(100),provider_code VARCHAR(64),environment_name VARCHAR(32),status VARCHAR(16))");
        jdbc.execute("CREATE TABLE sys_ai_model_config(model_id BIGINT PRIMARY KEY,provider_code VARCHAR(64),model_code VARCHAR(128))");
        return source;
    }
    MockMvc http(DruidDataSource source) {
        // The unrelated diagnostic runner is not called by GET; all listing SQL and services are real.
        return MockMvcBuilders.standaloneSetup(new AiConfigurationController(service(source),mock(AiConfigurationTests.class))).build();
    }
    @Test void exactEmptyFilterRequestReturnsAnEmptyPageWithProductionWallEnabled() throws Exception {
        try(var source=emptySource()) {
            var jdbc=new JdbcTemplate(source);
            assertThrows(org.springframework.jdbc.UncategorizedSQLException.class,()->jdbc.queryForObject("SELECT COUNT(*) FROM sys_ai_configuration WHERE 1=1",Long.class));
            var mvc=http(source);
            mvc.perform(get("/system/ai/configurations").param("name","").param("providerCode","").param("environment","").param("status","").param("pageNum","1").param("pageSize","10"))
                    .andExpect(status().isOk()).andExpect(jsonPath("$.code").value(200))
                    .andExpect(jsonPath("$.data.records").isEmpty()).andExpect(jsonPath("$.data.total").value(0))
                    .andExpect(jsonPath("$.data.legacyModels").isEmpty()).andExpect(jsonPath("$.data.pageNum").value(1)).andExpect(jsonPath("$.data.pageSize").value(10));
            mvc.perform(get("/system/ai/configurations").param("pageNum","1").param("pageSize","10"))
                    .andExpect(status().isOk()).andExpect(jsonPath("$.data.records").isEmpty());
        }
    }
    @Test void boundFiltersPaginationAndMalformedNumbersUseTheirRealHttpContracts() throws Exception {
        try(var source=emptySource()) {
            var mvc=http(source);
            mvc.perform(get("/system/ai/configurations").param("name","sample").param("providerCode","openrouter").param("environment","qa").param("status","DRAFT").param("pageNum","2").param("pageSize","3"))
                    .andExpect(status().isOk()).andExpect(jsonPath("$.data.records").isEmpty())
                    .andExpect(jsonPath("$.data.pageNum").value(2)).andExpect(jsonPath("$.data.pageSize").value(3));
            mvc.perform(get("/system/ai/configurations").param("pageNum","not-a-number"))
                    .andExpect(status().isBadRequest()).andExpect(jsonPath("$.code").value(202));
            mvc.perform(get("/system/ai/configurations").param("pageSize","0"))
                    .andExpect(status().isBadRequest()).andExpect(jsonPath("$.code").value(202));
        }
    }
}
