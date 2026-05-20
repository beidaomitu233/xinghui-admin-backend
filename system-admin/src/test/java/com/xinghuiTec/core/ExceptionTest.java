package com.xinghuiTec.core;

import com.xinghuiTec.exception.ServiceException;
import com.xinghuiTec.exception.TenantException;
import com.xinghuiTec.exception.base.BaseException;
import com.xinghuiTec.exception.user.UserException;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.SpringBootTest;

import static org.junit.jupiter.api.Assertions.*;

@SpringBootTest
@DisplayName("异常体系测试")
public class ExceptionTest {

    @Test
    @DisplayName("BaseException - 默认消息")
    void testBaseExceptionDefault() {
        BaseException ex = new BaseException("测试错误");
        assertEquals("测试错误", ex.getMessage());
        System.out.println("✓ BaseException 默认消息: " + ex.getMessage());
    }

    @Test
    @DisplayName("BaseException - 模块+消息")
    void testBaseExceptionModule() {
        BaseException ex = new BaseException("test", "模块测试错误");
        assertEquals("test", ex.getModule());
        assertEquals("模块测试错误", ex.getMessage()); // 实际取 defaultMessage
        System.out.println("✓ BaseException 模块: " + ex.getModule());
    }

    @Test
    @DisplayName("ServiceException - 占位符格式化")
    void testServiceExceptionFormat() {
        ServiceException ex = new ServiceException("用户[{}]不存在", "张三");
        assertEquals("用户[张三]不存在", ex.getMessage());
        System.out.println("✓ ServiceException 占位符: " + ex.getMessage());
    }

    @Test
    @DisplayName("ServiceException - 自定义错误码")
    void testServiceExceptionCode() {
        ServiceException ex = new ServiceException("错误", 500);
        assertEquals(500, ex.getCode());
        assertEquals("错误", ex.getMessage());
        System.out.println("✓ ServiceException 错误码: " + ex.getCode());
    }

    @Test
    @DisplayName("TenantException - 默认消息")
    void testTenantException() {
        TenantException ex = new TenantException("租户不存在");
        assertEquals("tenant", ex.getModule());
        assertNotNull(ex.getMessage());
        System.out.println("✓ TenantException: " + ex.getMessage());
    }

    @Test
    @DisplayName("TenantException - i18n编码")
    void testTenantExceptionI18n() {
        TenantException ex = new TenantException("tenant.not.exists");
        assertEquals("tenant", ex.getModule());
        System.out.println("✓ TenantException i18n: " + ex.getMessage());
    }

    @Test
    @DisplayName("UserException - 继承链")
    void testUserException() {
        UserException ex = new UserException("user.not.exists", new Object[]{"张三"});
        assertTrue(ex instanceof BaseException);
        assertEquals("user", ex.getModule());
        System.out.println("✓ UserException 继承 BaseException");
    }
}
