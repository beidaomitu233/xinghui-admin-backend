package com.xinghuiTec;

import com.baomidou.mybatisplus.core.conditions.query.QueryWrapper;
import com.xinghuiTec.domain.dto.SysMenuAddDTO;
import com.xinghuiTec.domain.dto.SysMenuQueryDTO;
import com.xinghuiTec.domain.entity.SysMenu;
import com.xinghuiTec.domain.vo.SysMenuVO;
import com.xinghuiTec.mapper.SysMenuMapper;
import com.xinghuiTec.service.SysMenuService;
import jakarta.annotation.Resource;
import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.SpringBootTest;

import java.util.List;

/**
 * 菜单管理模块单元测试类
 * 包含菜单增删改查的完整测试用例
 *
 * @author beidoa23
 * @since 2026-01-22
 */
@SpringBootTest
public class SysMenuTest {

    /**
     * 注入菜单服务
     */
    @Resource
    private SysMenuService sysMenuService;

    /**
     * 注入菜单Mapper
     */
    @Resource
    private SysMenuMapper sysMenuMapper;

    /**
     * 测试新增目录菜单
     * 目录是最顶层的菜单容器，可以包含子菜单
     */
    @Test
    public void testAddDirectory() {
        System.out.println("========== 测试新增目录菜单 ==========");

        // 创建目录菜单DTO
        SysMenuAddDTO addDTO = new SysMenuAddDTO();
        addDTO.setMenuName("测试目录");
        addDTO.setParentId(0L); // 顶级菜单
        addDTO.setOrderNum(100);
        addDTO.setMenuType("M"); // M-目录
        addDTO.setPath("testdir");
        addDTO.setVisible("0"); // 0-显示
        addDTO.setStatus("0"); // 0-正常
        addDTO.setIcon("test-icon");

        // 新增菜单
        Long menuId = sysMenuService.addMenu(addDTO);
        System.out.println("✓ 目录菜单新增成功，ID: " + menuId);

        // 验证新增结果
        SysMenu menu = sysMenuService.getMenuById(menuId);
        System.out.println("✓ 查询新增菜单: " + menu.getMenuName() + " (类型: " + menu.getMenuType() + ")");
    }

    /**
     * 测试新增页面菜单
     * 页面菜单对应具体的前端页面组件
     */
    @Test
    public void testAddPageMenu() {
        System.out.println("========== 测试新增页面菜单 ==========");

        // 创建页面菜单DTO（需要先有父目录）
        SysMenuAddDTO addDTO = new SysMenuAddDTO();
        addDTO.setMenuName("测试页面");
        addDTO.setParentId(1L); // 假设父菜单ID为1（系统管理目录）
        addDTO.setOrderNum(1);
        addDTO.setMenuType("C"); // C-菜单（页面）
        addDTO.setPath("testpage");
        addDTO.setComponent("views/test/index"); // 组件路径
        addDTO.setVisible("0");
        addDTO.setStatus("0");
        addDTO.setPerms("test:page:list"); // 权限标识
        addDTO.setIcon("page-icon");

        try {
            Long menuId = sysMenuService.addMenu(addDTO);
            System.out.println("✓ 页面菜单新增成功，ID: " + menuId);
        } catch (Exception e) {
            System.out.println("✗ 页面菜单新增失败: " + e.getMessage());
        }
    }

    /**
     * 测试新增按钮权限
     * 按钮权限用于控制页面内的操作按钮
     */
    @Test
    public void testAddButton() {
        System.out.println("========== 测试新增按钮权限 ==========");

        // 创建按钮DTO（需要有父菜单）
        SysMenuAddDTO addDTO = new SysMenuAddDTO();
        addDTO.setMenuName("测试新增按钮");
        addDTO.setParentId(2L); // 假设父菜单ID为2（用户管理页面）
        addDTO.setOrderNum(1);
        addDTO.setMenuType("F"); // F-按钮
        addDTO.setVisible("0");
        addDTO.setStatus("0");
        addDTO.setPerms("test:page:add"); // 按钮权限标识

        try {
            Long menuId = sysMenuService.addMenu(addDTO);
            System.out.println("✓ 按钮权限新增成功，ID: " + menuId);
        } catch (Exception e) {
            System.out.println("✗ 按钮权限新增失败: " + e.getMessage());
        }
    }

    /**
     * 测试查询菜单列表
     * 支持按条件查询菜单
     */
    @Test
    public void testQueryMenuList() {
        System.out.println("========== 测试查询菜单列表 ==========");

        // 1. 查询所有菜单
        SysMenuQueryDTO queryDTO = new SysMenuQueryDTO();
        List<SysMenu> allMenus = sysMenuService.getMenuList(queryDTO);
        System.out.println("查询到菜单总数: " + allMenus.size());

        // 2. 按菜单名称模糊查询
        queryDTO.setMenuName("系统");
        List<SysMenu> systemMenus = sysMenuService.getMenuList(queryDTO);
        System.out.println("包含'系统'的菜单数: " + systemMenus.size());

        // 3. 按菜单类型查询
        queryDTO.setMenuName(null);
        queryDTO.setMenuType("M"); // 只查询目录
        List<SysMenu> directories = sysMenuService.getMenuList(queryDTO);
        System.out.println("目录类型菜单数: " + directories.size());

        // 打印部分结果
        allMenus.stream().limit(5).forEach(menu -> {
            System.out.println("  - " + menu.getMenuName() + " [" + menu.getMenuType() + "]");
        });
    }

