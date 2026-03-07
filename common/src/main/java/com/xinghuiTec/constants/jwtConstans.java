package com.xinghuiTec.constants;

/**
 * JWT 相关常量类
 * 统一管理 JWT 过期时间、加密密钥、载荷字段名等常量
 */
public class jwtConstans {

    /**
     * TOKEN 过期时间 (毫秒)
     * 默认: 1天
     */
    public static final long TOKEN_EXPIRATION = 60 * 60 * 1000L * 24;

    /**
     * TOKEN 加密密钥
     */
    public static final byte[] TOKEN_SIGN_KEY = "XINGHUIADMINPROJECTXXXXXXXXXXXXXXXXX".getBytes();

    /**
     * JWT 载荷字段名 - 用户ID
     * 用于从 JWT 中提取用户ID
     */
    public static final String PAYLOAD_USER_ID = "userId";
}
