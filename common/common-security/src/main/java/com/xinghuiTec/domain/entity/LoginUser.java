package com.xinghuiTec.domain.entity;

import com.alibaba.fastjson2.annotation.JSONField;
import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;
import org.springframework.security.core.GrantedAuthority;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.core.userdetails.UserDetails;

import java.util.Collection;
import java.util.List;
import java.util.stream.Collectors;

@Data
@NoArgsConstructor
@AllArgsConstructor
public class LoginUser implements UserDetails {
    private SysUser user;
    //存储权限信息临时集合
    private List<String> permissions;
    /**
     * 获取用户权限集合
     * @return 用户权限集合
     */
    //获取权限
    @JSONField(serialize = false) //忽略序列化
    private List<SimpleGrantedAuthority> authorities;
    /**
     * 租户ID
     */
    private String tenantId;
    /** 当前有效令牌；同一账号再次登录时旧令牌失效。 */
    private String token;

    public LoginUser(SysUser user, List<String> validPermissions) {
        this.user = user;
        this.permissions = validPermissions;
    }

    public LoginUser(SysUser user) {
        this.user = user;
    }

    @Override
    public Collection<? extends GrantedAuthority> getAuthorities() {
        if (authorities != null){
            return authorities;
        }
        //把permissions中字符串类型的权限信息转换成GrantedAuthority对象存入authorities
        //过滤掉null和空字符串，避免IllegalArgumentException异常
        authorities = permissions.stream()
                .filter(permission -> permission != null && !permission.trim().isEmpty())
                .map(SimpleGrantedAuthority::new)
                .collect(Collectors.toList());
        return authorities;
    }
    @Override
    public String getPassword() {
        return user.getPassword();
    }
    /**
     * 返回手机号作为 Spring Security 的 username
     * 因为系统已切换为手机号登录
     */
    @Override
    public String getUsername() {
        return user.getMobile();
    }
    @Override
    public boolean isAccountNonExpired() {
        return true;
    }
    @Override
    public boolean isAccountNonLocked() {
        return true;
    }
    @Override
    public boolean isCredentialsNonExpired() {
        return true;
    }
    /**
     * 账号是否启用（status: 1正常 0停用）
     * 停用账号在认证阶段由 Spring Security 抛出 DisabledException 拒绝登录
     */
    @Override
    public boolean isEnabled() {
        return user != null && (user.getStatus() == null || user.getStatus() == 1);
    }
}
