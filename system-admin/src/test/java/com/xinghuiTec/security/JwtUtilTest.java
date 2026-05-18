package com.xinghuiTec.security;

import cn.hutool.jwt.JWT;
import com.xinghuiTec.utils.JwtUtil;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.SpringBootTest;

import static org.junit.jupiter.api.Assertions.*;

@SpringBootTest
@DisplayName("JWT 工具测试")
public class JwtUtilTest {

    @Test
    @DisplayName("创建 + 解析 + 验证 JWT")
    void testCreateParseVerify() {
        String token = JwtUtil.createJWT("user123", "000000");
        assertNotNull(token);
        assertFalse(token.isBlank());

        // 验证
        assertTrue(JwtUtil.verify(token));

        // 解析
        JWT parsed = JwtUtil.parseToken(token);
        assertEquals("user123", parsed.getPayload("userId").toString());
        assertEquals("000000", parsed.getPayload("tenantId").toString());

        System.out.println("✓ JWT 创建/解析/验证通过");
        System.out.println("  Token: " + token.substring(0, 30) + "...");
    }

    @Test
    @DisplayName("无租户的 JWT")
    void testJwtWithoutTenant() {
        String token = JwtUtil.createJWT("user456");
        assertTrue(JwtUtil.verify(token));
        JWT parsed = JwtUtil.parseToken(token);
        assertEquals("user456", parsed.getPayload("userId").toString());
        assertNull(parsed.getPayload("tenantId"));
        System.out.println("✓ 无租户 JWT 通过");
    }

    @Test
    @DisplayName("伪造的 Token 验证失败")
    void testInvalidToken() {
        assertFalse(JwtUtil.verify("invalid.token.here"));
        System.out.println("✓ 伪造 Token 验证失败");
    }
}
