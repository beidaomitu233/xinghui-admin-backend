package com.xinghuiTec.utils;

import cn.hutool.jwt.JWT;
import cn.hutool.jwt.JWTHeader;
import cn.hutool.jwt.JWTUtil;

import java.util.HashMap;
import java.util.Map;

import static com.xinghuiTec.constants.JwtConstants.TOKEN_EXPIRATION;
import static com.xinghuiTec.constants.JwtConstants.TOKEN_SIGN_KEY;

/**
 * JWT工具类
 * 用于生成、解析和验证JWT令牌
 */
public class JwtUtil {

    /**
     * 创建JWT令牌
     *
     * @param userId   用户ID
     * @param tenantId 租户ID
     * @return 生成的JWT令牌字符串
     */
    public static String createJWT(String userId, String tenantId) {
        Map<String, Object> map = new HashMap<String, Object>() {
            private static final long serialVersionUID = 1L;
            {
                put("userId", userId);
                put("expire_time", System.currentTimeMillis() + TOKEN_EXPIRATION);
                if (tenantId != null && !tenantId.isBlank()) {
                    put("tenantId", tenantId);
                }
            }
        };
        return JWTUtil.createToken(map, TOKEN_SIGN_KEY);
    }

    /**
     * 创建JWT令牌（无租户，向后兼容）
     */
    public static String createJWT(String userId) {
        return createJWT(userId, null);
    }

    public static JWT parseToken(String Token) {
        return JWTUtil.parseToken(Token);
    }

    public static boolean verify(String token) {
        return JWTUtil.verify(token, TOKEN_SIGN_KEY);
    }
}
