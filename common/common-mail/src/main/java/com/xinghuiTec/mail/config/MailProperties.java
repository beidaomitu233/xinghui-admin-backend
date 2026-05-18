package com.xinghuiTec.mail.config;

import lombok.Data;
import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * 邮件配置属性
 *
 * @author xinghuiTec
 */
@Data
@ConfigurationProperties(prefix = "mail")
public class MailProperties {

    private Boolean enabled = false;
    private String host;
    private Integer port;
    private Boolean auth = true;
    private String user;
    private String pass;
    private String from;
    private Boolean starttlsEnable = true;
    private Boolean sslEnable = true;
    private Long timeout = 0L;
    private Long connectionTimeout = 0L;
}
