package com.xinghuiTec.ai.template;

import com.xinghuiTec.domain.entity.*;
import com.xinghuiTec.security.PlatformAccessService;
import org.junit.jupiter.api.*;
import org.springframework.context.annotation.*;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.config.annotation.method.configuration.EnableMethodSecurity;
import org.springframework.security.core.context.SecurityContextHolder;
import java.util.List;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

class TemplateOptionsSecurityTest {
    @AfterEach void clear() { SecurityContextHolder.clearContext(); }
    private AnnotationConfigApplicationContext context() {
        var c = new AnnotationConfigApplicationContext(); c.register(Security.class);
        c.registerBean("platformAccess", PlatformAccessService.class, () -> new PlatformAccessService(true));
        c.registerBean(TemplateDataService.class, () -> mock(TemplateDataService.class));
        c.registerBean(TemplateAdminController.class); c.refresh(); return c;
    }
    private void login(String tenant, String... permissions) {
        var user = new SysUser(); user.setUserId(101L); user.setTenantId(tenant);
        var principal = new LoginUser(user, List.of(permissions)); principal.setTenantId(tenant);
        SecurityContextHolder.getContext().setAuthentication(new UsernamePasswordAuthenticationToken(principal, null, principal.getAuthorities()));
    }
    @Test void applicationActionsIndependentlyAllowMetadataButNotDraftAccess() {
        try (var c = context()) {
            var api = c.getBean(TemplateAdminController.class);
            for (String permission : List.of("ai:template:query", "ai:application:query", "ai:application:add", "ai:application:edit", "ai:application:test")) {
                login("000000", permission);
                assertDoesNotThrow(() -> api.options(1, 20, null, "201"));
                if (!permission.equals("ai:template:query")) assertThrows(AccessDeniedException.class, () -> api.get(1));
                assertThrows(AccessDeniedException.class, () -> api.save(1, null));
            }
            verify(c.getBean(TemplateDataService.class), times(5)).options(1, 20, null, "201");
        }
    }
    @Test void tenantMisassignmentAnonymousAndUnrelatedActionsCannotAccessOptions() {
        try (var c = context()) {
            var api = c.getBean(TemplateAdminController.class);
            assertThrows(AccessDeniedException.class, () -> api.options(1, 20, null, null));
            login("tenant_a", "ai:application:edit", "ai:template:query");
            assertThrows(AccessDeniedException.class, () -> api.options(1, 20, null, null));
            login("000000", "ai:template:list", "ai:data:query");
            assertThrows(AccessDeniedException.class, () -> api.options(1, 20, null, null));
            verifyNoInteractions(c.getBean(TemplateDataService.class));
        }
    }
    @Configuration @EnableMethodSecurity static class Security {}
}
