package com.xinghuiTec.social.config;

import lombok.Data;
import org.springframework.boot.context.properties.ConfigurationProperties;

import java.util.Map;

/**
 * 社交登录主配置
 *
 * @author xinghuiTec
 */
@Data
@ConfigurationProperties(prefix = "justauth")
public class SocialProperties {

    /** key=平台标识(gitee/github/wechat_open...), value=平台配置 */
    private Map<String, SocialLoginConfigProperties> type;
}