    /**
     * 测试获取菜单树形结构
     */
//    @Test
//    public void testGetMenuTree() {
//        System.out.println("========== 测试获取菜单树形结构 ==========");
//
//        List<SysMenuVO> menuTree = sysMenuService.getMenuTree( queryDTO);
//        System.out.println("顶级菜单数量: " + menuTree.size());
//
//        // 打印树形结构
//        printMenuTree(menuTree, 0);
//    }

    /**
     * 测试修改菜单
     */
    @Test
    public void testUpdateMenu() {
        System.out.println("========== 测试修改菜单 ==========");

        // 先查询一个存在的菜单
        QueryWrapper<SysMenu> queryWrapper = new QueryWrapper<>();
        queryWrapper.eq("menu_type", "M").last("LIMIT 1");
        SysMenu existMenu = sysMenuService.getOne(queryWrapper);

        if (existMenu != null) {
            System.out.println("找到菜单: " + existMenu.getMenuName() + " (ID: " + existMenu.getMenuId() + ")");

            // 修改菜单
            SysMenuAddDTO updateDTO = new SysMenuAddDTO();
            updateDTO.setMenuId(existMenu.getMenuId());
            updateDTO.setMenuName(existMenu.getMenuName() + "_已修改");
            updateDTO.setParentId(existMenu.getParentId());
            updateDTO.setOrderNum(existMenu.getOrderNum());
            updateDTO.setMenuType(existMenu.getMenuType());
            updateDTO.setPath(existMenu.getPath());
            updateDTO.setVisible(existMenu.getVisible());
            updateDTO.setStatus(existMenu.getStatus());
            updateDTO.setIcon(existMenu.getIcon());

            try {
                sysMenuService.updateMenu(updateDTO);
                System.out.println("✓ 菜单修改成功");

                // 验证修改结果
                SysMenu updatedMenu = sysMenuService.getMenuById(existMenu.getMenuId());
                System.out.println("修改后名称: " + updatedMenu.getMenuName());
            } catch (Exception e) {
                System.out.println("✗ 菜单修改失败: " + e.getMessage());
            }
        } else {
            System.out.println("未找到可修改的菜单");
        }
    }

    /**
     * 测试删除菜单
     * 注意：有子菜单或被角色关联的菜单不能删除
     */
    @Test
    public void testDeleteMenu() {
        System.out.println("========== 测试删除菜单 ==========");

        // 先新增一个测试菜单用于删除
        SysMenuAddDTO addDTO = new SysMenuAddDTO();
        addDTO.setMenuName("待删除测试菜单");
        addDTO.setParentId(0L);
        addDTO.setOrderNum(999);
        addDTO.setMenuType("M");
        addDTO.setPath("todelete");
        addDTO.setVisible("0");
        addDTO.setStatus("0");

        Long menuId = sysMenuService.addMenu(addDTO);
        System.out.println("新增待删除菜单，ID: " + menuId);

        // 检查是否有子菜单
        boolean hasChild = sysMenuService.hasChildMenu(menuId);
        System.out.println("是否有子菜单: " + hasChild);

        // 删除菜单
        try {
            sysMenuService.deleteMenu(menuId);
            System.out.println("✓ 菜单删除成功");

            // 验证删除结果
            SysMenu deletedMenu = sysMenuService.getMenuById(menuId);
            System.out.println("验证删除: " + (deletedMenu == null ? "已删除" : "未删除"));
        } catch (Exception e) {
            System.out.println("✗ 菜单删除失败: " + e.getMessage());
        }
    }

    /**
     * 测试更新菜单排序
     */
    @Test
    public void testUpdateMenuOrder() {
        System.out.println("========== 测试更新菜单排序 ==========");

        // 查询一个存在的菜单
        QueryWrapper<SysMenu> queryWrapper = new QueryWrapper<>();
        queryWrapper.last("LIMIT 1");
        SysMenu menu = sysMenuService.getOne(queryWrapper);

        if (menu != null) {
            System.out.println("菜单: " + menu.getMenuName() + ", 当前排序: " + menu.getOrderNum());

            // 更新排序
            int newOrder = menu.getOrderNum() + 1;
            sysMenuService.updateMenuOrder(menu.getMenuId(), newOrder);
            System.out.println("✓ 排序更新为: " + newOrder);

            // 验证更新结果
            SysMenu updatedMenu = sysMenuService.getMenuById(menu.getMenuId());
            System.out.println("验证排序: " + updatedMenu.getOrderNum());
        }
    }

