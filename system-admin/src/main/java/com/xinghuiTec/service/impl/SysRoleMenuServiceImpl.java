package com.xinghuiTec.service.impl;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.extension.service.impl.ServiceImpl;
import com.xinghuiTec.mapper.SysRoleMenuMapper;
import com.xinghuiTec.domain.entity.SysRoleMenu;
import com.xinghuiTec.domain.entity.SysRole;
import com.xinghuiTec.domain.entity.SysMenu;
import com.xinghuiTec.domain.entity.SysUserRole;
import com.xinghuiTec.mapper.SysRoleMapper;
import com.xinghuiTec.mapper.SysMenuMapper;
import com.xinghuiTec.mapper.SysUserRoleMapper;
import com.xinghuiTec.utils.RedisCacheUtils;
import com.xinghuiTec.constants.RedisConstants;
import jakarta.annotation.Resource;
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
    @Resource private SysRoleMapper roleMapper;
    @Resource private SysMenuMapper menuMapper;
    @Resource private SysUserRoleMapper userRoleMapper;
    @Resource private RedisCacheUtils redisCacheUtils;

    /**
     * 查询角色的菜单ID列表
     * 
     * @param roleId 角色ID
     * @return 菜单ID列表
     */
    @Override
    public List<Long> getMenuIdsByRoleId(Long roleId) {
        requireRole(roleId);
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
        requireRole(roleId);
        if (menuIds != null && !menuIds.isEmpty()) {
            if (menuIds.stream().anyMatch(java.util.Objects::isNull)
                    || menuIds.stream().distinct().count() != menuIds.size()) {
                throw new IllegalArgumentException("菜单ID不能重复或为空");
            }
            long validCount = menuMapper.selectCount(new LambdaQueryWrapper<SysMenu>().in(SysMenu::getMenuId, menuIds));
            if (validCount != menuIds.size()) {
                throw new IllegalArgumentException("菜单不存在");
            }
        }
        // 先删除角色的所有菜单关联
        deleteByRoleId(roleId);

        // 如果菜单ID列表为空，则只删除不插入
        if (menuIds == null || menuIds.isEmpty()) {
            clearRoleUserCaches(roleId);
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
        clearRoleUserCaches(roleId);
        log.info("为角色分配菜单权限成功，角色ID: {}, 菜单数量: {}", roleId, menuIds.size());
    }

    private void requireRole(Long roleId) {
        SysRole role = roleId == null ? null : roleMapper.selectById(roleId);
        if (role == null) throw new IllegalArgumentException("角色不存在");
    }

    private void clearRoleUserCaches(Long roleId) {
        List<SysUserRole> users = userRoleMapper.selectList(new LambdaQueryWrapper<SysUserRole>()
                .eq(SysUserRole::getRoleId, roleId));
        for (SysUserRole userRole : users) {
            Long userId = userRole.getUserId();
            redisCacheUtils.deleteObject(RedisConstants.USER_INFO_PREFIX + userId);
            redisCacheUtils.deleteObject(RedisConstants.USER_ROUTER_PREFIX + userId);
            redisCacheUtils.deleteObject(RedisConstants.ADMIN_LOGIN_PREFIX + userId);
        }
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
