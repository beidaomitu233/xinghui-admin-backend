package com.xinghuiTec.constants;

/**
 * JWT 常量
 *
 * @author xinghuiTec
 */
public interface JwtConstants {

    /** TOKEN 过期时间 (毫秒)，默认 1 天 */
    long TOKEN_EXPIRATION = 60 * 60 * 1000L * 24;

    /** TOKEN 签名密钥：环境必须显式提供，没有固定回退。 */
    byte[] TOKEN_SIGN_KEY = requireSigningKey(System.getenv("JWT_SECRET"));

    static byte[] requireSigningKey(String value) {
        if (value == null || value.isBlank()) {
            throw new IllegalStateException("JWT_SECRET is required");
        }
        byte[] bytes = value.getBytes(java.nio.charset.StandardCharsets.UTF_8);
        if (bytes.length < 32) {
            throw new IllegalStateException("JWT_SECRET must contain at least 32 UTF-8 bytes");
        }
        return bytes;
    }

    /** JWT 载荷字段名 - 用户 ID */
    String PAYLOAD_USER_ID = "userId";

    /** JWT 载荷字段名 - 租户 ID */
    String PAYLOAD_TENANT_ID = "tenantId";
}
