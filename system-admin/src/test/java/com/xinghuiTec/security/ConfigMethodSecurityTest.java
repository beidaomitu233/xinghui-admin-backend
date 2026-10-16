package com.xinghuiTec.security;

import com.xinghuiTec.controller.SysConfigController;
import com.xinghuiTec.domain.dto.ConfigChangeRequest;
import com.xinghuiTec.domain.entity.LoginUser;
import com.xinghuiTec.domain.entity.SysUser;
import com.xinghuiTec.service.SysConfigService;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.context.annotation.AnnotationConfigApplicationContext;
import org.springframework.context.annotation.Configuration;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.config.annotation.method.configuration.EnableMethodSecurity;
import org.springframework.security.core.context.SecurityContextHolder;

import java.util.List;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

class ConfigMethodSecurityTest {
    @AfterEach
    void clear() {
        SecurityContextHolder.clearContext();
    }

    @Test
    void tenantOnRequiresPlatformIdentityAndExactOperationCode() {
        try (var context = context(true)) {
            var controller = context.getBean(SysConfigController.class);
            var service = context.getBean(SysConfigService.class);
            var change = new ConfigChangeRequest("sys.index.sideTheme", "theme-light");

            authenticate("000001", "000001", "system:config:list", "system:config:edit");
            assertThrows(AccessDeniedException.class, controller::list);
            assertThrows(AccessDeniedException.class, () -> controller.edit(3, change));
            authenticate("000000", "000000", "system:config:list");
            assertDoesNotThrow(controller::list);
            assertThrows(AccessDeniedException.class, () -> controller.edit(3, change));
            verify(service).listManaged();
            verify(service, never()).updateManaged(any(), any());
        }
    }

    @Test
    void tenantOffAllowsAuthorizedNonDefaultAccount() {
        try (var context = context(false)) {
            authenticate("000001", "000001", "system:config:edit");
            var change = new ConfigChangeRequest("sys.index.sideTheme", "theme-light");
            context.getBean(SysConfigController.class).edit(3, change);
            verify(context.getBean(SysConfigService.class)).updateManaged(3, change);
        }
    }

    private static AnnotationConfigApplicationContext context(boolean tenantEnabled) {
        var context = new AnnotationConfigApplicationContext();
        context.register(MethodSecurityConfiguration.class);
        context.registerBean("platformAccess", PlatformAccessService.class,
                () -> new PlatformAccessService(tenantEnabled));
        context.registerBean(SysConfigController.class);
        context.registerBean(SysConfigService.class, () -> mock(SysConfigService.class));
        context.refresh();
        return context;
    }

    private static void authenticate(String accountTenant, String sessionTenant, String... permissions) {
        var user = new SysUser();
        user.setUserId(1L);
        user.setTenantId(accountTenant);
        var loginUser = new LoginUser(user, List.of(permissions));
        loginUser.setTenantId(sessionTenant);
        SecurityContextHolder.getContext().setAuthentication(
                new UsernamePasswordAuthenticationToken(loginUser, null, loginUser.getAuthorities()));
    }

    @Configuration
    @EnableMethodSecurity
    static class MethodSecurityConfiguration {
    }
}
