package com.xinghuiTec.social.utils;

import me.zhyd.oauth.cache.AuthStateCache;
import me.zhyd.oauth.config.AuthConfig;
import me.zhyd.oauth.request.AuthWeChatOpenRequest;

/** URL generation only. Project-owned hashed Redis state is the sole flow validator. */
public final class WechatWebsiteAuthorization {
    private WechatWebsiteAuthorization() {}

    private static final AuthStateCache URL_ONLY_CACHE = new AuthStateCache() {
        public void cache(String key, String value) {}
        public void cache(String key, String value, long timeout) {}
        public String get(String key) { return null; }
        public boolean containsKey(String key) { return false; }
    };

    public static String authorize(String clientId, String secret, String callback, String state) {
        if (state == null || !state.matches("[A-Za-z0-9_-]{43}")) {
            throw new IllegalArgumentException("Invalid authorization flow");
        }
        return new AuthWeChatOpenRequest(AuthConfig.builder().clientId(clientId)
                .clientSecret(secret).redirectUri(callback).build(), URL_ONLY_CACHE).authorize(state);
    }
}
