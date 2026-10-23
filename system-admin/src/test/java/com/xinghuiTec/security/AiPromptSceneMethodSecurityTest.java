package com.xinghuiTec.security;

import com.xinghuiTec.ai.prompt.AiPromptSceneRules.VariableDefinition;
import com.xinghuiTec.ai.prompt.AiPromptSceneRules.VariableType;
import com.xinghuiTec.ai.prompt.AiPromptSceneService;
import com.xinghuiTec.ai.prompt.AiPromptSceneService.CreateRequest;
import com.xinghuiTec.ai.prompt.AiPromptSceneService.DraftRequest;
import com.xinghuiTec.ai.prompt.AiPromptSceneService.PreviewRequest;
import com.xinghuiTec.ai.prompt.AiPromptSceneService.RevisionRequest;
import com.xinghuiTec.ai.prompt.AiPromptSceneService.StatusRequest;
import com.xinghuiTec.controller.AiPromptSceneAdminController;
import com.xinghuiTec.domain.entity.LoginUser;
import com.xinghuiTec.domain.entity.SysUser;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.context.annotation.AnnotationConfigApplicationContext;
import org.springframework.context.annotation.Configuration;
import org.springframework.http.HttpStatus;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.config.annotation.method.configuration.EnableMethodSecurity;
import org.springframework.security.core.context.SecurityContextHolder;

import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

class AiPromptSceneMethodSecurityTest {
    @AfterEach
    void clearSecurity() {
        SecurityContextHolder.clearContext();
    }

    @Test
    void tenantWithMisassignedScenePermissionsCannotCallAnyAdminOperation() {
        try (var context = context(true)) {
            var controller = context.getBean(AiPromptSceneAdminController.class);
            var service = context.getBean(AiPromptSceneService.class);
            authenticate("000001", "000001", "ai:prompt:list", "ai:prompt:query", "ai:prompt:add",
                    "ai:prompt:edit", "ai:prompt:publish", "ai:prompt:enable", "ai:prompt:remove");

            assertThrows(AccessDeniedException.class, () -> controller.page(1, 20, null, null, null, null, null, null));
            assertThrows(AccessDeniedException.class, controller::availableModels);
            assertThrows(AccessDeniedException.class, () -> controller.get(1));
            assertThrows(AccessDeniedException.class, () -> controller.create(create()));
            assertThrows(AccessDeniedException.class, () -> controller.updateDraft(1, draft()));
            assertThrows(AccessDeniedException.class, () -> controller.preview(1, preview()));
            assertThrows(AccessDeniedException.class, () -> controller.publish(1, new RevisionRequest(0)));
            assertThrows(AccessDeniedException.class, () -> controller.setEnabled(1, new StatusRequest(false, 0)));
            assertThrows(AccessDeniedException.class, () -> controller.delete(1, 0));
            verifyNoInteractions(service);
        }
    }

    @Test
    void platformIdentityStillNeedsEachOperationPermission() {
        try (var context = context(true)) {
            var controller = context.getBean(AiPromptSceneAdminController.class);
            var service = context.getBean(AiPromptSceneService.class);
            authenticate("000000", "000000");
            assertThrows(AccessDeniedException.class, () -> controller.page(1, 20, null, null, null, null, null, null));
            assertThrows(AccessDeniedException.class, controller::availableModels);
            assertThrows(AccessDeniedException.class, () -> controller.create(create()));
            verifyNoInteractions(service);

            authenticate("000000", "000000", "ai:prompt:list");
            assertDoesNotThrow(() -> controller.page(1, 20, null, null, null, null, null, null));
            assertThrows(AccessDeniedException.class, controller::availableModels);
            assertThrows(AccessDeniedException.class, () -> controller.get(1));
            verify(service).page(1, 20, null, null, null, null, null, null);
            verify(service, never()).availableModels();
        }
    }

    @Test
    void tenantDisabledAllowsAnAuthorizedAdministratorButStillChecksPermission() {
        try (var context = context(false)) {
            var controller = context.getBean(AiPromptSceneAdminController.class);
            var service = context.getBean(AiPromptSceneService.class);
            authenticate("000001", "000001", "ai:prompt:add");
            assertDoesNotThrow(() -> controller.create(create()));
            assertDoesNotThrow(controller::availableModels);
            verify(service).create(any());
            verify(service).availableModels();
            assertThrows(AccessDeniedException.class, () -> controller.page(1, 20, null, null, null, null, null, null));
            verify(service, never()).page(any(), any(), any(), any(), any(), any(), any(), any());
        }
    }

    @Test
    void publishPermissionDoesNotAuthorizeEditingAndMalformedBodiesGetStaticErrors() {
        try (var context = context(true)) {
            var controller = context.getBean(AiPromptSceneAdminController.class);
            var service = context.getBean(AiPromptSceneService.class);
            authenticate("000000", "000000", "ai:prompt:publish");
            assertDoesNotThrow(() -> controller.publish(1, new RevisionRequest(0)));
            assertThrows(AccessDeniedException.class, () -> controller.updateDraft(1, draft()));
            assertThrows(AccessDeniedException.class, () -> controller.setEnabled(1, new StatusRequest(false, 0)));
            verify(service).publish(1, new RevisionRequest(0));
            verify(service, never()).updateDraft(anyLong(), any());
            verify(service, never()).setEnabled(anyLong(), any());

            var invalid = controller.invalidBody();
            assertEquals(HttpStatus.BAD_REQUEST, invalid.getStatusCode());
            assertEquals("请求数据格式无效", invalid.getBody().getMessage());
        }
    }

    private static CreateRequest create() {
        return new CreateRequest("sample_app", "summarize", 1L, "Summarize {{recordName}}", variables());
    }

    private static DraftRequest draft() {
        return new DraftRequest(1L, "Summarize {{recordName}}", variables(), 0);
    }

    private static PreviewRequest preview() {
        return new PreviewRequest(0, Map.of("recordName", "Example"));
    }

    private static List<VariableDefinition> variables() {
        return List.of(new VariableDefinition("recordName", VariableType.STRING,
                "Neutral sample name", false, "Example"));
    }

    private static AnnotationConfigApplicationContext context(boolean tenantEnabled) {
        var context = new AnnotationConfigApplicationContext();
        context.register(MethodSecurityConfiguration.class);
        context.registerBean("platformAccess", PlatformAccessService.class,
                () -> new PlatformAccessService(tenantEnabled));
        context.registerBean(AiPromptSceneAdminController.class);
        context.registerBean(AiPromptSceneService.class, () -> mock(AiPromptSceneService.class));
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
