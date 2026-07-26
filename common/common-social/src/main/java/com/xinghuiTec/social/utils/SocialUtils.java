package com.xinghuiTec.social.utils;

import cn.hutool.core.util.ObjectUtil;
import com.xinghuiTec.social.config.SocialLoginConfigProperties;
import com.xinghuiTec.social.config.SocialProperties;
import com.xinghuiTec.utils.SpringUtils;
import me.zhyd.oauth.config.AuthConfig;
import me.zhyd.oauth.exception.AuthException;
import me.zhyd.oauth.model.AuthCallback;
import me.zhyd.oauth.model.AuthResponse;
import me.zhyd.oauth.model.AuthUser;
import me.zhyd.oauth.request.*;

/**
 * 第三方登录工具类
 *
 * @author xinghuiTec
 */
public class SocialUtils {

    private static final AuthRedisStateCache STATE_CACHE = SpringUtils.getBean(AuthRedisStateCache.class);

    /** 执行第三方登录认证 */
    public static AuthResponse<AuthUser> loginAuth(String source, String code, String state,
                                                    SocialProperties socialProperties) throws AuthException {
        AuthRequest request = getAuthRequest(source, socialProperties);
        AuthCallback callback = new AuthCallback();
        callback.setCode(code);
        callback.setState(state);
        return request.login(callback);
    }

    /** 根据平台标识获取 AuthRequest */
    public static AuthRequest getAuthRequest(String source, SocialProperties socialProperties) {
        SocialLoginConfigProperties obj = socialProperties.getType().get(source);
        if (ObjectUtil.isNull(obj)) {
            throw new AuthException("不支持的第三方登录类型: " + source);
        }

        AuthConfig config = AuthConfig.builder()
            .clientId(obj.getClientId())
            .clientSecret(obj.getClientSecret())
            .redirectUri(obj.getRedirectUri())
            .scopes(obj.getScopes())
            .build();

        return switch (source.toLowerCase()) {
            case "gitee"      -> new AuthGiteeRequest(config, STATE_CACHE);
            case "github"     -> new AuthGithubRequest(config, STATE_CACHE);
            case "weibo"      -> new AuthWeiboRequest(config, STATE_CACHE);
            case "qq"         -> new AuthQqRequest(config, STATE_CACHE);
            case "wechat_open"-> new AuthWeChatOpenRequest(config, STATE_CACHE);
            case "dingtalk"   -> new AuthDingTalkV2Request(config, STATE_CACHE);
            case "baidu"      -> new AuthBaiduRequest(config, STATE_CACHE);
            case "alipay_wallet" -> new AuthAlipayRequest(config, obj.getAlipayPublicKey(), STATE_CACHE);
            case "douyin"     -> new AuthDouyinRequest(config, STATE_CACHE);
            case "gitlab"     -> new AuthGitlabRequest(config, STATE_CACHE);
            case "microsoft"  -> new AuthMicrosoftRequest(config, STATE_CACHE);
            case "wechat_mp"  -> new AuthWeChatMpRequest(config, STATE_CACHE);
            case "huawei"     -> new AuthHuaweiV3Request(config, STATE_CACHE);
            default -> throw new AuthException("暂不支持的平台: " + source);
        };
    }
}
