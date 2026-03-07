package com.xinghuiTec.controller;

import com.alibaba.fastjson2.JSON;
import com.xinghuiTec.domain.dto.SysRoleAddDTO;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.DisplayName;
import org.springframework.http.MediaType;

import java.util.Arrays;
import java.util.List;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;
import static org.springframework.test.web.servlet.result.MockMvcResultHandlers.*;

/**
 * 角色管理控制器测试类
 * 测试角色管理模块的所有API端点
 * 
 * @author beidoa23
 * @since 2026-01-24
 */
@DisplayName("角色管理模块API测试")
public class SysRoleControllerTest extends BaseControllerTest {

    /**
     * 测试获取角色列表
     * GET /system/role/list
     */
    @Test
    @DisplayName("测试获取角色列表")
    public void testGetRoleList() throws Exception {
        mockMvc.perform(get("/system/role/list")
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
     * 测试按角色名查询
     */
    @Test
    @DisplayName("测试按角色名查询")
    public void testGetRoleListByName() throws Exception {
        mockMvc.perform(get("/system/role/list")
                .header("Authorization", getAuthHeader())
                .param("pageNum", "1")
                .param("pageSize", "10")
                .param("roleName", "管理员")
                .contentType(MediaType.APPLICATION_JSON))
                .andDo(print())
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value(200))
                .andExpect(jsonPath("$.data.records").isArray());
    }

    /**
     * 测试按角色权限字符查询
     */
    @Test
    @DisplayName("测试按角色权限字符查询")
    public void testGetRoleListByKey() throws Exception {
        mockMvc.perform(get("/system/role/list")
                .header("Authorization", getAuthHeader())
                .param("pageNum", "1")
                .param("pageSize", "10")
                .param("roleKey", "admin")
                .contentType(MediaType.APPLICATION_JSON))
                .andDo(print())
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value(200));
    }

    /**
     * 测试按状态查询
     */
    @Test
    @DisplayName("测试按状态查询")
    public void testGetRoleListByStatus() throws Exception {
        mockMvc.perform(get("/system/role/list")
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
     * 测试按日期范围查询
     */
    @Test
    @DisplayName("测试按日期范围查询")
    public void testGetRoleListByDateRange() throws Exception {
        mockMvc.perform(get("/system/role/list")
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
     * 测试获取所有角色列表（不分页）
     * GET /system/role/listAll
     */
    @Test
    @DisplayName("测试获取所有角色列表")
    public void testGetAllRoles() throws Exception {
        mockMvc.perform(get("/system/role/listAll")
                .header("Authorization", getAuthHeader())
                .contentType(MediaType.APPLICATION_JSON))
                .andDo(print())
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value(200))
                .andExpect(jsonPath("$.data").isArray());
    }

    /**
     * 测试获取角色详情
     * GET /system/role/get?roleId=xxx
     */
    @Test
    @DisplayName("测试获取角色详情")
    public void testGetRoleDetail() throws Exception {
        Long roleId = 1L; // 需要替换为实际的角色ID

        mockMvc.perform(get("/system/role/get")
                .header("Authorization", getAuthHeader())
                .param("roleId", String.valueOf(roleId))
                .contentType(MediaType.APPLICATION_JSON))
                .andDo(print())
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value(200))
                .andExpect(jsonPath("$.data.roleId").exists())
                .andExpect(jsonPath("$.data.roleName").exists())
                .andExpect(jsonPath("$.data.roleKey").exists());
    }

    /**
     * 测试新增角色
     * POST /system/role/add
     */
    @Test
    @DisplayName("测试新增角色")
    public void testAddRole() throws Exception {
        SysRoleAddDTO roleAddDTO = new SysRoleAddDTO();
        roleAddDTO.setRoleName("超级管理员" + System.currentTimeMillis());
        roleAddDTO.setRoleKey("super_admin" + System.currentTimeMillis());
        roleAddDTO.setStatus(1); // 1正常 0停用



        mockMvc.perform(post("/system/role/add")
                .header("Authorization", getAuthHeader())
                .contentType(MediaType.APPLICATION_JSON)
                .content(JSON.toJSONString(roleAddDTO)))
                .andDo(print())
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value(200));
    }

    /**
     * 测试新增角色 - 角色权限字符重复
     */
    @Test
    @DisplayName("测试新增角色-角色权限字符重复")
    public void testAddRoleWithDuplicateKey() throws Exception {
        SysRoleAddDTO roleAddDTO = new SysRoleAddDTO();
        roleAddDTO.setRoleName("测试角色");
        roleAddDTO.setRoleKey("admin"); // 已存在的角色权限字符
        roleAddDTO.setStatus(1);

        mockMvc.perform(post("/system/role/add")
                .header("Authorization", getAuthHeader())
                .contentType(MediaType.APPLICATION_JSON)
                .content(JSON.toJSONString(roleAddDTO)))
                .andDo(print())
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value(500)); // 角色权限字符已存在
    }

