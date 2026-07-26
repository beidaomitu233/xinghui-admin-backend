package com.xinghuiTec.service.impl;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.xinghuiTec.domain.entity.SysUser;
import com.xinghuiTec.domain.entity.SysUserRole;
import com.xinghuiTec.domain.entity.LoginUser;
import com.xinghuiTec.mapper.SysMenuMapper;
import com.xinghuiTec.mapper.SysUserMapper;
import com.xinghuiTec.service.SysUserRoleService;
import com.xinghuiTec.utils.TenantHelper;
import jakarta.annotation.Resource;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.security.authentication.InternalAuthenticationServiceException;
import org.springframework.security.core.userdetails.UserDetails;
import org.springframework.security.core.userdetails.UserDetailsService;
import org.springframework.security.core.userdetails.UsernameNotFoundException;
import org.springframework.stereotype.Service;

import java.util.List;
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
     * Spring Security 会将 LoginDTO.phone 传入此方法的 username 参数
     */
    @Override
    public UserDetails loadUserByUsername(String phone) throws UsernameNotFoundException {
        // 不限租户查询：忽略租户隔离，按手机号匹配
        List<SysUser> users = TenantHelper.ignore(() -> userMapper.selectList(
                new LambdaQueryWrapper<SysUser>().eq(SysUser::getMobile, phone)
        ));

        if (users == null || users.isEmpty()) {
            throw new UsernameNotFoundException("手机号未注册");
        }
        // 同一手机号匹配到多个账号属于数据异常：拒绝登录并告警，
        // 避免无 ORDER BY 的 LIMIT 1 随机命中他人/他租户账号
        if (users.size() > 1) {
            throw new InternalAuthenticationServiceException("手机号关联多个账号，数据异常，请联系管理员");
        }
        SysUser user = users.get(0);

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

        LoginUser loginUser = new LoginUser(user, validPermissions);
        // 从用户实体设置租户ID
        loginUser.setTenantId(user.getTenantId());

        return loginUser;
    }
}
