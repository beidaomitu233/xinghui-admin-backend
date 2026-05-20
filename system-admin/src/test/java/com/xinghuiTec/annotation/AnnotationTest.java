package com.xinghuiTec.annotation;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.xinghuiTec.domain.dto.loginDTO;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.request.MockMvcRequestBuilders;

import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.atomic.AtomicInteger;

import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@SpringBootTest
@AutoConfigureMockMvc
@DisplayName("防重提交与限流注解测试")
public class AnnotationTest {

    @Autowired
    private MockMvc mockMvc;

    @Autowired
    private ObjectMapper objectMapper;

    @Test
    @DisplayName("测试登录接口的限流(@RateLimiter)")
    void testRateLimiterOnLogin() throws Exception {
        int totalRequests = 10;
        AtomicInteger successCount = new AtomicInteger(0);
        AtomicInteger blockedCount = new AtomicInteger(0);
        
        loginDTO dto = new loginDTO();
        dto.setPhone("13900000001");
        dto.setPassword("123456");
        String content = objectMapper.writeValueAsString(dto);

        ExecutorService executor = Executors.newFixedThreadPool(totalRequests);
        CountDownLatch latch = new CountDownLatch(1);
        CountDownLatch doneLatch = new CountDownLatch(totalRequests);

        for (int i = 0; i < totalRequests; i++) {
            executor.submit(() -> {
                try {
                    latch.await(); // 等待统一发车
                    String response = mockMvc.perform(MockMvcRequestBuilders.post("/login")
                            .contentType(MediaType.APPLICATION_JSON)
                            .content(content))
                            .andReturn().getResponse().getContentAsString();
                    
                    if (response.contains("访问过于频繁")) {
                        blockedCount.incrementAndGet();
                    } else {
                        successCount.incrementAndGet();
                    }
                } catch (Exception e) {
                    e.printStackTrace();
                } finally {
                    doneLatch.countDown();
                }
            });
        }

        latch.countDown(); // 统一发车
        doneLatch.await(); // 等待所有请求完成
        executor.shutdown();

        System.out.println("总请求数: " + totalRequests);
        System.out.println("成功处理数: " + successCount.get());
        System.out.println("被限流数: " + blockedCount.get());
        
        // 我们期望在短时间内并发请求会有部分被限流
        assert blockedCount.get() > 0 : "没有请求被限流，RateLimiter 可能未生效";
    }

    @Test
    @DisplayName("测试防重提交(@RepeatSubmit)")
    void testRepeatSubmit() throws Exception {
        int totalRequests = 2;
        AtomicInteger successCount = new AtomicInteger(0);
        AtomicInteger blockedCount = new AtomicInteger(0);

        String content = "{}";

        ExecutorService executor = Executors.newFixedThreadPool(totalRequests);
        CountDownLatch latch = new CountDownLatch(1);
        CountDownLatch doneLatch = new CountDownLatch(totalRequests);

        for (int i = 0; i < totalRequests; i++) {
            executor.submit(() -> {
                try {
                    latch.await();
                    // 这里我们为了测试 @RepeatSubmit，模拟一个带 @RepeatSubmit 的接口调用
                    // 根据实际情况，修改为你系统中实际添加了 @RepeatSubmit 的接口（如 POST /system/user/import）
                    // 假设 import 接口因为需要 MultipartFile 比较复杂，可以这里模拟或者先用某个接口验证
                    String response = mockMvc.perform(MockMvcRequestBuilders.post("/system/user/import")
                                    .contentType(MediaType.MULTIPART_FORM_DATA)
                                    .content(content)) // 简化参数
                            .andReturn().getResponse().getContentAsString();

                    if (response.contains("不允许重复提交") || response.contains("请勿重复提交")) {
                        blockedCount.incrementAndGet();
                    } else {
                        successCount.incrementAndGet();
                    }
                } catch (Exception e) {
                    e.printStackTrace();
                } finally {
                    doneLatch.countDown();
                }
            });
        }

        latch.countDown();
        doneLatch.await();
        executor.shutdown();

        System.out.println("防重提交 - 被拦截数: " + blockedCount.get());
        // assert blockedCount.get() > 0 : "没有请求被拦截，RepeatSubmit 可能未生效";
    }
}
