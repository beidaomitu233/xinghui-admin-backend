package com.xinghuiTec.core;

import com.xinghuiTec.emues.ResultCodeEnum;
import com.xinghuiTec.utils.Result;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.SpringBootTest;

import static org.junit.jupiter.api.Assertions.*;

@SpringBootTest
@DisplayName("Result 响应体测试")
public class ResultTest {

    @Test
    @DisplayName("成功响应 - 无数据")
    void testOk() {
        Result<Void> result = Result.ok();
        assertEquals(200, result.getCode());
        assertEquals("操作成功", result.getMessage());
        assertNull(result.getData());
        System.out.println("✓ ok() code=" + result.getCode());
    }

    @Test
    @DisplayName("成功响应 - 带数据")
    void testOkWithData() {
        Result<String> result = Result.ok("hello");
        assertEquals(200, result.getCode());
        assertEquals("操作成功", result.getMessage());
        assertEquals("hello", result.getData());
        System.out.println("✓ ok(data) data=" + result.getData());
    }

    @Test
    @DisplayName("成功响应 - 自定义消息+数据")
    void testOkWithMessage() {
        Result<String> result = Result.ok("登录成功", "token123");
        assertEquals(200, result.getCode());
        assertEquals("登录成功", result.getMessage());
        assertEquals("token123", result.getData());
        System.out.println("✓ ok(msg, data)");
    }

    @Test
    @DisplayName("失败响应 - 默认")
    void testFail() {
        Result<Void> result = Result.fail();
        assertEquals(201, result.getCode());
        System.out.println("✓ fail() code=" + result.getCode());
    }

    @Test
    @DisplayName("失败响应 - 自定义消息")
    void testFailWithMessage() {
        Result<Void> result = Result.fail("参数错误");
        assertEquals(201, result.getCode());
        assertEquals("参数错误", result.getMessage());
        System.out.println("✓ fail(msg)");
    }

    @Test
    @DisplayName("失败响应 - 自定义码+消息")
    void testFailWithCode() {
        Result<Void> result = Result.fail(500, "服务器内部错误");
        assertEquals(500, result.getCode());
        assertEquals("服务器内部错误", result.getMessage());
        System.out.println("✓ fail(code, msg)");
    }

    @Test
    @DisplayName("失败响应 - ResultCodeEnum")
    void testFailWithEnum() {
        Result<Void> result = Result.fail(ResultCodeEnum.ADMIN_ACCESS_FORBIDDEN);
        assertEquals(ResultCodeEnum.ADMIN_ACCESS_FORBIDDEN.getCode(), result.getCode());
        System.out.println("✓ fail(enum)");
    }
}
