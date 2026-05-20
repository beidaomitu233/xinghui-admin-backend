package com.xinghuiTec.mail;

import cn.hutool.extra.mail.MailAccount;
import com.xinghuiTec.mail.utils.MailUtils;
import com.xinghuiTec.utils.SpringUtils;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.SpringBootTest;

import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;

import static org.junit.jupiter.api.Assertions.*;

@SpringBootTest
@DisplayName("邮件模块测试")
class MailUtilsTest {

    @Test
    @DisplayName("发送测试邮件到 test@example.com")
    void testSendRealEmail() {
        // 检查 MailAccount Bean 是否存在（mail.enabled=true 时创建）
        MailAccount account;
        try {
            account = SpringUtils.getBean(MailAccount.class);
        } catch (Exception e) {
            account = null;
        }

        if (account == null) {
            System.out.println("============================================================");
            System.out.println("⚠ 邮件未配置！请先在 application.yml 中设置：");
            System.out.println("  mail:");
            System.out.println("    enabled: true");
            System.out.println("    host: smtp.qq.com");
            System.out.println("    port: 465");
            System.out.println("    auth: true");
            System.out.println("    from: 你的QQ邮箱@qq.com");
            System.out.println("    user: 你的QQ邮箱@qq.com");
            System.out.println("    pass: QQ邮箱授权码(非登录密码)");
            System.out.println("    sslEnable: true");
            System.out.println("============================================================");
            return;
        }

        String now = LocalDateTime.now().format(DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss"));
        String subject = "兴辉Admin 测试邮件 - " + now;
        String content = String.format("""
            <h2>兴辉Admin 邮件模块测试</h2>
            <p>这是一封测试邮件，验证系统邮件功能是否正常。</p>
            <hr/>
            <p><b>发送时间：</b>%s</p>
            <p><b>邮件服务器：</b>%s</p>
            <p><b>发件人：</b>%s</p>
            <p>✅ 如果您收到此邮件，说明邮件模块配置正确！</p>
            """, now, account.getHost(), account.getFrom());

        System.out.println("正在发送邮件...");
        System.out.println("  收件人: test@example.com");
        System.out.println("  标题: " + subject);
        System.out.println("  服务器: " + account.getHost());

        String messageId = MailUtils.sendHtml("test@example.com", subject, content);

        assertNotNull(messageId);
        assertFalse(messageId.isBlank());
        System.out.println("✓ 邮件发送成功！message-id: " + messageId);
    }
}
