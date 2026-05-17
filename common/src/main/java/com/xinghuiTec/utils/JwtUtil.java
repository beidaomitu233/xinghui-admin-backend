package com.xinghuiTec.utils;

import cn.hutool.jwt.JWT;
import cn.hutool.jwt.JWTHeader;
import cn.hutool.jwt.JWTUtil;

import java.util.HashMap;
import java.util.Map;

import static com.xinghuiTec.constants.jwtConstans.TOKEN_EXPIRATION;
import static com.xinghuiTec.constants.jwtConstans.TOKEN_SIGN_KEY;

/**
 * JWT工具类
 * 用于生成、解析和验证JWT令牌
 */
public class JwtUtil {
    /**
     * 创建JWT令牌
     *
     * @param userId 用户ID
     * @return 生成的JWT令牌字符串
     */
    public static String createJWT(String userId) {
        return createJWT(userId, null);
    }

    /**
     * 创建JWT令牌（包含租户ID）
     *
     * @param userId 用户ID
     * @param tenantId 租户ID
     * @return 生成的JWT令牌字符串
     */
    public static String createJWT(String userId, String tenantId) {
        Map<String, Object> map = new HashMap<String, Object>() {
            private static final long serialVersionUID = 1L;
            {
                put("userId", userId);
                if (tenantId != null && !tenantId.isEmpty()) {
                    put("tenantId", tenantId);
                }
                put("expire_time", System.currentTimeMillis()+TOKEN_EXPIRATION);
            }
        };
        return JWTUtil.createToken(map, TOKEN_SIGN_KEY);
    }

    public static JWT parseToken(String Token) {
        return JWTUtil.parseToken(Token);
    }

    public static boolean verify(String token) {
        return JWTUtil.verify(token, TOKEN_SIGN_KEY);
    }

    /**
     * 从JWT中获取用户ID
     */
    public static String getUserId(String token) {
        JWT jwt = JWTUtil.parseToken(token);
        Object userId = jwt.getPayload("userId");
        return userId != null ? userId.toString() : null;
    }

    /**
     * 从JWT中获取租户ID
     */
    public static String getTenantId(String token) {
        JWT jwt = JWTUtil.parseToken(token);
        Object tenantId = jwt.getPayload("tenantId");
        return tenantId != null ? tenantId.toString() : null;
    }
}
