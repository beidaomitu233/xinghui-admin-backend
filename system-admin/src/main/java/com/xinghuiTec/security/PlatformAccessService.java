package com.xinghuiTec.security;

import com.xinghuiTec.constants.TenantConstants;
import com.xinghuiTec.domain.entity.LoginUser;
import com.xinghuiTec.utils.SecurityUtils;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

/** Platform management identity gate used alongside operation permissions. */
@Component("platformAccess")
public class PlatformAccessService {

    private final boolean tenantEnabled;

    public PlatformAccessService(@Value("${tenant.enable:false}") boolean tenantEnabled) {
        this.tenantEnabled = tenantEnabled;
    }

    public boolean isPlatformIdentity() {
        LoginUser loginUser = SecurityUtils.getLoginUser();
        if (loginUser == null || loginUser.getUser() == null) {
            return false;
        }
        if (!tenantEnabled) {
            return true;
        }
        // Check the account and session tenant, never a dynamic/ignored SQL tenant context.
        return TenantConstants.DEFAULT_TENANT_ID.equals(loginUser.getUser().getTenantId())
                && TenantConstants.DEFAULT_TENANT_ID.equals(loginUser.getTenantId());
    }
}
