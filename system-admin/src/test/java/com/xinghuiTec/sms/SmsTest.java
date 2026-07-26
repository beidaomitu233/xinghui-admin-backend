package com.xinghuiTec.sms;

import com.xinghuiTec.sms.config.SmsAutoConfiguration;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.ApplicationContext;

import static org.junit.jupiter.api.Assertions.*;

@SpringBootTest
@DisplayName("SMS 模块测试")
public class SmsTest {

    @Autowired
    private ApplicationContext context;

    @Test
    @DisplayName("SMS 自动配置类是否加载")
    void testAutoConfigLoaded() {
        SmsAutoConfiguration config = context.getBean(SmsAutoConfiguration.class);
        assertNotNull(config);
        System.out.println("✓ SmsAutoConfiguration 已加载");
    }

    @Test
    @DisplayName("sms4j SmsDao Bean 是否注册")
    void testSmsDaoBean() {
        Object smsDao = context.getBean("smsDao");
        assertNotNull(smsDao);
        System.out.println("✓ SmsDao Bean: " + smsDao.getClass().getSimpleName());
    }
}
