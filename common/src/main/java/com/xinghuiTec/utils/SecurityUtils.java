package com.xinghuiTec.utils;

import com.xinghuiTec.domain.entity.SysUser;
import com.xinghuiTec.domain.entity.loginUser;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.context.SecurityContextHolder;

public class SecurityUtils {
    public static SysUser getUser() {
        Authentication authentication = SecurityContextHolder.getContext().getAuthentication();
        loginUser loginUser = (loginUser) authentication.getPrincipal();

        return loginUser.getUser();
    }
}