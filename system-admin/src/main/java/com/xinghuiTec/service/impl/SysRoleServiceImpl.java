package com.xinghuiTec.service.impl;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import com.baomidou.mybatisplus.extension.service.impl.ServiceImpl;
import com.xinghuiTec.domain.dto.SysRoleAddDTO;
import com.xinghuiTec.domain.dto.SysRoleQueryDTO;
import com.xinghuiTec.domain.entity.SysUserRole;
import com.xinghuiTec.mapper.SysRoleMapper;
import com.xinghuiTec.domain.entity.SysRole;
import com.xinghuiTec.mapper.SysUserRoleMapper;
import com.xinghuiTec.service.SysRoleMenuService;
import com.xinghuiTec.service.SysRoleService;
import jakarta.annotation.Resource;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;

/**
 * 角色信息表(SysRole)表服务实现类
 */
@Service
@Slf4j
public class SysRoleServiceImpl extends ServiceImpl<SysRoleMapper, SysRole> implements SysRoleService {

    @Resource
    private SysRoleMenuService sysRoleMenuService;

    @Resource
    private SysUserRoleMapper sysUserRoleMapper;

    @Resource
    private com.xinghuiTec.utils.RedisCacheUtils redisCacheUtils;

    /**
     * 分页查询角色列表
     * 支持多条件组合查询和动态排序
     * 
     * @param queryDTO 查询条件
     * @return 分页结果
     */
    @Override
    public Page<SysRole> getRoleList(SysRoleQueryDTO queryDTO) {
        // 构建分页对象
        Page<SysRole> page = new Page<>(queryDTO.getPageNum(), queryDTO.getPageSize());

        // 构建查询条件
        LambdaQueryWrapper<SysRole> wrapper = new LambdaQueryWrapper<>();

        // 角色名称模糊查询
        wrapper.like(queryDTO.getRoleName() != null && !queryDTO.getRoleName().isEmpty(),
                SysRole::getRoleName, queryDTO.getRoleName());

        // 角色权限字符串模糊查询
        wrapper.like(queryDTO.getRoleKey() != null && !queryDTO.getRoleKey().isEmpty(),
                SysRole::getRoleKey, queryDTO.getRoleKey());

        // 角色状态精确查询
        wrapper.eq(queryDTO.getStatus() != null,
                SysRole::getStatus, queryDTO.getStatus());

        // 创建时间范围查询
        wrapper.ge(queryDTO.getCreateTimeStart() != null,
                SysRole::getCreateTime, queryDTO.getCreateTimeStart());
        wrapper.le(queryDTO.getCreateTimeEnd() != null,
                SysRole::getCreateTime, queryDTO.getCreateTimeEnd());

        // 动态排序
        if ("asc".equalsIgnoreCase(queryDTO.getOrder())) {
            wrapper.orderByAsc(SysRole::getCreateTime);
        } else {
            wrapper.orderByDesc(SysRole::getCreateTime);
        }

        // 执行查询
        return this.page(page, wrapper);
    }

    /**
     * 新增角色
     * 
     * @param addDTO 角色信息
     * @return 角色ID
     */
    @Override
    @Transactional(rollbackFor = Exception.class)
    public Long addRole(SysRoleAddDTO addDTO) {
        // 检查角色名称唯一性
        if (!checkRoleNameUnique(addDTO.getRoleName(), null)) {
            throw new RuntimeException("角色名称已存在");
        }

        // 检查角色权限字符串唯一性
        if (!checkRoleKeyUnique(addDTO.getRoleKey(), null)) {
            throw new RuntimeException("角色权限字符串已存在");
        }

        // 创建角色对象
        SysRole role = new SysRole();
        role.setRoleName(addDTO.getRoleName());
        role.setRoleKey(addDTO.getRoleKey());
        role.setDataScope(addDTO.getDataScope());
        role.setStatus(addDTO.getStatus());

        // 保存角色
        this.save(role);

        // 如果提供了菜单ID列表，保存角色菜单关联
        if (addDTO.getMenuIds() != null && !addDTO.getMenuIds().isEmpty()) {
            sysRoleMenuService.assignMenusToRole(role.getRoleId(), addDTO.getMenuIds());
        }

        log.info("新增角色成功: {}", role.getRoleName());
        return role.getRoleId();
    }

