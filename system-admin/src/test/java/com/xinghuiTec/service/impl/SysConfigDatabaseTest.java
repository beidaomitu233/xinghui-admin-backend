package com.xinghuiTec.service.impl;

import com.baomidou.mybatisplus.extension.spring.MybatisSqlSessionFactoryBean;
import com.xinghuiTec.domain.dto.ConfigChangeRequest;
import com.xinghuiTec.exception.ServiceException;
import com.xinghuiTec.mapper.SysConfigMapper;
import com.xinghuiTec.service.SysConfigService;
import com.xinghuiTec.utils.RedisCacheUtils;
import org.apache.ibatis.session.SqlSessionFactory;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mybatis.spring.annotation.MapperScan;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import org.springframework.test.context.junit.jupiter.SpringJUnitConfig;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.annotation.EnableTransactionManagement;
import org.springframework.jdbc.datasource.DataSourceTransactionManager;
import org.springframework.data.redis.core.RedisTemplate;

import javax.sql.DataSource;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

@SpringJUnitConfig(SysConfigDatabaseTest.DatabaseConfiguration.class)
class SysConfigDatabaseTest {
    @Autowired private SysConfigService service;
    @Autowired private JdbcTemplate jdbc;
    @Autowired private RedisCacheUtils cache;

    @BeforeEach
    void createTable() {
        jdbc.execute("DROP TABLE IF EXISTS sys_config");
        jdbc.execute("""
                CREATE TABLE sys_config (
                  config_id INT AUTO_INCREMENT PRIMARY KEY,
                  tenant_id VARCHAR(20),
                  config_name VARCHAR(100),
                  config_key VARCHAR(100) UNIQUE,
                  config_value VARCHAR(500),
                  config_type CHAR(1),
                  runtime_active TINYINT DEFAULT 0,
                  remark VARCHAR(500),
                  create_time TIMESTAMP,
                  create_by VARCHAR(64),
                  update_time TIMESTAMP,
                  update_by VARCHAR(64),
                  is_deleted TINYINT DEFAULT 0
                )
                """);
    }

    @Test
    void readsChangedValueFromDatabaseAndNeverReturnsLegacySecret() {
        jdbc.update("INSERT INTO sys_config(tenant_id,config_name,config_key,config_value,config_type) VALUES(?,?,?,?,?)",
                "000000", "初始密码", "sys.user.initPassword", "legacy-secret", "Y");
        assertTrue(service.listManaged().isEmpty());

        String key = "sys.index.footerText";
        Integer id = service.addManaged(new ConfigChangeRequest(key, "first"));
        assertEquals("first", service.getManaged(id).configValue());
        assertEquals("first", service.getPublishedValue(key));
        service.updateManaged(id, new ConfigChangeRequest(key, "second"));
        assertEquals("second", service.getPublishedValue(key));
        assertEquals("second", jdbc.queryForObject(
                "SELECT config_value FROM sys_config WHERE config_id = ?", String.class, id));
        assertEquals(1, service.listManaged().size());
        assertThrows(ServiceException.class, () -> service.getManaged(1));
        service.removeManaged(id);
        assertThrows(ServiceException.class, () -> service.getManaged(id));
        Integer recreatedId = service.addManaged(new ConfigChangeRequest(key, "third"));
        assertEquals("third", service.getPublishedValue(key));
        assertNotEquals(id, recreatedId);
        verify(cache, times(4)).deleteObject("global:sys_config:" + key);
    }

    @Test
    void legacyCaptchaSettingIsPreservedUntilAdminEditsIt() {
        jdbc.update("INSERT INTO sys_config(tenant_id,config_name,config_key,config_value,config_type) VALUES(?,?,?,?,?)",
                "000000", "验证码", "sys.account.captchaOnOff", "true", "Y");
        Integer id = jdbc.queryForObject(
                "SELECT config_id FROM sys_config WHERE config_key = 'sys.account.captchaOnOff'",
                Integer.class);
        assertFalse(service.isCaptchaEnabled(false));
        assertTrue(service.isCaptchaEnabled(true));
        service.updateManaged(id, new ConfigChangeRequest("sys.account.captchaOnOff", "true"));
        assertTrue(service.isCaptchaEnabled(false));
        service.updateManaged(id, new ConfigChangeRequest("sys.account.captchaOnOff", "false"));
        assertFalse(service.isCaptchaEnabled(true));
        verify(cache, times(2)).deleteObject("global:sys_config:effectiveCaptcha");
    }

    @Configuration
    @EnableTransactionManagement
    @MapperScan(basePackageClasses = SysConfigMapper.class)
    static class DatabaseConfiguration {
        @Bean
        DataSource dataSource() {
            var dataSource = new DriverManagerDataSource();
            dataSource.setDriverClassName("org.h2.Driver");
            dataSource.setUrl("jdbc:h2:mem:cfg01;MODE=MySQL;DB_CLOSE_DELAY=-1");
            dataSource.setUsername("sa");
            return dataSource;
        }

        @Bean
        JdbcTemplate jdbcTemplate(DataSource dataSource) {
            return new JdbcTemplate(dataSource);
        }

        @Bean
        SqlSessionFactory sqlSessionFactory(DataSource dataSource) throws Exception {
            var factory = new MybatisSqlSessionFactoryBean();
            factory.setDataSource(dataSource);
            return factory.getObject();
        }

        @Bean
        PlatformTransactionManager transactionManager(DataSource dataSource) {
            return new DataSourceTransactionManager(dataSource);
        }

        @Bean
        SysConfigService sysConfigService() {
            return new SysConfigServiceImpl();
        }

        @Bean
        RedisCacheUtils redisCacheUtils() {
            return mock(RedisCacheUtils.class);
        }

        @Bean
        RedisTemplate<?, ?> redisTemplate() {
            return mock(RedisTemplate.class);
        }
    }
}
