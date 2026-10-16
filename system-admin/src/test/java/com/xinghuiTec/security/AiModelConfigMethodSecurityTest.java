package com.xinghuiTec.security;

import com.xinghuiTec.ai.model.AiModelConfigService;
import com.xinghuiTec.ai.model.AiModelConfigService.CreateRequest;
import com.xinghuiTec.ai.model.AiModelConfigService.StatusRequest;
import com.xinghuiTec.ai.model.AiModelConfigService.UpdateRequest;
import com.xinghuiTec.controller.AiModelConfigAdminController;
import com.xinghuiTec.domain.entity.LoginUser;
import com.xinghuiTec.domain.entity.SysUser;
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

class AiModelConfigMethodSecurityTest {
    @AfterEach
    void clearSecurity() {
        SecurityContextHolder.clearContext();
    }

    @Test
    void tenantIdentityWithMisassignedPermissionsCannotCallAnyAdminOperation() {
        try (var context = context(true)) {
            var controller = context.getBean(AiModelConfigAdminController.class);
            var service = context.getBean(AiModelConfigService.class);
            authenticate("000001", "000001", "ai:model:list", "ai:model:query", "ai:model:add",
                    "ai:model:edit", "ai:model:remove");

            assertThrows(AccessDeniedException.class, () -> controller.page(1, 20, null, null, null));
            assertThrows(AccessDeniedException.class, () -> controller.get(1));
            assertThrows(AccessDeniedException.class, () -> controller.create(create()));
            assertThrows(AccessDeniedException.class, () -> controller.update(1, update()));
            assertThrows(AccessDeniedException.class, () -> controller.setEnabled(1, new StatusRequest(false, 0)));
            assertThrows(AccessDeniedException.class, () -> controller.delete(1, 0));
            verifyNoInteractions(service);
        }
    }

    @Test
    void platformIdentityMustAlsoHaveTheExactOperationPermission() {
        try (var context = context(true)) {
            var controller = context.getBean(AiModelConfigAdminController.class);
            var service = context.getBean(AiModelConfigService.class);
            authenticate("000000", "000000");
            assertThrows(AccessDeniedException.class, () -> controller.page(1, 20, null, null, null));
            assertThrows(AccessDeniedException.class, () -> controller.get(1));
            verifyNoInteractions(service);

            authenticate("000000", "000000", "ai:model:list");
            assertDoesNotThrow(() -> controller.page(1, 20, null, null, null));
            assertThrows(AccessDeniedException.class, () -> controller.get(1));
            assertThrows(AccessDeniedException.class, () -> controller.create(create()));
            verify(service).page(1, 20, null, null, null);
            verify(service, never()).get(anyLong());
            verify(service, never()).create(any());
        }
    }

    @Test
    void tenantDisabledAllowsAnAuthorizedAdministratorAndStillChecksPermission() {
        try (var context = context(false)) {
            var controller = context.getBean(AiModelConfigAdminController.class);
            var service = context.getBean(AiModelConfigService.class);
            authenticate("000001", "000001", "ai:model:add");
            assertDoesNotThrow(() -> controller.create(create()));
            verify(service).create(any());
            assertThrows(AccessDeniedException.class, () -> controller.page(1, 20, null, null, null));
            verify(service, never()).page(any(), any(), any(), any(), any());
        }
    }

    private static CreateRequest create() {
        return new CreateRequest("openai", "gpt-4.1-mini", "https://api.example.com/v1",
                "OPENAI_API_KEY", 30, true);
    }

    private static UpdateRequest update() {
        return new UpdateRequest("openai", "gpt-4.1-mini", "https://api.example.com/v1",
                "OPENAI_API_KEY", 30, true, 0);
    }

    private static AnnotationConfigApplicationContext context(boolean tenantEnabled) {
        var context = new AnnotationConfigApplicationContext();
        context.register(MethodSecurityConfiguration.class);
        context.registerBean("platformAccess", PlatformAccessService.class,
                () -> new PlatformAccessService(tenantEnabled));
        context.registerBean(AiModelConfigAdminController.class);
        context.registerBean(AiModelConfigService.class, () -> mock(AiModelConfigService.class));
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
    static class MethodSecurityConfiguration {}
}
