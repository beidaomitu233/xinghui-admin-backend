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
        Map<String, Object> map = new HashMap<String, Object>() {
            private static final long serialVersionUID = 1L;
            {
                put("userId", userId);
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
}
