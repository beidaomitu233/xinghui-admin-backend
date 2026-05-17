package com.xinghuiTec.config;

import jakarta.validation.Validator;
import org.hibernate.validator.HibernateValidator;
import org.springframework.boot.autoconfigure.AutoConfiguration;
import org.springframework.boot.autoconfigure.validation.ValidationAutoConfiguration;
import org.springframework.context.MessageSource;
import org.springframework.context.annotation.Bean;
import org.springframework.validation.beanvalidation.LocalValidatorFactoryBean;

import java.util.Properties;

/**
 * 校验框架配置类
 * - 快速失败模式（遇到第一个校验失败立即返回）
 * - 国际化校验消息
 *
 * @author xinghuiTec
 */
@AutoConfiguration(before = ValidationAutoConfiguration.class)
public class ValidatorConfig {

    @Bean
    public Validator validator(MessageSource messageSource) {
        try (LocalValidatorFactoryBean factoryBean = new LocalValidatorFactoryBean()) {
            factoryBean.setValidationMessageSource(messageSource);
            factoryBean.setProviderClass(HibernateValidator.class);
            Properties properties = new Properties();
            properties.setProperty("hibernate.validator.fail_fast", "true");
            factoryBean.setValidationProperties(properties);
            factoryBean.afterPropertiesSet();
            return factoryBean.getValidator();
        }
    }

}
