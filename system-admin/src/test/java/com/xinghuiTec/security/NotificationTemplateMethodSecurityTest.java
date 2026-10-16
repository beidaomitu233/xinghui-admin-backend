package com.xinghuiTec.security;

import com.xinghuiTec.controller.NotificationTemplateAdminController;
import com.xinghuiTec.domain.entity.LoginUser;
import com.xinghuiTec.domain.entity.SysUser;
import com.xinghuiTec.notification.NotificationTemplateService;
import com.xinghuiTec.notification.NotificationTemplateService.Edit;
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

class NotificationTemplateMethodSecurityTest {
    @AfterEach
    void clear() {
        SecurityContextHolder.clearContext();
    }

    @Test
    void tenantWithMisassignedPermissionCannotReadOrEditTemplates() {
        try (var context = context(true)) {
            var admin = context.getBean(NotificationTemplateAdminController.class);
            var service = context.getBean(NotificationTemplateService.class);
            authenticate("000001", "system:notificationTemplate:list", "system:notificationTemplate:edit");
            assertThrows(AccessDeniedException.class, admin::list);
            assertThrows(AccessDeniedException.class, () -> admin.edit(1, edit()));
            verifyNoInteractions(service);

            authenticate("000000", "system:notificationTemplate:list");
            assertDoesNotThrow(admin::list);
            assertThrows(AccessDeniedException.class, () -> admin.edit(1, edit()));
        }
    }

    @Test
    void tenantDisabledUsesExactTemplatePermission() {
        try (var context = context(false)) {
            var admin = context.getBean(NotificationTemplateAdminController.class);
            var service = context.getBean(NotificationTemplateService.class);
            authenticate("000001", "system:notificationTemplate:edit");
            assertDoesNotThrow(() -> admin.edit(1, edit()));
            verify(service).edit(eq(1L), any());
            assertThrows(AccessDeniedException.class, admin::list);
        }
    }

    private static Edit edit() {
        return new Edit("示例", "标题", "正文", null, List.of(), true);
    }

    private static AnnotationConfigApplicationContext context(boolean tenantEnabled) {
        var context = new AnnotationConfigApplicationContext();
        context.register(MethodSecurityConfiguration.class);
        context.registerBean("platformAccess", PlatformAccessService.class,
                () -> new PlatformAccessService(tenantEnabled));
        context.registerBean(NotificationTemplateAdminController.class);
        context.registerBean(NotificationTemplateService.class,
                () -> mock(NotificationTemplateService.class));
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
    static class MethodSecurityConfiguration {}
}
