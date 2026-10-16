package com.xinghuiTec.lowcode;

import com.xinghuiTec.domain.entity.LoginUser;
import com.xinghuiTec.utils.SecurityUtils;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.stereotype.Component;

/** Record ownership comes from the authenticated session, never from request JSON. */
@Component
public class LowcodeIdentity {
    private final boolean tenantEnabled;

    public LowcodeIdentity(@Value("${tenant.enable:false}") boolean tenantEnabled) {
        this.tenantEnabled = tenantEnabled;
    }

    public String tenant() {
        LoginUser login = SecurityUtils.getLoginUser();
        if (!SecurityUtils.isLogin() || login == null || login.getUser() == null || login.getUser().getUserId() == null) {
            throw new AccessDeniedException("需要登录");
        }
        String tenant = tenantEnabled ? login.getTenantId() : "000000";
        if (tenant == null || !tenant.matches("[a-zA-Z0-9_-]{1,20}")) {
            throw new AccessDeniedException("租户身份无效");
        }
        return tenant;
    }

    public String creator() {
        tenant();
        return String.valueOf(SecurityUtils.getUserId());
    }
}
