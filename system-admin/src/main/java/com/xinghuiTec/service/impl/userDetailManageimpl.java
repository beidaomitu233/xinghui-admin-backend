package com.xinghuiTec.service.impl;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.xinghuiTec.domain.entity.SysUser;
import com.xinghuiTec.domain.entity.SysUserRole;
import com.xinghuiTec.domain.entity.loginUser;
import com.xinghuiTec.mapper.SysMenuMapper;
import com.xinghuiTec.mapper.SysUserMapper;
import com.xinghuiTec.service.SysUserRoleService;
import com.xinghuiTec.utils.TenantHelper;
import jakarta.annotation.Resource;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.security.core.userdetails.UserDetails;
import org.springframework.security.core.userdetails.UserDetailsService;
import org.springframework.security.core.userdetails.UsernameNotFoundException;
import org.springframework.stereotype.Service;

import java.util.List;
import java.util.Objects;
import java.util.stream.Collectors;

@Service
public class UserDetailManageImpl implements UserDetailsService {

    @Resource
    private SysUserMapper userMapper;

    @Autowired
    private SysMenuMapper menuMapper;

    @Resource
    private SysUserRoleService roleService;

    /**
     * 按手机号加载用户（不限租户）
     * Spring Security 会将 loginDTO.phone 传入此方法的 username 参数
     */
    @Override
    public UserDetails loadUserByUsername(String phone) throws UsernameNotFoundException {
        // 不限租户查询：忽略租户隔离，按手机号匹配（取第一条匹配记录）
        SysUser user = TenantHelper.ignore(() -> {
            List<SysUser> users = userMapper.selectList(
                new LambdaQueryWrapper<SysUser>().eq(SysUser::getMobile, phone).last("LIMIT 1")
            );
            return users.isEmpty() ? null : users.get(0);
        });

        if (Objects.isNull(user)) {
            throw new UsernameNotFoundException("手机号未注册");
        }

        // 查询关联角色
        LambdaQueryWrapper<SysUserRole> sysUserRoleWrapper = new LambdaQueryWrapper<>();
        sysUserRoleWrapper.eq(SysUserRole::getUserId, user.getUserId());
        List<SysUserRole> userRoles = roleService.list(sysUserRoleWrapper);

        // 查询权限信息
        List<String> list = new java.util.ArrayList<>();
        if (!userRoles.isEmpty()) {
            List<Long> roleIds = userRoles.stream().map(SysUserRole::getRoleId).collect(Collectors.toList());
            List<String> perms = menuMapper.selectPermsByRoleIds(roleIds);
            if (perms != null) {
                list.addAll(perms);
            }
        }

        // 去重 + 过滤空值
        List<String> validPermissions = list.stream()
                .filter(perm -> perm != null && !perm.trim().isEmpty())
                .distinct()
                .collect(Collectors.toList());

        loginUser loginUser = new loginUser(user, validPermissions);
        // 从用户实体设置租户ID
        loginUser.setTenantId(user.getTenantId());

        return loginUser;
    }
}
