package com.xinghuiTec.controller;

import jakarta.annotation.Resource;
import org.junit.jupiter.api.*;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.security.test.context.support.WithMockUser;
import org.springframework.security.test.web.servlet.setup.SecurityMockMvcConfigurers;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import org.springframework.web.context.WebApplicationContext;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

@SpringBootTest
@TestMethodOrder(MethodOrderer.OrderAnnotation.class)
@DisplayName("角色管理 Controller 测试")
class SysRoleControllerTest {

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
    @WithMockUser(authorities = "system:role:list")
    @DisplayName("GET /system/role/list - 查询角色列表")
    void testListRoles() throws Exception {
        mockMvc.perform(get("/system/role/list")
                .param("pageNum", "1")
                .param("pageSize", "10"))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.code").exists());
        System.out.println("✓ 角色列表查询通过");
    }
}
