package com.xinghuiTec.service;

import com.baomidou.mybatisplus.extension.service.IService;
import com.xinghuiTec.domain.entity.SysRoleMenu;

import java.util.List;

/**
 * 角色和菜单关联表(SysRoleMenu)表服务接口层
 *
 * @since 2025-12-25 19:33:19
 */
public interface SysRoleMenuService extends IService<SysRoleMenu> {

    /**
     * 查询角色的菜单ID列表
     * 
     * @param roleId 角色ID
     * @return 菜单ID列表
     */
    List<Long> getMenuIdsByRoleId(Long roleId);

    /**
     * 为角色分配菜单权限
     * 先删除角色的所有菜单关联，再批量插入新的关联
     * 
     * @param roleId  角色ID
     * @param menuIds 菜单ID列表
     */
    void assignMenusToRole(Long roleId, List<Long> menuIds);

    /**
     * 批量删除角色菜单关联
     * 
     * @param roleId 角色ID
     */
    void deleteByRoleId(Long roleId);
}
