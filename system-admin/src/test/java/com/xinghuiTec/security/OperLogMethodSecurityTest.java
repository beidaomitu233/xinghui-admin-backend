package com.xinghuiTec.security;

import com.xinghuiTec.controller.SysOperLogController;
import com.xinghuiTec.domain.entity.LoginUser;
import com.xinghuiTec.domain.entity.SysUser;
import com.xinghuiTec.service.SysOperLogService;
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

class OperLogMethodSecurityTest {
    @AfterEach
    void clear() {
        SecurityContextHolder.clearContext();
    }

    @Test
    void tenantOnRequiresPlatformAndDedicatedCleanCode() {
        try (var context = context(true)) {
            var controller = context.getBean(SysOperLogController.class);
            var service = context.getBean(SysOperLogService.class);
            authenticate("000001", "monitor:operlog:clean", "monitor:operlog:list");
            assertThrows(AccessDeniedException.class, controller::cleanOperLog);
            assertThrows(AccessDeniedException.class,
                    () -> controller.getOperLogList(null));
            authenticate("000000", "monitor:operlog:remove");
            assertThrows(AccessDeniedException.class, controller::cleanOperLog);
            verifyNoInteractions(service);
            authenticate("000000", "monitor:operlog:clean");
            assertDoesNotThrow(controller::cleanOperLog);
            verify(service).cleanOperLog();
        }
    }

    @Test
    void tenantOffAllowsAuthorizedNonDefaultAccountButNotRemoveOnlyAccount() {
        try (var context = context(false)) {
            var controller = context.getBean(SysOperLogController.class);
            authenticate("000001", "monitor:operlog:remove");
            assertThrows(AccessDeniedException.class, controller::cleanOperLog);
            authenticate("000001", "monitor:operlog:clean");
            assertDoesNotThrow(controller::cleanOperLog);
            verify(context.getBean(SysOperLogService.class)).cleanOperLog();
        }
    }

    private static AnnotationConfigApplicationContext context(boolean tenantEnabled) {
        var context = new AnnotationConfigApplicationContext();
        context.register(MethodSecurityConfiguration.class);
        context.registerBean("platformAccess", PlatformAccessService.class,
                () -> new PlatformAccessService(tenantEnabled));
        context.registerBean(SysOperLogController.class);
        context.registerBean(SysOperLogService.class, () -> mock(SysOperLogService.class));
        context.refresh();
        return context;
    }

    private static void authenticate(String tenant, String... permissions) {
        var user = new SysUser();
        user.setUserId(1L);
        user.setTenantId(tenant);
        var loginUser = new LoginUser(user, List.of(permissions));
        loginUser.setTenantId(tenant);
        SecurityContextHolder.getContext().setAuthentication(
                new UsernamePasswordAuthenticationToken(loginUser, null, loginUser.getAuthorities()));
    }

    @Configuration
    @EnableMethodSecurity
    static class MethodSecurityConfiguration {
    }
}
