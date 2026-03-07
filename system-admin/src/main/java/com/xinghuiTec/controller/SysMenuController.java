package com.xinghuiTec.controller;

import com.xinghuiTec.annotation.Log;
import com.xinghuiTec.domain.dto.SysMenuAddDTO;
import com.xinghuiTec.domain.dto.SysMenuQueryDTO;
import com.xinghuiTec.domain.entity.SysMenu;
import com.xinghuiTec.domain.vo.SysMenuVO;
import com.xinghuiTec.emues.BusinessType;
import com.xinghuiTec.service.SysMenuService;
import com.xinghuiTec.utils.Result;
import jakarta.annotation.Resource;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.validation.annotation.Validated;
import org.springframework.web.bind.annotation.*;

import java.util.List;

/**
 * 菜单权限表(SysMenu)控制层
 * 提供菜单管理的REST API接口，包括：
 * 1. 菜单列表查询（平铺/树形）
 * 2. 菜单详情查询
 * 3. 菜单新增、修改、删除
 * 4. 菜单排序更新
 *
 * @author beidoa23
 * @since 2025-12-25 19:33:19
 */
@RestController
@RequestMapping("/system/menu")
public class SysMenuController {

    /**
     * 注入菜单服务
     */
    @Resource
    private SysMenuService sysMenuService;

    /**
     * 查询菜单列表
     * 支持按菜单名称、状态、类型等条件查询
     * 返回平铺的菜单列表（按层级和排序号排序）
     *
     * @param queryDTO 查询条件DTO
     * @return 菜单列表
     */
    @GetMapping("/list")
    @PreAuthorize("hasAuthority('system:menu:list')")
    public Result<List<SysMenu>> getMenuList(@Validated SysMenuQueryDTO queryDTO) {
        List<SysMenu> menuList = sysMenuService.getMenuList(queryDTO);
        return Result.ok(menuList);
    }

    /**
     * 获取菜单树形结构
     * 返回所有正常状态的菜单，构建为树形结构
     * 用于前端菜单展示和菜单选择等场景
     *
     * @return 菜单树形结构
     */
    @GetMapping("/tree")
    @PreAuthorize("hasAuthority('system:menu:list')")
    public Result<List<SysMenuVO>> getMenuTree(@Validated SysMenuQueryDTO queryDTO) {
        List<SysMenuVO> menuTree = sysMenuService.getMenuTree(queryDTO);
        return Result.ok(menuTree);
    }

    /**
     * 根据菜单ID查询菜单详情
     *
     * @param menuId 菜单ID
     * @return 菜单详情
     */
    @GetMapping("/{menuId}")
    @PreAuthorize("hasAuthority('system:menu:query')")
    public Result<SysMenu> getMenuById(@PathVariable("menuId") Long menuId) {
        SysMenu menu = sysMenuService.getMenuById(menuId);
        if (menu == null) {
            return Result.fail();
        }
        return Result.ok(menu);
    }

    /**
     * 新增菜单
     * 支持新增目录(M)、菜单(C)、按钮(F)三种类型
     * 会进行以下校验：
     * 1. 父菜单是否存在
     * 2. 同一父菜单下菜单名称是否唯一
     * 3. 菜单类型与必填字段的匹配性
     *
     * @param addDTO 新增菜单请求DTO
     * @return 新增的菜单ID
     */
    @PostMapping("/add")
    @PreAuthorize("hasAuthority('system:menu:add')")
    @Log(title = "菜单管理", businessType = BusinessType.INSERT)
    public Result<Long> addMenu(@Validated @RequestBody SysMenuAddDTO addDTO) {
        Long menuId = sysMenuService.addMenu(addDTO);
        return Result.ok(menuId);
    }

    /**
     * 修改菜单
     * 会进行以下校验：
     * 1. 菜单是否存在
     * 2. 父菜单不能是自己或自己的子菜单
     * 3. 同一父菜单下菜单名称是否唯一
     *
     * @param addDTO 修改菜单请求DTO（必须包含menuId）
     * @return 操作结果
     */
    @PostMapping("/edit")
    @PreAuthorize("hasAuthority('system:menu:edit')")
    @Log(title = "菜单管理", businessType = BusinessType.UPDATE)
    public Result<Void> updateMenu(@Validated @RequestBody SysMenuAddDTO addDTO) {
        sysMenuService.updateMenu(addDTO);
        return Result.ok();
    }

    /**
     * 删除菜单
     * 会进行以下校验：
     * 1. 菜单是否存在
     * 2. 是否有子菜单（有子菜单不能删除）
     * 3. 是否被角色关联（被关联不能删除）
     *
     * @param menuId 菜单ID
     * @return 操作结果
     */
    @PostMapping("/remove")
    @PreAuthorize("hasAuthority('system:menu:remove')")
    @Log(title = "菜单管理", businessType = BusinessType.DELETE)
    public Result<Void> deleteMenu(@RequestParam("menuId") Long menuId) {
        sysMenuService.deleteMenu(menuId);
        return Result.ok();
    }

    /**
     * 更新菜单排序
     * 用于调整菜单在同级中的显示顺序
     *
     * @param menuId   菜单ID
     * @param orderNum 新的排序号（数值越小越靠前）
     * @return 操作结果
     */
    @PostMapping("/order")
    @PreAuthorize("hasAuthority('system:menu:edit')")
    public Result<Void> updateMenuOrder(
            @RequestParam("menuId") Long menuId,
            @RequestParam("orderNum") Integer orderNum) {
        sysMenuService.updateMenuOrder(menuId, orderNum);
        return Result.ok();
    }

    /**
     * 检查菜单是否有子菜单
     * 用于前端删除前的确认提示
     *
     * @param menuId 菜单ID
     * @return true-有子菜单，false-无子菜单
     */
    @GetMapping("/hasChild/{menuId}")
    @PreAuthorize("hasAuthority('system:menu:query')")
    public Result<Boolean> hasChildMenu(@PathVariable("menuId") Long menuId) {
        boolean hasChild = sysMenuService.hasChildMenu(menuId);
        return Result.ok(hasChild);
    }

    /**
     * 根据角色ID获取菜单列表
     * 用于角色菜单权限分配时的回显
     *
     * @param roleId 角色ID
     * @return 角色关联的菜单列表
     */
    @GetMapping("/role/{roleId}")
    @PreAuthorize("hasAuthority('system:menu:list')")
    public Result<List<SysMenu>> getMenuListByRoleId(@PathVariable("roleId") Long roleId) {
        List<SysMenu> menuList = sysMenuService.getMenuListByRoleId(roleId);
        return Result.ok(menuList);
    }
}
