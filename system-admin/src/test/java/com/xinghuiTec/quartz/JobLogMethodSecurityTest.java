package com.xinghuiTec.quartz;

import com.xinghuiTec.domain.entity.LoginUser;
import com.xinghuiTec.domain.entity.SysUser;
import com.xinghuiTec.quartz.controller.SysJobLogController;
import com.xinghuiTec.quartz.service.JobFireLogService;
import com.xinghuiTec.security.PlatformAccessService;
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

class JobLogMethodSecurityTest {
    @AfterEach
    void clear() {
        SecurityContextHolder.clearContext();
    }

    @Test
    void tenantModeBlocksTenantEvenWithCodesAndSeparatesEveryOperation() {
        try (var context = context(true)) {
            var controller = context.getBean(SysJobLogController.class);
            var logs = context.getBean(JobFireLogService.class);
            assertThrows(AccessDeniedException.class, () -> controller.list(1, 10, null, null, null, null));
            authenticate("000001", "monitor:jobLog:list", "monitor:jobLog:query",
                    "monitor:jobLog:remove", "monitor:jobLog:clean");
            assertThrows(AccessDeniedException.class, () -> controller.list(1, 10, null, null, null, null));
            assertThrows(AccessDeniedException.class, () -> controller.detail(1L));
            assertThrows(AccessDeniedException.class, () -> controller.remove(List.of(1L)));
            assertThrows(AccessDeniedException.class, controller::clean);
            verifyNoInteractions(logs);

            authenticate("000000", "monitor:jobLog:list");
            assertDoesNotThrow(() -> controller.list(1, 10, null, null, null, null));
            assertThrows(AccessDeniedException.class, () -> controller.detail(1L));
            assertThrows(AccessDeniedException.class, () -> controller.remove(List.of(1L)));
            assertThrows(AccessDeniedException.class, controller::clean);
            authenticate("000000", "monitor:jobLog:query");
            assertDoesNotThrow(() -> controller.detail(1L));
            authenticate("000000", "monitor:jobLog:remove");
            assertDoesNotThrow(() -> controller.remove(List.of(1L)));
            authenticate("000000", "monitor:jobLog:clean");
            assertDoesNotThrow(controller::clean);
        }
    }

    @Test
    void tenantDisabledStillRequiresMatchingCode() {
        try (var context = context(false)) {
            var controller = context.getBean(SysJobLogController.class);
            authenticate("000001", "monitor:jobLog:list");
            assertDoesNotThrow(() -> controller.list(1, 10, null, null, null, null));
            assertThrows(AccessDeniedException.class, controller::clean);
            authenticate("000001", "monitor:jobLog:clean");
            assertDoesNotThrow(controller::clean);
        }
    }

    private static AnnotationConfigApplicationContext context(boolean tenantEnabled) {
        var context = new AnnotationConfigApplicationContext();
        context.register(MethodSecurityConfiguration.class);
        context.registerBean("platformAccess", PlatformAccessService.class,
                () -> new PlatformAccessService(tenantEnabled));
        context.registerBean(SysJobLogController.class);
        context.registerBean(JobFireLogService.class, () -> mock(JobFireLogService.class));
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
