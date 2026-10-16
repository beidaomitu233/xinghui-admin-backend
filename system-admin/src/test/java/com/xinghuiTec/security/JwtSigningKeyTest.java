package com.xinghuiTec.security;

import com.xinghuiTec.constants.JwtConstants;
import com.xinghuiTec.config.JwtSigningKeyConfiguration;
import org.junit.jupiter.api.Test;
import org.springframework.mock.env.MockEnvironment;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

class JwtSigningKeyTest {

    @Test
    void rejectsMissingBlankAndShortKeysWithoutEchoingInput() {
        assertEquals("JWT_SECRET is required", assertThrows(IllegalStateException.class,
                () -> JwtConstants.requireSigningKey(null)).getMessage());
        assertThrows(IllegalStateException.class, () -> JwtConstants.requireSigningKey(" "));
        assertEquals("JWT_SECRET must contain at least 32 UTF-8 bytes",
                assertThrows(IllegalStateException.class,
                        () -> JwtConstants.requireSigningKey("short-test-only")).getMessage());
    }

    @Test
    void acceptsExplicitUtf8Key() {
        assertEquals(34, JwtConstants.requireSigningKey("neutral-test-only-key-32-bytes!!!!").length);
    }

    @Test
    void disabledDruidNeedsNoConsoleCredentials() {
        new JwtSigningKeyConfiguration(new MockEnvironment()).afterPropertiesSet();
    }

    @Test
    void enabledDruidRejectsAbsentCredentials() {
        MockEnvironment environment = new MockEnvironment()
                .withProperty("spring.datasource.druid.stat-view-servlet.enabled", "true");
        assertThrows(IllegalStateException.class,
                () -> new JwtSigningKeyConfiguration(environment).afterPropertiesSet());
    }
}