    /**
     * 综合测试：完整的菜单增删改查流程
     */
    @Test
    public void testMenuCRUD() {
        System.out.println("========== 开始完整的菜单增删改查测试 ==========\n");

        // 1. 新增目录
        System.out.println("【1. 新增目录】");
        SysMenuAddDTO dirDTO = new SysMenuAddDTO();
        dirDTO.setMenuName("CRUD测试目录");
        dirDTO.setParentId(0L);
        dirDTO.setOrderNum(888);
        dirDTO.setMenuType("M");
        dirDTO.setPath("crudtest");
        dirDTO.setVisible("0");
        dirDTO.setStatus("0");
        dirDTO.setIcon("crud-icon");

        Long dirId = sysMenuService.addMenu(dirDTO);
        System.out.println("✓ 目录创建成功，ID: " + dirId + "\n");

        // 2. 在目录下新增页面
        System.out.println("【2. 新增页面】");
        SysMenuAddDTO pageDTO = new SysMenuAddDTO();
        pageDTO.setMenuName("CRUD测试页面");
        pageDTO.setParentId(dirId);
        pageDTO.setOrderNum(1);
        pageDTO.setMenuType("C");
        pageDTO.setPath("crudpage");
        pageDTO.setComponent("views/crud/index");
        pageDTO.setVisible("0");
        pageDTO.setStatus("0");
        pageDTO.setPerms("crud:test:list");

        Long pageId = sysMenuService.addMenu(pageDTO);
        System.out.println("✓ 页面创建成功，ID: " + pageId + "\n");

        // 3. 在页面下新增按钮
        System.out.println("【3. 新增按钮】");
        SysMenuAddDTO btnDTO = new SysMenuAddDTO();
        btnDTO.setMenuName("CRUD测试按钮");
        btnDTO.setParentId(pageId);
        btnDTO.setOrderNum(1);
        btnDTO.setMenuType("F");
        btnDTO.setVisible("0");
        btnDTO.setStatus("0");
        btnDTO.setPerms("crud:test:add");

        Long btnId = sysMenuService.addMenu(btnDTO);
        System.out.println("✓ 按钮创建成功，ID: " + btnId + "\n");

        // 4. 查询菜单
        System.out.println("【4. 查询菜单】");
        SysMenu queriedDir = sysMenuService.getMenuById(dirId);
        System.out.println("✓ 查询目录: " + queriedDir.getMenuName());
        boolean hasChild = sysMenuService.hasChildMenu(dirId);
        System.out.println("✓ 目录是否有子菜单: " + hasChild + "\n");

        // 5. 修改菜单
        System.out.println("【5. 修改菜单】");
        SysMenuAddDTO updateDTO = new SysMenuAddDTO();
        updateDTO.setMenuId(pageId);
        updateDTO.setMenuName("CRUD测试页面-已修改");
        updateDTO.setParentId(dirId);
        updateDTO.setOrderNum(1);
        updateDTO.setMenuType("C");
        updateDTO.setPath("crudpage-updated");
        updateDTO.setComponent("views/crud/index");
        updateDTO.setVisible("0");
        updateDTO.setStatus("0");

        sysMenuService.updateMenu(updateDTO);
        SysMenu updatedPage = sysMenuService.getMenuById(pageId);
        System.out.println("✓ 修改成功，新名称: " + updatedPage.getMenuName() + "\n");

        // 6. 删除菜单（需按顺序：先按钮，再页面，最后目录）
        System.out.println("【6. 删除菜单】");
        sysMenuService.deleteMenu(btnId);
        System.out.println("✓ 按钮删除成功");

        sysMenuService.deleteMenu(pageId);
        System.out.println("✓ 页面删除成功");

        sysMenuService.deleteMenu(dirId);
        System.out.println("✓ 目录删除成功\n");

        System.out.println("========== 完整的菜单增删改查测试完成 ==========");
    }

    // ==================== 辅助方法 ====================

    /**
     * 递归打印菜单树形结构
     *
     * @param menuList 菜单列表
     * @param level    当前层级
     */
    private void printMenuTree(List<SysMenuVO> menuList, int level) {
        if (menuList == null || menuList.isEmpty()) {
            return;
        }

        String indent = "  ".repeat(level);
        for (SysMenuVO menu : menuList) {
            String typeIcon = switch (menu.getMenuType()) {
                case "M" -> "📁";
                case "C" -> "📄";
                case "F" -> "🔘";
                default -> "❓";
            };
            System.out.println(indent + typeIcon + " " + menu.getMenuName() + " [" + menu.getMenuType() + "]");

            // 递归打印子菜单
            if (menu.getChildren() != null && !menu.getChildren().isEmpty()) {
                printMenuTree(menu.getChildren(), level + 1);
            }
        }
    }
}
