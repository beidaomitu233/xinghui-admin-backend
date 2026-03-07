package com.xinghuiTec.controller;

import com.alibaba.fastjson2.JSON;
import com.xinghuiTec.domain.dto.loginDTO;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.DisplayName;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;
import jakarta.annotation.Resource;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;
import static org.springframework.test.web.servlet.result.MockMvcResultHandlers.*;

/**
 * 登录控制器测试类
 * 测试认证模块的所有API端点
 * 注意：此类不继承BaseControllerTest，因为需要测试未登录状态
 * 
 * @author beidoa23
 * @since 2026-01-24
 */
@SpringBootTest
@AutoConfigureMockMvc
@DisplayName("认证模块API测试")
public class LoginControllerTest {

    @Resource
    private MockMvc mockMvc;

    private String token; // 存储登录后的token

    /**
     * 测试用户名和密码
     */
    private static final String TEST_USERNAME = "testuser2";
    private static final String TEST_PASSWORD = "123456";

    /**
     * 测试获取验证码
     * GET /captcha
     */
    @Test
    @DisplayName("测试获取验证码")
    public void testGetCaptcha() throws Exception {
        mockMvc.perform(get("/captcha")
                .contentType(MediaType.APPLICATION_JSON))
                .andDo(print())
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value(200))
                .andExpect(jsonPath("$.data").exists())
                .andExpect(jsonPath("$.data.uuid").exists());
    }

    /**
     * 测试用户登录 - 成功场景
     * POST /user/login
     */
    @Test
    @DisplayName("测试用户登录-成功")
    public void testLoginSuccess() throws Exception {
        loginDTO loginDto = new loginDTO();
        loginDto.setUsername(TEST_USERNAME);
        loginDto.setPassword(TEST_PASSWORD);

        MvcResult result = mockMvc.perform(post("/user/login")
                .contentType(MediaType.APPLICATION_JSON)
                .content(JSON.toJSONString(loginDto)))
                .andDo(print())
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value(200))
                .andExpect(jsonPath("$.data").exists()) // token
                .andReturn();

        // 保存token用于后续测试
        String response = result.getResponse().getContentAsString();
        System.out.println("登录响应: " + response);
    }

    /**
     * 测试用户登录 - 用户名错误
     */
    @Test
    @DisplayName("测试用户登录-用户名错误")
    public void testLoginWithWrongUsername() throws Exception {
        loginDTO loginDto = new loginDTO();
        loginDto.setUsername("wronguser");
        loginDto.setPassword(TEST_PASSWORD);

        mockMvc.perform(post("/user/login")
                .contentType(MediaType.APPLICATION_JSON)
                .content(JSON.toJSONString(loginDto)))
                .andDo(print())
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value(500)); // 登录失败
    }

    /**
     * 测试用户登录 - 密码错误
     */
    @Test
    @DisplayName("测试用户登录-密码错误")
    public void testLoginWithWrongPassword() throws Exception {
        loginDTO loginDto = new loginDTO();
        loginDto.setUsername(TEST_USERNAME);
        loginDto.setPassword("wrongpassword");

        mockMvc.perform(post("/user/login")
                .contentType(MediaType.APPLICATION_JSON)
                .content(JSON.toJSONString(loginDto)))
                .andDo(print())
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value(500)); // 登录失败
    }

    /**
     * 测试用户登录 - 参数验证失败
     */
    @Test
    @DisplayName("测试用户登录-参数验证失败")
    public void testLoginWithEmptyFields() throws Exception {
        loginDTO loginDto = new loginDTO();
        // 不设置username和password

        mockMvc.perform(post("/user/login")
                .contentType(MediaType.APPLICATION_JSON)
                .content(JSON.toJSONString(loginDto)))
                .andDo(print())
                .andExpect(status().is4xxClientError()); // 参数验证失败
    }

    /**
     * 测试获取用户信息 - 需要登录
     * GET /user/info
     */
    @Test
    @DisplayName("测试获取用户信息")
    public void testGetUserInfo() throws Exception {
        // 先登录获取token
        String authToken = loginAndGetToken();

        mockMvc.perform(get("/user/info")
                .header("Authorization", authToken)
                .contentType(MediaType.APPLICATION_JSON))
                .andDo(print())
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value(200))
                .andExpect(jsonPath("$.data").exists())
                .andExpect(jsonPath("$.data.userName").exists())
                .andExpect(jsonPath("$.data.roles").exists())
                .andExpect(jsonPath("$.data.permissions").exists());
    }

    /**
     * 测试获取用户信息 - 未登录
     */
    @Test
    @DisplayName("测试获取用户信息-未登录")
    public void testGetUserInfoWithoutToken() throws Exception {
        mockMvc.perform(get("/user/info")
                .contentType(MediaType.APPLICATION_JSON))
                .andDo(print())
                .andExpect(status().isUnauthorized()); // 401未认证
    }

    /**
     * 测试获取用户路由
     * GET /user/router
     */
    @Test
    @DisplayName("测试获取用户路由")
    public void testGetUserRouter() throws Exception {
        // 先登录获取token
        String authToken = loginAndGetToken();

        mockMvc.perform(get("/user/router")
                .header("Authorization", authToken)
                .contentType(MediaType.APPLICATION_JSON))
                .andDo(print())
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value(200))
                .andExpect(jsonPath("$.data").isArray())
                .andExpect(jsonPath("$.data[0].path").exists())
                .andExpect(jsonPath("$.data[0].name").exists());
    }

    /**
     * 测试用户登出
     * GET /user/logout
     */
    @Test
    @DisplayName("测试用户登出")
    public void testLogout() throws Exception {
        // 先登录获取token
        String authToken = loginAndGetToken();

        mockMvc.perform(get("/user/logout")
                .header("Authorization", authToken)
                .contentType(MediaType.APPLICATION_JSON))
                .andDo(print())
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value(200));
    }

    /**
     * 登录并获取token的辅助方法
     */
    private String loginAndGetToken() throws Exception {
        loginDTO loginDto = new loginDTO();
        loginDto.setUsername(TEST_USERNAME);
        loginDto.setPassword(TEST_PASSWORD);

        MvcResult result = mockMvc.perform(post("/user/login")
                .contentType(MediaType.APPLICATION_JSON)
                .content(JSON.toJSONString(loginDto)))
                .andReturn();

        String responseBody = result.getResponse().getContentAsString();
        com.alibaba.fastjson2.JSONObject response = JSON.parseObject(responseBody);
        return response.getString("data");
    }
}