    /**
     * 修改角色
     * 
     * @param addDTO 角色信息（必须包含roleId）
     */
    @Override
    @Transactional(rollbackFor = Exception.class)
    public void updateRole(SysRoleAddDTO addDTO) {
        // 检查角色是否存在
        SysRole existRole = this.getById(addDTO.getRoleId());
        if (existRole == null) {
            throw new RuntimeException("角色不存在");
        }

        // 检查角色名称唯一性（排除自己）
        if (!checkRoleNameUnique(addDTO.getRoleName(), addDTO.getRoleId())) {
            throw new RuntimeException("角色名称已存在");
        }

        // 检查角色权限字符串唯一性（排除自己）
        if (!checkRoleKeyUnique(addDTO.getRoleKey(), addDTO.getRoleId())) {
            throw new RuntimeException("角色权限字符串已存在");
        }

        // 更新角色信息
        existRole.setRoleName(addDTO.getRoleName());
        existRole.setRoleKey(addDTO.getRoleKey());
        existRole.setDataScope(addDTO.getDataScope());
        existRole.setStatus(addDTO.getStatus());

        this.updateById(existRole);

        // 如果提供了菜单ID列表，更新角色菜单关联
        if (addDTO.getMenuIds() != null) {
            sysRoleMenuService.assignMenusToRole(addDTO.getRoleId(), addDTO.getMenuIds());
            // 清除所有拥有该角色的用户的路由缓存
            clearUserRouterCacheByRoleId(addDTO.getRoleId());
        }

        log.info("修改角色成功: {}", existRole.getRoleName());
    }

    /**
     * 删除角色
     * 
     * @param roleId 角色ID
     */
    @Override
    @Transactional(rollbackFor = Exception.class)
    public void deleteRole(Long roleId) {
        // 检查角色是否存在
        SysRole role = this.getById(roleId);
        if (role == null) {
            throw new RuntimeException("角色不存在");
        }

        // 检查角色是否被用户使用
        LambdaQueryWrapper<SysUserRole> wrapper = new LambdaQueryWrapper<>();
        wrapper.eq(SysUserRole::getRoleId, roleId);
        long count = sysUserRoleMapper.selectCount(wrapper);
        if (count > 0) {
            throw new RuntimeException("角色已分配给用户，不能删除");
        }

        // 删除角色
        this.removeById(roleId);

        // 删除角色菜单关联
        sysRoleMenuService.deleteByRoleId(roleId);

        log.info("删除角色成功: {}", role.getRoleName());
    }

    /**
     * 查询角色详情
     * 
     * @param roleId 角色ID
     * @return 角色信息
     */
    @Override
    public SysRole getRoleById(Long roleId) {
        return this.getById(roleId);
    }

    /**
     * 修改角色状态
     * 
     * @param roleId 角色ID
     * @param status 状态
     */
    @Override
    @Transactional(rollbackFor = Exception.class)
    public void updateRoleStatus(Long roleId, Integer status) {
        SysRole role = this.getById(roleId);
        if (role == null) {
            throw new RuntimeException("角色不存在");
        }

        role.setStatus(status);
        this.updateById(role);

        // 角色状态变更，清除相关用户的路由缓存
        clearUserRouterCacheByRoleId(roleId);

        log.info("修改角色状态成功: {} -> {}", role.getRoleName(), status);
    }

    /**
     * 清除拥有指定角色的所有用户的路由缓存
     * 
     * @param roleId 角色ID
     */
    private void clearUserRouterCacheByRoleId(Long roleId) {
        // 查询拥有该角色的所有用户
        LambdaQueryWrapper<SysUserRole> wrapper = new LambdaQueryWrapper<>();
        wrapper.eq(SysUserRole::getRoleId, roleId);
        List<SysUserRole> userRoles = sysUserRoleMapper.selectList(wrapper);

        // 清除每个用户的路由缓存和用户信息缓存
        for (SysUserRole userRole : userRoles) {
            String userId = userRole.getUserId();
            String routerCacheKey = com.xinghuiTec.constants.RedisConstants.USER_ROUTER_PREFIX + userId;
            redisCacheUtils.deleteObject(routerCacheKey);
            String userInfoCacheKey = com.xinghuiTec.constants.RedisConstants.USER_INFO_PREFIX + userId;
            redisCacheUtils.deleteObject(userInfoCacheKey);
        }

        log.info("已清除 {} 个用户的路由缓存", userRoles.size());
    }

    /**
     * 检查角色名称是否唯一
     * 
     * @param roleName 角色名称
     * @param roleId   角色ID（排除自己）
     * @return true-唯一，false-不唯一
     */
    @Override
    public boolean checkRoleNameUnique(String roleName, Long roleId) {
        LambdaQueryWrapper<SysRole> wrapper = new LambdaQueryWrapper<>();
        wrapper.eq(SysRole::getRoleName, roleName);
        if (roleId != null) {
            wrapper.ne(SysRole::getRoleId, roleId);
        }
        return this.count(wrapper) == 0;
    }

    /**
     * 检查角色权限字符串是否唯一
     * 
     * @param roleKey 角色权限字符串
     * @param roleId  角色ID（排除自己）
     * @return true-唯一，false-不唯一
     */
    @Override
    public boolean checkRoleKeyUnique(String roleKey, Long roleId) {
        LambdaQueryWrapper<SysRole> wrapper = new LambdaQueryWrapper<>();
        wrapper.eq(SysRole::getRoleKey, roleKey);
        if (roleId != null) {
            wrapper.ne(SysRole::getRoleId, roleId);
        }
        return this.count(wrapper) == 0;
    }
}
