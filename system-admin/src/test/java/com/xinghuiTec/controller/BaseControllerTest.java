package com.xinghuiTec.controller;

import com.alibaba.fastjson2.JSON;
import com.alibaba.fastjson2.JSONObject;
import com.xinghuiTec.domain.dto.loginDTO;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.TestInstance;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;
import jakarta.annotation.Resource;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;

/**
 * 控制器测试基类
 * 提供登录功能，子类继承后自动获取token
 * 
 * @author beidoa23
 * @since 2026-01-25
 */
@SpringBootTest
@AutoConfigureMockMvc
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
public abstract class BaseControllerTest {

    @Resource
    protected MockMvc mockMvc;

    /**
     * 登录后的JWT Token
     */
    protected String token;

    /**
     * 测试用户名
     */
    protected static final String TEST_USERNAME = "testuser2";

    /**
     * 测试密码
     */
    protected static final String TEST_PASSWORD = "123456";

    /**
     * 在所有测试之前执行登录，获取token
     */
    @BeforeAll
    public void loginBeforeAllTests() throws Exception {
        // 创建登录请求体
        loginDTO loginDto = new loginDTO();
        loginDto.setUsername(TEST_USERNAME);
        loginDto.setPassword(TEST_PASSWORD);

        // 执行登录请求
        MvcResult result = mockMvc.perform(post("/user/login")
                .contentType(MediaType.APPLICATION_JSON)
                .content(JSON.toJSONString(loginDto)))
                .andReturn();

        // 解析响应获取token
        String responseBody = result.getResponse().getContentAsString();
        JSONObject response = JSON.parseObject(responseBody);

        if (response.getInteger("code") == 200) {
            System.out.println(response);
            this.token = response.getString("data");
            System.out.println("✓ 测试登录成功，获取到token");
        } else {
            System.err.println("✗ 测试登录失败: " + response.getString("message"));
            throw new RuntimeException("测试登录失败，请确保测试用户存在: " + TEST_USERNAME);
        }
    }

    /**
     * 获取带认证的Authorization请求头值
     * 
     * @return Bearer token 格式的认证头
     */
    protected String getAuthHeader() {
        return token;
    }
}
