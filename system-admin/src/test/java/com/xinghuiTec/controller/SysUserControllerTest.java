package com.xinghuiTec.controller;

import com.alibaba.fastjson2.JSON;
import com.xinghuiTec.domain.dto.SysUserAddDTO;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.DisplayName;
import org.springframework.http.MediaType;
import org.springframework.mock.web.MockMultipartFile;

import java.util.Arrays;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;
import static org.springframework.test.web.servlet.result.MockMvcResultHandlers.*;

/**
 * 用户管理控制器测试类
 * 测试用户管理模块的所有API端点
 * 
 * @author beidoa23
 * @since 2026-01-24
 */
@DisplayName("用户管理模块API测试")
public class SysUserControllerTest extends BaseControllerTest {

    /**
     * 测试获取用户列表
     * GET /system/user/list
     */
    @Test
    @DisplayName("测试获取用户列表")
    public void testGetUserList() throws Exception {
        mockMvc.perform(get("/system/user/list")
                .header("Authorization", getAuthHeader())
                .param("pageNum", "1")
                .param("pageSize", "10")
                .contentType(MediaType.APPLICATION_JSON))
                .andDo(print())
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value(200))
                .andExpect(jsonPath("$.data").exists())
                .andExpect(jsonPath("$.data.records").isArray())
                .andExpect(jsonPath("$.data.total").exists());
    }

    /**
     * 测试按用户名查询用户
     */
    @Test
    @DisplayName("测试按用户名查询用户")
    public void testGetUserListByUsername() throws Exception {
        mockMvc.perform(get("/system/user/list")
                .header("Authorization", getAuthHeader())
                .param("pageNum", "1")
                .param("pageSize", "10")
                .param("username", "admin")
                .contentType(MediaType.APPLICATION_JSON))
                .andDo(print())
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value(200))
                .andExpect(jsonPath("$.data.records").isArray());
    }

    /**
     * 测试按手机号查询用户
     */
    @Test
    @DisplayName("测试按手机号查询用户")
    public void testGetUserListByPhone() throws Exception {
        mockMvc.perform(get("/system/user/list")
                .header("Authorization", getAuthHeader())
                .param("pageNum", "1")
                .param("pageSize", "10")
                .param("mobile", "15888888888")
                .contentType(MediaType.APPLICATION_JSON))
                .andDo(print())
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value(200))
                .andExpect(jsonPath("$.data.records").isArray());
    }

    /**
     * 测试按状态查询用户
     */
    @Test
    @DisplayName("测试按状态查询用户")
    public void testGetUserListByStatus() throws Exception {
        mockMvc.perform(get("/system/user/list")
                .header("Authorization", getAuthHeader())
                .param("pageNum", "1")
                .param("pageSize", "10")
                .param("status", "1") // 1正常 0停用
                .contentType(MediaType.APPLICATION_JSON))
                .andDo(print())
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value(200));
    }

    /**
     * 测试按日期范围查询用户
     */
    @Test
    @DisplayName("测试按日期范围查询用户")
    public void testGetUserListByDateRange() throws Exception {
        mockMvc.perform(get("/system/user/list")
                .header("Authorization", getAuthHeader())
                .param("pageNum", "1")
                .param("pageSize", "10")
                .param("beginTime", "2024-01-01")
                .param("endTime", "2024-12-31")
                .contentType(MediaType.APPLICATION_JSON))
                .andDo(print())
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value(200));
    }

    /**
     * 测试获取用户详情
     * GET /system/user/getuser?userId=xxx
     */
    @Test
    @DisplayName("测试获取用户详情")
    public void testGetUserDetail() throws Exception {
        String userId = "1"; // 需要替换为实际的用户ID

        mockMvc.perform(get("/system/user/getuser")
                .header("Authorization", getAuthHeader())
                .param("userId", userId)
                .contentType(MediaType.APPLICATION_JSON))
                .andDo(print())
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value(200))
                .andExpect(jsonPath("$.data.userInfo").exists())
                .andExpect(jsonPath("$.data.routers").isArray());
    }

    /**
     * 测试获取不存在的用户详情
     */
    @Test
    @DisplayName("测试获取不存在的用户详情")
    public void testGetNonExistentUserDetail() throws Exception {
        String userId = "999999";

        mockMvc.perform(get("/system/user/getuser")
                .header("Authorization", getAuthHeader())
                .param("userId", userId)
                .contentType(MediaType.APPLICATION_JSON))
                .andDo(print())
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value(500)); // 用户不存在错误码
    }

    /**
     * 测试新增用户
     * POST /system/user/add
     */
    @Test
    @DisplayName("测试新增用户")
    public void testAddUser() throws Exception {
        SysUserAddDTO userAddDTO = new SysUserAddDTO();
        userAddDTO.setUsername("testuser_" + System.currentTimeMillis());
        userAddDTO.setNickname("测试用户");
        userAddDTO.setPassword("Test@123456");
        userAddDTO.setMobile("13800138000");
        userAddDTO.setEmail("testuser@example.com");
        userAddDTO.setStatus(1); // 1正常 0停用
        userAddDTO.setRoleIds(Arrays.asList(1L)); // 普通角色

        mockMvc.perform(post("/system/user/add")
                .header("Authorization", getAuthHeader())
                .contentType(MediaType.APPLICATION_JSON)
                .content(JSON.toJSONString(userAddDTO)))
                .andDo(print())
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value(200))
                .andExpect(jsonPath("$.data").exists()); // 返回新用户的userId
    }

    /**
     * 测试新增用户 - 用户名重复
     */
    @Test
    @DisplayName("测试新增用户-用户名重复")
    public void testAddUserWithDuplicateUsername() throws Exception {
        SysUserAddDTO userAddDTO = new SysUserAddDTO();
        userAddDTO.setUsername("admin"); // 已存在的用户名
        userAddDTO.setNickname("测试用户");
        userAddDTO.setPassword("Test@123456");
        userAddDTO.setMobile("13800138000");
        userAddDTO.setEmail("testuser@example.com");
        userAddDTO.setStatus(1);
        userAddDTO.setRoleIds(Arrays.asList(1L));

        mockMvc.perform(post("/system/user/add")
                .header("Authorization", getAuthHeader())
                .contentType(MediaType.APPLICATION_JSON)
                .content(JSON.toJSONString(userAddDTO)))
                .andDo(print())
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value(500)); // 用户名已存在错误
    }

    /**
     * 测试新增用户 - 参数验证失败
     */
    @Test
    @DisplayName("测试新增用户-参数验证失败")
    public void testAddUserWithInvalidParams() throws Exception {
        SysUserAddDTO userAddDTO = new SysUserAddDTO();
        // 缺少必填字段

        mockMvc.perform(post("/system/user/add")
                .header("Authorization", getAuthHeader())
                .contentType(MediaType.APPLICATION_JSON)
                .content(JSON.toJSONString(userAddDTO)))
                .andDo(print())
                .andExpect(status().is4xxClientError()); // 参数验证失败
    }

    /**
     * 测试编辑用户
     * POST /system/user/edit
     */
    @Test
    @DisplayName("测试编辑用户")
    public void testUpdateUser() throws Exception {
        SysUserAddDTO userAddDTO = new SysUserAddDTO();
        userAddDTO.setUserId("1"); // 需要替换为实际的用户ID
        userAddDTO.setUsername("testuser");
        userAddDTO.setNickname("测试用户(已修改)");
        userAddDTO.setMobile("13800138000");
        userAddDTO.setEmail("testuser@example.com");
        userAddDTO.setStatus(1);
        userAddDTO.setRoleIds(Arrays.asList(1L, 2L));

        mockMvc.perform(post("/system/user/edit")
                .header("Authorization", getAuthHeader())
                .contentType(MediaType.APPLICATION_JSON)
                .content(JSON.toJSONString(userAddDTO)))
                .andDo(print())
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value(200));
    }

    /**
     * 测试删除用户
     * POST /system/user/remove?userId=xxx
     */
    @Test
    @DisplayName("测试删除用户")
    public void testDeleteUser() throws Exception {
        String userId = "999"; // 需要替换为实际的用户ID

        mockMvc.perform(post("/system/user/remove")
                .header("Authorization", getAuthHeader())
                .param("userId", userId)
                .contentType(MediaType.APPLICATION_JSON))
                .andDo(print())
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value(200));
    }

    /**
     * 测试重置密码
     * POST /system/user/resetPwd?userId=xxx
     */
    @Test
    @DisplayName("测试重置密码")
    public void testResetPassword() throws Exception {
        String userId = "1"; // 需要替换为实际的用户ID

        mockMvc.perform(post("/system/user/resetPwd")
                .header("Authorization", getAuthHeader())
                .param("userId", userId)
                .contentType(MediaType.APPLICATION_JSON))
                .andDo(print())
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value(200));
    }

    /**
     * 测试下载用户导入模板
     * GET /system/user/importTemplate
     */
    @Test
    @DisplayName("测试下载用户导入模板")
    public void testDownloadTemplate() throws Exception {
        mockMvc.perform(get("/system/user/importTemplate")
                .header("Authorization", getAuthHeader()))
                .andDo(print())
                .andExpect(status().isOk())
                .andExpect(header().exists("Content-Disposition"));
    }

    /**
     * 测试导入用户
     * POST /system/user/import
     */
    @Test
    @DisplayName("测试导入用户")
    public void testImportUsers() throws Exception {
        // 创建模拟的Excel文件
        MockMultipartFile file = new MockMultipartFile(
                "file",
                "users.xlsx",
                "application/vnd.openxmlformats-officedocument.spreadsheetml.sheet",
                "test data".getBytes());

        mockMvc.perform(multipart("/system/user/import")
                .file(file)
                .header("Authorization", getAuthHeader()))
                .andDo(print())
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value(200))
                .andExpect(jsonPath("$.data").exists()); // 导入结果信息
    }

    /**
     * 测试导出用户
     * GET /system/user/export
     */
    @Test
    @DisplayName("测试导出用户")
    public void testExportUsers() throws Exception {
        mockMvc.perform(get("/system/user/export")
                .header("Authorization", getAuthHeader())
                .param("pageNum", "1")
                .param("pageSize", "100"))
                .andDo(print())
                .andExpect(status().isOk())
                .andExpect(header().exists("Content-Disposition"));
    }

    /**
     * 测试导出用户 - 带查询条件
     */
    @Test
    @DisplayName("测试导出用户-带查询条件")
    public void testExportUsersWithConditions() throws Exception {
        mockMvc.perform(get("/system/user/export")
                .header("Authorization", getAuthHeader())
                .param("pageNum", "1")
                .param("pageSize", "100")
                .param("status", "1")
                .param("beginTime", "2024-01-01")
                .param("endTime", "2024-12-31"))
                .andDo(print())
                .andExpect(status().isOk())
                .andExpect(header().exists("Content-Disposition"));
    }

    /**
     * 测试无权限访问
     */
    @Test
    @DisplayName("测试无权限访问用户列表")
    public void testUnauthorizedAccess() throws Exception {
        mockMvc.perform(get("/system/user/list")
                .param("pageNum", "1")
                .param("pageSize", "10"))
                .andDo(print())
                .andExpect(status().isUnauthorized()); // 401未认证
    }
}
