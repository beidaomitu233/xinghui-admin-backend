package com.xinghuiTec.config;

import com.xinghuiTec.constants.JwtConstants;
import org.springframework.beans.factory.InitializingBean;
import org.springframework.context.annotation.Configuration;
import org.springframework.core.env.Environment;

/** Reject missing signing configuration before accepting requests. */
@Configuration(proxyBeanMethods = false)
public class JwtSigningKeyConfiguration implements InitializingBean {

    private final Environment environment;

    public JwtSigningKeyConfiguration(Environment environment) {
        this.environment = environment;
    }

    @Override
    public void afterPropertiesSet() {
        // Validate the same environment source used by the existing static JWT utility.
        JwtConstants.requireSigningKey(System.getenv("JWT_SECRET"));
        if (environment.getProperty("spring.datasource.druid.stat-view-servlet.enabled", Boolean.class, false)) {
            requireNonBlank("spring.datasource.druid.stat-view-servlet.login-username");
            requireNonBlank("spring.datasource.druid.stat-view-servlet.login-password");
        }
    }

    private void requireNonBlank(String name) {
        String value = environment.getProperty(name);
        if (value == null || value.isBlank()) {
            throw new IllegalStateException("Enabled Druid requires " + name);
        }
    }
}
