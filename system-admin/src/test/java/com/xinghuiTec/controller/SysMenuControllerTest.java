package com.xinghuiTec.controller;

import com.alibaba.fastjson2.JSON;
import com.xinghuiTec.domain.dto.SysMenuAddDTO;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.DisplayName;
import org.springframework.http.MediaType;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;
import static org.springframework.test.web.servlet.result.MockMvcResultHandlers.*;

/**
 * 菜单管理控制器测试类
 * 测试菜单管理模块的所有API端点
 * 
 * @author beidoa23
 * @since 2026-01-24
 */
@DisplayName("菜单管理模块API测试")
public class SysMenuControllerTest extends BaseControllerTest {

    /**
     * 测试获取菜单树
     * GET /system/menu/treeselect
     */
    @Test
    @DisplayName("测试获取菜单树")
    public void testGetMenuTree() throws Exception {
        mockMvc.perform(get("/system/menu/treeselect")
                .header("Authorization", getAuthHeader())
                .contentType(MediaType.APPLICATION_JSON))
                .andDo(print())
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value(200))
                .andExpect(jsonPath("$.data").isArray())
                .andExpect(jsonPath("$.data[0].menuId").exists())
                .andExpect(jsonPath("$.data[0].menuName").exists())
                .andExpect(jsonPath("$.data[0].children").exists());
    }

    /**
     * 测试获取菜单列表（带查询条件）
     * GET /system/menu/list
     */
    @Test
    @DisplayName("测试获取菜单列表")
    public void testGetMenuList() throws Exception {
        mockMvc.perform(get("/system/menu/list")
                .header("Authorization", getAuthHeader())
                .contentType(MediaType.APPLICATION_JSON))
                .andDo(print())
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value(200))
                .andExpect(jsonPath("$.data").isArray());
    }

    /**
     * 测试按菜单名称查询
     */
    @Test
    @DisplayName("测试按菜单名称查询")
    public void testGetMenuListByName() throws Exception {
        mockMvc.perform(get("/system/menu/list")
                .header("Authorization", getAuthHeader())
                .param("menuName", "用户管理")
                .contentType(MediaType.APPLICATION_JSON))
                .andDo(print())
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value(200))
                .andExpect(jsonPath("$.data").isArray());
    }

    /**
     * 测试按状态查询
     */
    @Test
    @DisplayName("测试按状态查询")
    public void testGetMenuListByStatus() throws Exception {
        mockMvc.perform(get("/system/menu/list")
                .header("Authorization", getAuthHeader())
                .param("status", "0") // 0正常 1停用
                .contentType(MediaType.APPLICATION_JSON))
                .andDo(print())
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value(200));
    }

    /**
     * 测试获取菜单详情
     * GET /system/menu/get?menuId=xxx
     */
    @Test
    @DisplayName("测试获取菜单详情")
    public void testGetMenuDetail() throws Exception {
        String menuId = "1"; // 需要替换为实际的菜单ID

        mockMvc.perform(get("/system/menu/get")
                .header("Authorization", getAuthHeader())
                .param("menuId", menuId)
                .contentType(MediaType.APPLICATION_JSON))
                .andDo(print())
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value(200))
                .andExpect(jsonPath("$.data.menuId").exists())
                .andExpect(jsonPath("$.data.menuName").exists())
                .andExpect(jsonPath("$.data.menuType").exists());
    }

    /**
     * 测试新增目录菜单
     * POST /system/menu/add
     */
    @Test
    @DisplayName("测试新增目录菜单")
    public void testAddDirectoryMenu() throws Exception {
        SysMenuAddDTO menuAddDTO = new SysMenuAddDTO();
        menuAddDTO.setMenuName("测试目录_" + System.currentTimeMillis());
        menuAddDTO.setMenuType("M"); // M目录 C菜单 F按钮
        menuAddDTO.setParentId(0L); // 顶级目录
        menuAddDTO.setOrderNum(100);
        menuAddDTO.setPath("/test");
        menuAddDTO.setIcon("el-icon-setting");
        menuAddDTO.setVisible("0"); // 0显示 1隐藏
        menuAddDTO.setStatus("0"); // 0正常 1停用

        mockMvc.perform(post("/system/menu/add")
                .header("Authorization", getAuthHeader())
                .contentType(MediaType.APPLICATION_JSON)
                .content(JSON.toJSONString(menuAddDTO)))
                .andDo(print())
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value(200));
    }

    /**
     * 测试新增菜单
     */
    @Test
    @DisplayName("测试新增菜单")
    public void testAddMenu() throws Exception {
        SysMenuAddDTO menuAddDTO = new SysMenuAddDTO();
        menuAddDTO.setMenuName("测试菜单_" + System.currentTimeMillis());
        menuAddDTO.setMenuType("C"); // C菜单
        menuAddDTO.setParentId(1L); // 父菜单ID
        menuAddDTO.setOrderNum(101);
        menuAddDTO.setPath("/test/menu");
        menuAddDTO.setComponent("test/menu/index");
        menuAddDTO.setPerms("test:menu:list");
        menuAddDTO.setIcon("el-icon-s-help");
        menuAddDTO.setVisible("0");
        menuAddDTO.setStatus("0");

        mockMvc.perform(post("/system/menu/add")
                .header("Authorization", getAuthHeader())
                .contentType(MediaType.APPLICATION_JSON)
                .content(JSON.toJSONString(menuAddDTO)))
                .andDo(print())
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value(200));
    }

    /**
     * 测试新增按钮
     */
    @Test
    @DisplayName("测试新增按钮")
    public void testAddButton() throws Exception {
        SysMenuAddDTO menuAddDTO = new SysMenuAddDTO();
        menuAddDTO.setMenuName("新增按钮_" + System.currentTimeMillis());
        menuAddDTO.setMenuType("F"); // F按钮
        menuAddDTO.setParentId(100L); // 父菜单ID
        menuAddDTO.setOrderNum(1);
        menuAddDTO.setPerms("test:menu:add");
        menuAddDTO.setVisible("0");
        menuAddDTO.setStatus("0");

        mockMvc.perform(post("/system/menu/add")
                .header("Authorization", getAuthHeader())
                .contentType(MediaType.APPLICATION_JSON)
                .content(JSON.toJSONString(menuAddDTO)))
                .andDo(print())
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value(200));
    }

    /**
     * 测试新增菜单 - 参数验证失败
     */
    @Test
    @DisplayName("测试新增菜单-参数验证失败")
    public void testAddMenuWithInvalidParams() throws Exception {
        SysMenuAddDTO menuAddDTO = new SysMenuAddDTO();
        // 缺少必填字段

        mockMvc.perform(post("/system/menu/add")
                .header("Authorization", getAuthHeader())
                .contentType(MediaType.APPLICATION_JSON)
                .content(JSON.toJSONString(menuAddDTO)))
                .andDo(print())
                .andExpect(status().is4xxClientError()); // 参数验证失败
    }

    /**
     * 测试编辑菜单
     * POST /system/menu/edit
     */
    @Test
    @DisplayName("测试编辑菜单")
    public void testUpdateMenu() throws Exception {
        SysMenuAddDTO menuAddDTO = new SysMenuAddDTO();
        menuAddDTO.setMenuId(100L); // 需要替换为实际的菜单ID
        menuAddDTO.setMenuName("测试菜单(已修改)");
        menuAddDTO.setMenuType("C");
        menuAddDTO.setParentId(1L);
        menuAddDTO.setOrderNum(102);
        menuAddDTO.setPath("/test/menu/updated");
        menuAddDTO.setComponent("test/menu/index");
        menuAddDTO.setPerms("test:menu:list");
        menuAddDTO.setIcon("el-icon-s-help");
        menuAddDTO.setVisible("0");
        menuAddDTO.setStatus("0");

        mockMvc.perform(post("/system/menu/edit")
                .header("Authorization", getAuthHeader())
                .contentType(MediaType.APPLICATION_JSON)
                .content(JSON.toJSONString(menuAddDTO)))
                .andDo(print())
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value(200));
    }

    /**
     * 测试删除菜单
     * POST /system/menu/remove?menuId=xxx
     */
    @Test
    @DisplayName("测试删除菜单")
    public void testDeleteMenu() throws Exception {
        String menuId = "100"; // 需要替换为实际的菜单ID

        mockMvc.perform(post("/system/menu/remove")
                .header("Authorization", getAuthHeader())
                .param("menuId", menuId)
                .contentType(MediaType.APPLICATION_JSON))
                .andDo(print())
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value(200));
    }

    /**
     * 测试删除有子菜单的菜单（应该失败）
     */
    @Test
    @DisplayName("测试删除有子菜单的菜单")
    public void testDeleteMenuWithChildren() throws Exception {
        String menuId = "1"; // 具有子菜单的菜单ID

        mockMvc.perform(post("/system/menu/remove")
                .header("Authorization", getAuthHeader())
                .param("menuId", menuId)
                .contentType(MediaType.APPLICATION_JSON))
                .andDo(print())
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value(500)); // 删除失败
    }

    /**
     * 测试删除已分配给角色的菜单（应该失败）
     */
    @Test
    @DisplayName("测试删除已分配给角色的菜单")
    public void testDeleteMenuAssignedToRole() throws Exception {
        String menuId = "1"; // 已分配给角的菜单ID

        mockMvc.perform(post("/system/menu/remove")
                .header("Authorization", getAuthHeader())
                .param("menuId", menuId)
                .contentType(MediaType.APPLICATION_JSON))
                .andDo(print())
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value(500)); // 删除失败
    }

    /**
     * 测试获取角色菜单树（用于角色权限分配）
     * GET /system/menu/roleMenuTreeselect?roleId=xxx
     */
    @Test
    @DisplayName("测试获取角色菜单树")
    public void testGetRoleMenuTree() throws Exception {
        String roleId = "1";

        mockMvc.perform(get("/system/menu/roleMenuTreeselect")
                .header("Authorization", getAuthHeader())
                .param("roleId", roleId)
                .contentType(MediaType.APPLICATION_JSON))
                .andDo(print())
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value(200))
                .andExpect(jsonPath("$.data.menus").isArray())
                .andExpect(jsonPath("$.data.checkedKeys").isArray()); // 已选中的菜单ID列表
    }

    /**
     * 测试修改菜单状态
     */
    @Test
    @DisplayName("测试修改菜单状态")
    public void testChangeMenuStatus() throws Exception {
        mockMvc.perform(post("/system/menu/changeStatus")
                .header("Authorization", getAuthHeader())
                .param("menuId", "100")
                .param("status", "1") // 停用
                .contentType(MediaType.APPLICATION_JSON))
                .andDo(print())
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value(200));
    }

    /**
     * 测试无权限访问
     */
    @Test
    @DisplayName("测试无权限访问菜单树")
    public void testUnauthorizedAccess() throws Exception {
        mockMvc.perform(get("/system/menu/treeselect"))
                .andDo(print())
                .andExpect(status().isUnauthorized()); // 401未认证
    }
}
