package com.xinghuiTec.social;

import com.xinghuiTec.social.config.SocialProperties;
import com.xinghuiTec.social.utils.SocialUtils;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;

import static org.junit.jupiter.api.Assertions.*;

@SpringBootTest
@DisplayName("社交登录模块测试")
public class SocialUtilsTest {

    @Autowired
    private SocialProperties socialProperties;

    @Test
    @DisplayName("SocialProperties 配置加载")
    void testPropertiesLoaded() {
        assertNotNull(socialProperties);
        System.out.println("✓ SocialProperties 已加载");
    }

    @Test
    @DisplayName("不支持的平台抛出异常")
    void testUnsupportedPlatform() {
        assertThrows(Exception.class, () ->
            SocialUtils.getAuthRequest("unknown_platform", socialProperties)
        );
        System.out.println("✓ 未知平台正确抛出异常");
    }

    @Test
    @DisplayName("支持的平台正常创建 AuthRequest")
    void testSupportedPlatform() {
        // Gitee 在 YAML 中有配置，应该能创建
        if (socialProperties.getType() != null && socialProperties.getType().containsKey("gitee")) {
            var request = SocialUtils.getAuthRequest("gitee", socialProperties);
            assertNotNull(request);
            System.out.println("✓ Gitee AuthRequest 创建成功: " + request.getClass().getSimpleName());
        } else {
            System.out.println("跳过: Gitee 未配置");
        }
    }
}
