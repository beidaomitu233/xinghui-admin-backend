package com.xinghuiTec.security;

import com.xinghuiTec.controller.SysTenantController;
import com.xinghuiTec.controller.SysTenantPackageController;
import com.xinghuiTec.domain.entity.LoginUser;
import com.xinghuiTec.domain.entity.SysUser;
import com.xinghuiTec.quartz.controller.SysJobController;
import com.xinghuiTec.quartz.controller.SysJobLogController;
import com.xinghuiTec.quartz.service.JobFireLogService;
import com.xinghuiTec.quartz.service.SysJobService;
import com.xinghuiTec.quartz.service.JobEngine;
import com.xinghuiTec.service.ISysTenantPackageService;
import com.xinghuiTec.service.ISysTenantService;
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
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;

class PlatformAccessMethodSecurityTest {

    @AfterEach
    void clearSecurityContext() {
        SecurityContextHolder.clearContext();
    }

    @Test
    void tenantEnabledRequiresPlatformAccountAndOperationPermission() {
        try (var context = context(true)) {
            var logs = context.getBean(SysJobLogController.class);
            var logService = context.getBean(JobFireLogService.class);

            authenticate("000001", "000001", "monitor:jobLog:clean");
            assertThrows(AccessDeniedException.class, logs::clean);
            authenticate("000000", "000001", "monitor:jobLog:clean");
            assertThrows(AccessDeniedException.class, logs::clean);
            authenticate("000000", "000000", "monitor:jobLog:list");
            assertThrows(AccessDeniedException.class, logs::clean);
            verifyNoInteractions(logService);

            authenticate("000000", "000000", "monitor:jobLog:clean");
            assertDoesNotThrow(logs::clean);
            verify(logService).clean();
        }
    }

    @Test
    void tenantDisabledAllowsAuthorizedAccountFromAnyTenant() {
        try (var context = context(false)) {
            var logs = context.getBean(SysJobLogController.class);
            authenticate("000001", "000001", "monitor:jobLog:clean");
            assertDoesNotThrow(logs::clean);
            verify(context.getBean(JobFireLogService.class)).clean();
        }
    }

    @Test
    void taskAndGlobalTenantManagementArePlatformGated() {
        try (var context = context(true)) {
            authenticate("000001", "000001", "monitor:job:query",
                    "system:tenant:query", "system:tenantPackage:query");
            assertThrows(AccessDeniedException.class,
                    () -> context.getBean(SysJobController.class).getInfo(1L));
            assertThrows(AccessDeniedException.class,
                    () -> context.getBean(SysTenantController.class).getInfo(1L));
            assertThrows(AccessDeniedException.class,
                    () -> context.getBean(SysTenantPackageController.class).getInfo(1L));
            verifyNoInteractions(context.getBean(SysJobService.class),
                    context.getBean(ISysTenantService.class),
                    context.getBean(ISysTenantPackageService.class));
        }
    }

    @Test
    void noLoginIsNotPlatformIdentity() {
        assertFalse(new PlatformAccessService(true).isPlatformIdentity());
        assertFalse(new PlatformAccessService(false).isPlatformIdentity());
    }

    private static AnnotationConfigApplicationContext context(boolean tenantEnabled) {
        var context = new AnnotationConfigApplicationContext();
        context.register(MethodSecurityConfiguration.class);
        context.registerBean("platformAccess", PlatformAccessService.class,
                () -> new PlatformAccessService(tenantEnabled));
        context.registerBean(SysJobController.class);
        context.registerBean(SysJobLogController.class);
        context.registerBean(SysTenantController.class);
        context.registerBean(SysTenantPackageController.class);
        context.registerBean(SysJobService.class, () -> mock(SysJobService.class));
        context.registerBean(JobEngine.class, () -> mock(JobEngine.class));
        context.registerBean(JobFireLogService.class, () -> mock(JobFireLogService.class));
        context.registerBean(ISysTenantService.class, () -> mock(ISysTenantService.class));
        context.registerBean(ISysTenantPackageService.class, () -> mock(ISysTenantPackageService.class));
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
