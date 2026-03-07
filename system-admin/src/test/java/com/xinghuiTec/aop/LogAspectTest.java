package com.xinghuiTec.aop;

import com.xinghuiTec.annotation.Log;
import com.xinghuiTec.domain.dto.SysOperLogQueryDTO;
import com.xinghuiTec.domain.entity.SysOperLog;
import com.xinghuiTec.emues.BusinessType;
import com.xinghuiTec.service.SysOperLogService;
import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.stereotype.Component;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import jakarta.annotation.Resource;

import static org.junit.jupiter.api.Assertions.*;

/**
 * 日志切面测试类
 * 测试 @Log 注解的 AOP 拦截功能
 * 
 * @author beidoa23
 * @since 2026-01-23
 */
@SpringBootTest
public class LogAspectTest {

    @Resource
    private SysOperLogService sysOperLogService;

    @Resource
    private TestService testService;

    /**
     * 测试AOP是否正确拦截并记录日志
     */
    @Test
    public void testLogAspect() throws Exception {
        // 清空之前的测试数据
        SysOperLogQueryDTO queryDTO = new SysOperLogQueryDTO();
        queryDTO.setTitle("AOP测试");
        long beforeCount = sysOperLogService.getOperLogList(queryDTO).getTotal();

        // 调用带有 @Log 注解的方法
        testService.testMethod("test parameter");

        // 等待异步日志保存完成
        Thread.sleep(1500);

        // 验证日志是否已记录
        long afterCount = sysOperLogService.getOperLogList(queryDTO).getTotal();
        assertTrue(afterCount > beforeCount, "日志应该被记录");
    }

    /**
     * 测试异常情况下的日志记录
     */
    @Test
    public void testLogAspectWithException() throws Exception {
        SysOperLogQueryDTO queryDTO = new SysOperLogQueryDTO();
        queryDTO.setStatus(1); // 查询异常日志
        long beforeCount = sysOperLogService.getOperLogList(queryDTO).getTotal();

        // 调用会抛出异常的方法
        try {
            testService.testMethodWithException();
        } catch (Exception e) {
            // 预期会抛出异常
        }

        // 等待异步日志保存完成
        Thread.sleep(1500);

        // 验证异常日志是否已记录
        long afterCount = sysOperLogService.getOperLogList(queryDTO).getTotal();
        assertTrue(afterCount > beforeCount, "异常日志应该被记录");
    }

    /**
     * 测试服务类
     * 用于模拟带有 @Log 注解的方法
     */
    @Component
    public static class TestService {

        @Log(title = "AOP测试", businessType = BusinessType.INSERT)
        public String testMethod(String param) {
            return "success: " + param;
        }

        @Log(title = "AOP异常测试", businessType = BusinessType.UPDATE)
        public void testMethodWithException() {
            throw new RuntimeException("测试异常");
        }
    }
}