    /**
     * 测试新增角色 - 参数验证失败
     */
    @Test
    @DisplayName("测试新增角色-参数验证失败")
    public void testAddRoleWithInvalidParams() throws Exception {
        SysRoleAddDTO roleAddDTO = new SysRoleAddDTO();
        // 缺少必填字段

        mockMvc.perform(post("/system/role/add")
                .header("Authorization", getAuthHeader())
                .contentType(MediaType.APPLICATION_JSON)
                .content(JSON.toJSONString(roleAddDTO)))
                .andDo(print())
                .andExpect(status().is4xxClientError()); // 参数验证失败
    }

    /**
     * 测试编辑角色
     * POST /system/role/edit
     */
    @Test
    @DisplayName("测试编辑角色")
    public void testUpdateRole() throws Exception {
        SysRoleAddDTO roleAddDTO = new SysRoleAddDTO();
        roleAddDTO.setRoleId(1L); // 需要替换为实际的角色ID
        roleAddDTO.setRoleName("测试角色(已修改)");
        roleAddDTO.setRoleKey("test_role");
        roleAddDTO.setStatus(1);
        roleAddDTO.setMenuIds(Arrays.asList(1L, 2L, 3L, 4L));

        mockMvc.perform(post("/system/role/edit")
                .header("Authorization", getAuthHeader())
                .contentType(MediaType.APPLICATION_JSON)
                .content(JSON.toJSONString(roleAddDTO)))
                .andDo(print())
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value(200));
    }

    /**
     * 测试删除角色
     * POST /system/role/remove
     */
    @Test
    @DisplayName("测试删除角色")
    public void testDeleteRole() throws Exception {
        List<Long> roleIds = Arrays.asList(100L); // 需要替换为实际的角色ID

        mockMvc.perform(post("/system/role/remove")
                .header("Authorization", getAuthHeader())
                .contentType(MediaType.APPLICATION_JSON)
                .content(JSON.toJSONString(roleIds)))
                .andDo(print())
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value(200));
    }

    /**
     * 测试批量删除角色
     */
    @Test
    @DisplayName("测试批量删除角色")
    public void testBatchDeleteRoles() throws Exception {
        List<Long> roleIds = Arrays.asList(100L, 101L, 102L);

        mockMvc.perform(post("/system/role/remove")
                .header("Authorization", getAuthHeader())
                .contentType(MediaType.APPLICATION_JSON)
                .content(JSON.toJSONString(roleIds)))
                .andDo(print())
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value(200));
    }

    /**
     * 测试修改角色状态
     * POST /system/role/changeStatus
     */
    @Test
    @DisplayName("测试修改角色状态")
    public void testChangeRoleStatus() throws Exception {
        mockMvc.perform(post("/system/role/changeStatus")
                .header("Authorization", getAuthHeader())
                .param("roleId", "1")
                .param("status", "0") // 停用
                .contentType(MediaType.APPLICATION_JSON))
                .andDo(print())
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value(200));
    }

    /**
     * 测试获取角色菜单权限
     * GET /system/role/roleMenu/get?roleId=xxx
     */
    @Test
    @DisplayName("测试获取角色菜单权限")
    public void testGetRoleMenus() throws Exception {
        String roleId = "1";

        mockMvc.perform(get("/system/role/roleMenu/get")
                .header("Authorization", getAuthHeader())
                .param("roleId", roleId)
                .contentType(MediaType.APPLICATION_JSON))
                .andDo(print())
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value(200))
                .andExpect(jsonPath("$.data").isArray()); // 返回菜单ID列表
    }

    /**
     * 测试分配角色菜单权限
     * POST /system/role/roleMenu/edit
     */
    @Test
    @DisplayName("测试分配角色菜单权限")
    public void testAssignRoleMenus() throws Exception {
        mockMvc.perform(post("/system/role/roleMenu/edit")
                .header("Authorization", getAuthHeader())
                .param("roleId", "1")
                .param("menuIds", "1,2,3,4,5") // 菜单ID列表
                .contentType(MediaType.APPLICATION_JSON))
                .andDo(print())
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value(200));
    }

    /**
     * 测试清空角色菜单权限
     */
    @Test
    @DisplayName("测试清空角色菜单权限")
    public void testClearRoleMenus() throws Exception {
        mockMvc.perform(post("/system/role/roleMenu/edit")
                .header("Authorization", getAuthHeader())
                .param("roleId", "1")
                .param("menuIds", "") // 空字符串表示清空权限
                .contentType(MediaType.APPLICATION_JSON))
                .andDo(print())
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value(200));
    }

    /**
     * 测试无权限访问
     */
    @Test
    @DisplayName("测试无权限访问角色列表")
    public void testUnauthorizedAccess() throws Exception {
        mockMvc.perform(get("/system/role/list")
                .param("pageNum", "1")
                .param("pageSize", "10"))
                .andDo(print())
                .andExpect(status().isUnauthorized()); // 401未认证
    }
}
