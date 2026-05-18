package com.xinghuiTec.controller;

import com.alibaba.fastjson2.JSON;
import com.xinghuiTec.domain.dto.SysUserAddDTO;
import jakarta.annotation.Resource;
import org.junit.jupiter.api.*;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.MediaType;
import org.springframework.security.test.context.support.WithMockUser;
import org.springframework.security.test.web.servlet.setup.SecurityMockMvcConfigurers;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import org.springframework.web.context.WebApplicationContext;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

@SpringBootTest
@TestMethodOrder(MethodOrderer.OrderAnnotation.class)
@DisplayName("用户管理 Controller 测试")
class SysUserControllerTest {

    @Resource
    private WebApplicationContext context;

    private MockMvc mockMvc;

    @BeforeEach
    void setUp() {
        mockMvc = MockMvcBuilders.webAppContextSetup(context)
            .apply(SecurityMockMvcConfigurers.springSecurity())
            .build();
    }

    @Test
    @Order(1)
    @WithMockUser(authorities = "system:user:list")
    @DisplayName("GET /system/user/list - 分页查询用户")
    void testListUsers() throws Exception {
        mockMvc.perform(get("/system/user/list")
                .param("pageNum", "1")
                .param("pageSize", "10"))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.code").exists());
        System.out.println("✓ 用户列表查询通过");
    }

    @Test
    @Order(2)
    @WithMockUser(authorities = "system:user:add")
    @DisplayName("POST /system/user/add - 新增用户")
    void testAddUser() throws Exception {
        SysUserAddDTO dto = new SysUserAddDTO();
        dto.setUsername("test_junit_" + System.currentTimeMillis());
        dto.setNickname("测试用户");
        dto.setPassword("123456");
        dto.setEmail("test@test.com");
        dto.setMobile("13900000001");
        dto.setStatus(1);

        mockMvc.perform(post("/system/user/add")
                .contentType(MediaType.APPLICATION_JSON)
                .content(JSON.toJSONString(dto)))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.code").exists());
        System.out.println("✓ 新增用户通过");
    }
}
