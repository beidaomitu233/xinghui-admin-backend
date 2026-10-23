package com.xinghuiTec.mail.config;

import cn.hutool.extra.mail.MailAccount;
import org.springframework.boot.autoconfigure.AutoConfiguration;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;

/**
 * 邮件自动配置
 *
 * @author xinghuiTec
 */
@AutoConfiguration
@EnableConfigurationProperties(MailProperties.class)
public class MailConfig {

    @Bean
    public RuntimeMailService runtimeMailService(org.springframework.beans.factory.ObjectProvider<com.xinghuiTec.config.BusinessConfigSource> source,
                                                org.springframework.beans.factory.ObjectProvider<MailAccount> deployment) {
        return new RuntimeMailService(source,deployment);
    }

    @Bean
    @ConditionalOnProperty(value = "mail.enabled", havingValue = "true")
    public MailAccount mailAccount(MailProperties props) {
        if (props.getHost() == null || props.getHost().isBlank()
                || props.getUser() == null || props.getUser().isBlank()
                || props.getPass() == null || props.getPass().isBlank()
                || props.getFrom() == null || props.getFrom().isBlank()) {
            throw new IllegalStateException("Enabled mail requires host, user, pass and from");
        }
        MailAccount account = new MailAccount();
        account.setHost(props.getHost());
        account.setPort(props.getPort());
        account.setAuth(props.getAuth());
        account.setFrom(props.getFrom());
        account.setUser(props.getUser());
        account.setPass(props.getPass());
        account.setSocketFactoryPort(props.getPort());
        account.setStarttlsEnable(props.getStarttlsEnable());
        account.setSslEnable(props.getSslEnable());
        account.setTimeout(props.getTimeout());
        account.setConnectionTimeout(props.getConnectionTimeout());
        return account;
    }
}
