package com.xinghuiTec.mail;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.SpringBootTest;

import static org.junit.jupiter.api.Assertions.*;

@SpringBootTest
@DisplayName("邮件模块测试")
public class MailUtilsTest {

    @Test
    @DisplayName("MailUtils 类存在")
    void testClassExists() {
        // MailAccount 仅在 mail.enabled=true 时创建，正常模式下为 null
        // 验证 MailUtils 类加载
        try {
            Class<?> clazz = Class.forName("com.xinghuiTec.mail.utils.MailUtils");
            assertNotNull(clazz);
            System.out.println("✓ MailUtils 类加载成功");
        } catch (ClassNotFoundException e) {
            fail("MailUtils 类未找到");
        }
    }
}
