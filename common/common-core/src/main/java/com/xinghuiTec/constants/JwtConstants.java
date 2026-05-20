package com.xinghuiTec.constants;

/**
 * JWT 常量
 *
 * @author xinghuiTec
 */
public interface JwtConstants {

    /** TOKEN 过期时间 (毫秒)，默认 1 天 */
    long TOKEN_EXPIRATION = 60 * 60 * 1000L * 24;

    /** TOKEN 加密密钥（优先读取环境变量 JWT_SECRET，未设置时使用默认值） */
    byte[] TOKEN_SIGN_KEY = System.getenv().getOrDefault("JWT_SECRET",
            "XINGHUIADMINPROJECTXXXXXXXXXXXXXXXXX").getBytes();

    /** JWT 载荷字段名 - 用户 ID */
    String PAYLOAD_USER_ID = "userId";

    /** JWT 载荷字段名 - 租户 ID */
    String PAYLOAD_TENANT_ID = "tenantId";
}
