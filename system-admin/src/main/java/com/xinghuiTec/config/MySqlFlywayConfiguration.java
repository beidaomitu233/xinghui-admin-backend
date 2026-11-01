package com.xinghuiTec.config;

import org.springframework.boot.autoconfigure.flyway.FlywayConfigurationCustomizer;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import java.sql.SQLException;

@Configuration(proxyBeanMethods = false)
public class MySqlFlywayConfiguration {
    @Bean
    FlywayConfigurationCustomizer explicitLegacyTimestampDefaults() {
        return configuration -> {
            try (var connection = configuration.getDataSource().getConnection()) {
                var database = connection.getMetaData();
                // 5.7 defaults to implicit zero timestamps. Early 8.0 already defaults
                // to explicit values and restricts SET SESSION to privileged users.
                if (database.getDatabaseProductName().equals("MySQL")
                        && database.getDatabaseMajorVersion() == 5) {
                    configuration.initSql("SET SESSION explicit_defaults_for_timestamp=ON");
                }
            } catch (SQLException failure) {
                throw new IllegalStateException("无法核对数据库迁移配置", failure);
            }
        };
    }
}
