package com.xinghuiTec.social.config;

import lombok.Data;

import java.util.List;

/**
 * 单个社交平台的 OAuth 配置
 *
 * @author xinghuiTec
 */
@Data
public class SocialLoginConfigProperties {

    private String clientId;
    private String clientSecret;
    private String redirectUri;
    private List<String> scopes;
    /** 支付宝公钥（仅支付宝登录使用） */
    private String alipayPublicKey;
}
