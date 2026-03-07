package com.xinghuiTec.service.impl;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.xinghuiTec.domain.entity.SysUser;
import com.xinghuiTec.domain.entity.SysUserRole;
import com.xinghuiTec.domain.entity.loginUser;
import com.xinghuiTec.mapper.SysMenuMapper;
import com.xinghuiTec.mapper.SysUserMapper;
import com.xinghuiTec.service.SysUserRoleService;
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
public class userDetailManageimpl implements UserDetailsService {
    @Resource
    private SysUserMapper userMapper;
    @Autowired
    private SysMenuMapper menuMapper;
    @Resource
    private SysUserRoleService roleService;

    /**
     * 查询用户是否存在并进行授权
     * 
     * @param username
     * @return loginUser(user) 用户信息
     * @throws UsernameNotFoundException 当用户不存在时抛出异常
     */
    @Override
    public UserDetails loadUserByUsername(String username) throws UsernameNotFoundException {
        // 从数据库中查询用户是否存在
        SysUser user = userMapper.selectOne(new LambdaQueryWrapper<SysUser>().eq(SysUser::getUsername, username));

        if (Objects.isNull(user)) {
            // 用户不存在时，必须抛出 UsernameNotFoundException
            throw new UsernameNotFoundException("用户不存在");
        }

        // 查询关联角色，以此授权
        // 优化：支持多角色
        LambdaQueryWrapper<SysUserRole> sysUserRoleWrapper = new LambdaQueryWrapper<>();
        sysUserRoleWrapper.eq(SysUserRole::getUserId, user.getUserId());
        List<SysUserRole> userRoles = roleService.list(sysUserRoleWrapper);

/*        if (userRoles == null || userRoles.isEmpty()) {
            // 这里直接抛出 RuntimeException，Spring Security 会将其包装为
            // InternalAuthenticationServiceException
            // 从而保留原始错误信息 "用户未分配角色"，而不会被转换为 BadCredentialsException
            throw new RuntimeException("用户未分配角色");
        }*/

        // 查询权限信息封装到LoginUser中
        List<String> list = new java.util.ArrayList<>();
        for (SysUserRole role : userRoles) {
            List<String> perms = menuMapper.selectPermsByUserId(role.getRoleId());
            if (perms != null) {
                list.addAll(perms);
            }
        }

        // 去重
        list = list.stream().distinct().collect(Collectors.toList());

        // 过滤掉null和空字符串权限
        List<String> validPermissions = list.stream()
                .filter(perm -> perm != null && !perm.trim().isEmpty())
                .collect(Collectors.toList());

        loginUser loginUser = new loginUser(user, validPermissions);

        return loginUser;
    }
}