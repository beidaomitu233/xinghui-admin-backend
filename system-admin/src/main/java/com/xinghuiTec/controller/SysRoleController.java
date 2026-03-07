package com.xinghuiTec.controller;

import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import com.xinghuiTec.annotation.Log;
import com.xinghuiTec.domain.dto.SysRoleAddDTO;
import com.xinghuiTec.domain.dto.SysRoleQueryDTO;
import com.xinghuiTec.domain.entity.SysRole;
import com.xinghuiTec.emues.BusinessType;
import com.xinghuiTec.service.SysRoleMenuService;
import com.xinghuiTec.service.SysRoleService;
import com.xinghuiTec.utils.Result;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.validation.annotation.Validated;
import org.springframework.web.bind.annotation.*;
import jakarta.annotation.Resource;

import java.util.List;

/**
 * 角色信息表(SysRole)表控制层
 *
 * @author beidoa23
 * @since 2025-12-25 19:33:19
 */

@RestController
@RequestMapping("/system/role")
public class SysRoleController {
    @Resource
    private SysRoleService sysRoleService;

    @Resource
    private SysRoleMenuService sysRoleMenuService;

    /**
     * 查询角色列表
     * 支持分页和多条件查询
     * 
     * @param queryDTO 查询条件DTO
     * @return Result<Page<SysRole>> 分页结果
     */
    @GetMapping("/list")
    @PreAuthorize("hasAuthority('system:role:list')")
    public Result<Page<SysRole>> getRoleList(@Validated SysRoleQueryDTO queryDTO) {
        Page<SysRole> page = sysRoleService.getRoleList(queryDTO);
        return Result.ok(page);
    }

    /**
     * 查询角色详情
     * 
     * @param roleId 角色ID
     * @return Result<SysRole>
     */
    @GetMapping("/{roleId}")
    @PreAuthorize("hasAuthority('system:role:query')")
    public Result<SysRole> getRoleById(@PathVariable("roleId") Long roleId) {
        SysRole role = sysRoleService.getRoleById(roleId);
        if (role == null) {
            return Result.fail();
        }
        return Result.ok(role);
    }

    /**
     * 新增角色
     * 
     * @param addDTO 角色信息
     * @return Result<Long> 返回角色ID
     */
    @PostMapping("/add")
    @PreAuthorize("hasAuthority('system:role:add')")
    @Log(title = "角色管理", businessType = BusinessType.INSERT)
    public Result<Long> addRole(@Validated @RequestBody SysRoleAddDTO addDTO) {
        Long roleId = sysRoleService.addRole(addDTO);
        return Result.ok(roleId);
    }

    /**
     * 修改角色
     * 
     * @param addDTO 角色信息（必须包含roleId）
     * @return Result<Void>
     */
    @PostMapping("/edit")
    @PreAuthorize("hasAuthority('system:role:edit')")
    @Log(title = "角色管理", businessType = BusinessType.UPDATE)
    public Result<Void> updateRole(@Validated @RequestBody SysRoleAddDTO addDTO) {
        sysRoleService.updateRole(addDTO);
        return Result.ok();
    }

    /**
     * 删除角色
     * 
     * @param roleId 角色ID
     * @return Result<Void>
     */
    @PostMapping("/remove")
    @PreAuthorize("hasAuthority('system:role:remove')")
    @Log(title = "角色管理", businessType = BusinessType.DELETE)
    public Result<Void> deleteRole(@RequestParam("roleId") Long roleId) {
        sysRoleService.deleteRole(roleId);
        return Result.ok();
    }

    /**
     * 修改角色状态
     * 
     * @param roleId 角色ID
     * @param status 状态（1正常 0停用）
     * @return Result<Void>
     */
    @PostMapping("/changeStatus")
    @PreAuthorize("hasAuthority('system:role:edit')")
    @Log(title = "角色管理", businessType = BusinessType.UPDATE)
    public Result<Void> updateRoleStatus(@RequestParam("roleId") Long roleId,
            @RequestParam("status") Integer status) {
        sysRoleService.updateRoleStatus(roleId, status);
        return Result.ok();
    }

    /**
     * 查询角色的菜单ID列表
     * 
     * @param roleId 角色ID
     * @return Result<List<Long>> 菜单ID列表
     */
    @GetMapping("/menuIds/{roleId}")
    @PreAuthorize("hasAuthority('system:role:query')")
    public Result<List<Long>> getMenuIdsByRoleId(@PathVariable("roleId") Long roleId) {
        List<Long> menuIds = sysRoleMenuService.getMenuIdsByRoleId(roleId);
        return Result.ok(menuIds);
    }

    /**
     * 为角色分配菜单权限
     * 
     * @param roleId  角色ID
     * @param menuIds 菜单ID列表
     * @return Result<Void>
     */
    @PostMapping("/assignMenus")
    @PreAuthorize("hasAuthority('system:role:edit')")
    @Log(title = "角色管理", businessType = BusinessType.GRANT)
    public Result<Void> assignMenus(@RequestParam("roleId") Long roleId,
            @RequestBody List<Long> menuIds) {
        sysRoleMenuService.assignMenusToRole(roleId, menuIds);
        return Result.ok();
    }
}
