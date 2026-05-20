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
 *
 * 作用：替换 Spring Boot 默认的 Validator Bean，实现:
 *   1. 快速失败（fail-fast）— 遇到第一个校验错误立即返回，不继续检查
 *   2. 国际化校验消息 — 注入 MessageSource，使 @NotNull(message="{not.null}") 从 messages.properties 查找中文翻译
 *
 * 原理：
 *   @AutoConfiguration(before = ValidationAutoConfiguration.class) 确保在 Spring Boot
 *   默认的 ValidationAutoConfiguration 之前执行，本 Bean 会优先注册，从而替换掉默认的 Validator。
 *   LocalValidatorFactoryBean 同时实现了 jakarta.validation.Validator 和
 *   org.springframework.validation.Validator，所以 Spring MVC 和 Jakarta 校验都能使用。
 *
 * @author xinghuiTec
 */
@AutoConfiguration(before = ValidationAutoConfiguration.class)
public class ValidatorConfig {

    @Bean
    public Validator validator(MessageSource messageSource) {
        try (LocalValidatorFactoryBean factoryBean = new LocalValidatorFactoryBean()) {
            // 注入 MessageSource，使校验注解中的 {key} 能从 messages.properties 获取国际化文本
            factoryBean.setValidationMessageSource(messageSource);
            // 使用 HibernateValidator 实现
            factoryBean.setProviderClass(HibernateValidator.class);
            Properties properties = new Properties();
            // 快速失败：第一个字段校验失败就返回，不继续检查后续字段
            properties.setProperty("hibernate.validator.fail_fast", "true");
            factoryBean.setValidationProperties(properties);
            factoryBean.afterPropertiesSet();
            return factoryBean.getValidator();
        }
    }

}
