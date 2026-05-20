package com.xinghuiTec.utils;

import com.xinghuiTec.domain.entity.SysUser;
import com.xinghuiTec.domain.entity.loginUser;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.context.SecurityContextHolder;

public class SecurityUtils {

    /**
     * 获取当前登录用户实体
     */
    public static SysUser getUser() {
        loginUser loginUser = getLoginUser();
        return loginUser != null ? loginUser.getUser() : null;
    }

    /**
     * 获取当前登录用户完整信息（含权限、租户ID等）
     */
    public static loginUser getLoginUser() {
        Authentication authentication = SecurityContextHolder.getContext().getAuthentication();
        if (authentication != null && authentication.getPrincipal() instanceof loginUser) {
            return (loginUser) authentication.getPrincipal();
        }
        return null;
    }

    /**
     * 获取当前登录用户的租户ID
     */
    public static String getTenantId() {
        loginUser loginUser = getLoginUser();
        return loginUser != null ? loginUser.getTenantId() : null;
    }

    /**
     * 获取当前登录用户的ID
     */
    public static String getUserId() {
        SysUser user = getUser();
        return user != null ? user.getUserId() : null;
    }

    /**
     * 判断当前是否已登录
     */
    public static boolean isLogin() {
        try {
            Authentication authentication = SecurityContextHolder.getContext().getAuthentication();
            return authentication != null && authentication.isAuthenticated()
                    && authentication.getPrincipal() instanceof loginUser;
        } catch (Exception e) {
            return false;
        }
    }
}