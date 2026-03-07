package com.xinghuiTec.service.impl;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.extension.service.impl.ServiceImpl;
import com.xinghuiTec.mapper.SysRoleMenuMapper;
import com.xinghuiTec.domain.entity.SysRoleMenu;
import com.xinghuiTec.service.SysRoleMenuService;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.ArrayList;
import java.util.List;
import java.util.stream.Collectors;

/**
 * 角色和菜单关联表(SysRoleMenu)表服务实现类
 */
@Service
@Slf4j
public class SysRoleMenuServiceImpl extends ServiceImpl<SysRoleMenuMapper, SysRoleMenu> implements SysRoleMenuService {

    /**
     * 查询角色的菜单ID列表
     * 
     * @param roleId 角色ID
     * @return 菜单ID列表
     */
    @Override
    public List<Long> getMenuIdsByRoleId(Long roleId) {
        LambdaQueryWrapper<SysRoleMenu> wrapper = new LambdaQueryWrapper<>();
        wrapper.eq(SysRoleMenu::getRoleId, roleId);

        List<SysRoleMenu> roleMenuList = this.list(wrapper);
        return roleMenuList.stream()
                .map(SysRoleMenu::getMenuId)
                .collect(Collectors.toList());
    }

    /**
     * 为角色分配菜单权限
     * 先删除角色的所有菜单关联，再批量插入新的关联
     * 
     * @param roleId  角色ID
     * @param menuIds 菜单ID列表
     */
    @Override
    @Transactional(rollbackFor = Exception.class)
    public void assignMenusToRole(Long roleId, List<Long> menuIds) {
        // 先删除角色的所有菜单关联
        deleteByRoleId(roleId);

        // 如果菜单ID列表为空，则只删除不插入
        if (menuIds == null || menuIds.isEmpty()) {
            log.info("清空角色菜单权限，角色ID: {}", roleId);
            return;
        }

        // 批量插入新的关联
        List<SysRoleMenu> roleMenuList = new ArrayList<>();
        for (Long menuId : menuIds) {
            SysRoleMenu roleMenu = new SysRoleMenu();
            roleMenu.setRoleId(roleId);
            roleMenu.setMenuId(menuId);
            roleMenuList.add(roleMenu);
        }

        this.saveBatch(roleMenuList);
        log.info("为角色分配菜单权限成功，角色ID: {}, 菜单数量: {}", roleId, menuIds.size());
    }

    /**
     * 批量删除角色菜单关联
     * 
     * @param roleId 角色ID
     */
    @Override
    @Transactional(rollbackFor = Exception.class)
    public void deleteByRoleId(Long roleId) {
        LambdaQueryWrapper<SysRoleMenu> wrapper = new LambdaQueryWrapper<>();
        wrapper.eq(SysRoleMenu::getRoleId, roleId);
        this.remove(wrapper);
        log.debug("删除角色菜单关联，角色ID: {}", roleId);
    }
}
