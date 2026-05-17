package com.xinghuiTec.service.impl;

import cn.hutool.core.util.RandomUtil;
import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.core.toolkit.Wrappers;
import com.baomidou.mybatisplus.extension.service.impl.ServiceImpl;
import com.xinghuiTec.constants.TenantConstants;
import com.xinghuiTec.domain.entity.*;
import com.xinghuiTec.exception.TenantException;
import com.xinghuiTec.mapper.*;
import com.xinghuiTec.service.ISysTenantService;
import lombok.RequiredArgsConstructor;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.ArrayList;
import java.util.Date;
import java.util.List;

/**
 * 租户Service业务层处理
 *
 * @author xinghuiTec
 */
@RequiredArgsConstructor
@Service
public class SysTenantServiceImpl extends ServiceImpl<SysTenantMapper, SysTenant> implements ISysTenantService {

    private final SysTenantMapper baseMapper;
    private final SysTenantPackageMapper tenantPackageMapper;
    private final SysUserMapper userMapper;
    private final SysRoleMapper roleMapper;
    private final SysRoleMenuMapper roleMenuMapper;
    private final SysUserRoleMapper userRoleMapper;
    private final PasswordEncoder passwordEncoder;

    @Override
    public SysTenant queryById(Long id) {
        return baseMapper.selectById(id);
    }

    @Override
    public SysTenant queryByTenantId(String tenantId) {
        return baseMapper.selectOne(new LambdaQueryWrapper<SysTenant>().eq(SysTenant::getTenantId, tenantId));
    }

    @Override
    public List<SysTenant> queryList(SysTenant tenant) {
        LambdaQueryWrapper<SysTenant> lqw = Wrappers.lambdaQuery();
        lqw.eq(tenant.getTenantId() != null && !tenant.getTenantId().isEmpty(), SysTenant::getTenantId, tenant.getTenantId());
        lqw.like(tenant.getContactUserName() != null && !tenant.getContactUserName().isEmpty(), SysTenant::getContactUserName, tenant.getContactUserName());
        lqw.eq(tenant.getContactPhone() != null && !tenant.getContactPhone().isEmpty(), SysTenant::getContactPhone, tenant.getContactPhone());
        lqw.like(tenant.getCompanyName() != null && !tenant.getCompanyName().isEmpty(), SysTenant::getCompanyName, tenant.getCompanyName());
        lqw.eq(tenant.getStatus() != null && !tenant.getStatus().isEmpty(), SysTenant::getStatus, tenant.getStatus());
        lqw.orderByAsc(SysTenant::getId);
        return baseMapper.selectList(lqw);
    }

    @Override
    @Transactional(rollbackFor = Exception.class)
    public Boolean insertByBo(SysTenant tenant, String username, String password) {
        // 生成租户ID
        List<String> tenantIds = baseMapper.selectObjs(
            new LambdaQueryWrapper<SysTenant>().select(SysTenant::getTenantId), x -> x.toString());
        String tenantId = generateTenantId(tenantIds);
        tenant.setTenantId(tenantId);

        // 创建租户记录
        boolean flag = baseMapper.insert(tenant) > 0;
        if (!flag) {
            throw new TenantException("创建租户失败");
        }

        // 根据套餐创建角色
        Long roleId = createTenantRole(tenantId, tenant.getPackageId());

        // 创建系统用户（租户管理员）
        SysUser user = new SysUser();
        user.setTenantId(tenantId);
        user.setUsername(username);
        user.setNickname(username);
        user.setPassword(passwordEncoder.encode(password));
        user.setStatus(1);
        userMapper.insert(user);

        // 用户和角色关联
        SysUserRole userRole = new SysUserRole();
        userRole.setUserId(user.getUserId());
        userRole.setRoleId(roleId);
        userRoleMapper.insert(userRole);

        return true;
    }

    @Override
    public Boolean updateByBo(SysTenant tenant) {
        tenant.setTenantId(null);
        tenant.setPackageId(null);
        return baseMapper.updateById(tenant) > 0;
    }

    @Override
    public int updateTenantStatus(SysTenant tenant) {
        SysTenant t = new SysTenant();
        t.setId(tenant.getId());
        t.setStatus(tenant.getStatus());
        return baseMapper.updateById(t);
    }

    @Override
    public Boolean deleteWithValidByIds(List<Long> ids, Boolean isValid) {
        if (isValid) {
            // 校验是否包含默认租户
            for (Long id : ids) {
                SysTenant tenant = baseMapper.selectById(id);
                if (tenant != null && TenantConstants.DEFAULT_TENANT_ID.equals(tenant.getTenantId())) {
                    throw new TenantException("不允许删除默认租户");
                }
            }
        }
        return baseMapper.deleteBatchIds(ids) > 0;
    }

    @Override
    public boolean checkCompanyNameUnique(SysTenant tenant) {
        boolean exist = baseMapper.exists(new LambdaQueryWrapper<SysTenant>()
            .eq(SysTenant::getCompanyName, tenant.getCompanyName())
            .ne(tenant.getId() != null, SysTenant::getId, tenant.getId()));
        return !exist;
    }

    @Override
    public boolean checkAccountBalance(String tenantId) {
        SysTenant tenant = queryByTenantId(tenantId);
        if (tenant.getAccountCount() == -1) {
            return true;
        }
        Long userNumber = userMapper.selectCount(new LambdaQueryWrapper<>());
        return tenant.getAccountCount() - userNumber > 0;
    }

    @Override
    public boolean checkExpireTime(String tenantId) {
        SysTenant tenant = queryByTenantId(tenantId);
        if (tenant.getExpireTime() == null) {
            return true;
        }
        return new Date().before(tenant.getExpireTime());
    }

    /**
     * 生成租户ID
     */
    private String generateTenantId(List<String> tenantIds) {
        String numbers = RandomUtil.randomNumbers(6);
        if (tenantIds.contains(numbers)) {
            return generateTenantId(tenantIds);
        }
        return numbers;
    }

    /**
     * 根据租户菜单创建租户角色
     */
    private Long createTenantRole(String tenantId, Long packageId) {
        // 获取租户套餐
        SysTenantPackage tenantPackage = tenantPackageMapper.selectById(packageId);
        if (tenantPackage == null) {
            throw new TenantException("套餐不存在");
        }

        // 解析菜单ID列表
        List<Long> menuIds = new ArrayList<>();
        if (tenantPackage.getMenuIds() != null && !tenantPackage.getMenuIds().isEmpty()) {
            String[] ids = tenantPackage.getMenuIds().split(",");
            for (String id : ids) {
                try {
                    menuIds.add(Long.parseLong(id.trim()));
                } catch (NumberFormatException e) {
                    // 忽略无效ID
                }
            }
        }

        // 创建角色
        SysRole role = new SysRole();
        role.setTenantId(tenantId);
        role.setRoleName(TenantConstants.TENANT_ADMIN_ROLE_NAME);
        role.setRoleKey(TenantConstants.TENANT_ADMIN_ROLE_KEY);
        role.setStatus(1);
        roleMapper.insert(role);
        Long roleId = role.getRoleId();

        // 创建角色菜单关联
        for (Long menuId : menuIds) {
            SysRoleMenu roleMenu = new SysRoleMenu();
            roleMenu.setRoleId(roleId);
            roleMenu.setMenuId(menuId);
            roleMenuMapper.insert(roleMenu);
        }

        return roleId;
    }
}
