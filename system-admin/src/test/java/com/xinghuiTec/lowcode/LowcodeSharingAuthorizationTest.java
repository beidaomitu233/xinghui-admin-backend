package com.xinghuiTec.lowcode;

import com.xinghuiTec.domain.entity.LoginUser;
import com.xinghuiTec.domain.entity.SysUser;
import com.xinghuiTec.security.PlatformAccessService;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.context.annotation.AnnotationConfigApplicationContext;
import org.springframework.context.annotation.Configuration;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.security.authentication.AnonymousAuthenticationToken;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.config.annotation.method.configuration.EnableMethodSecurity;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.core.context.SecurityContextHolder;
import java.util.List;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

class LowcodeSharingAuthorizationTest {
    @Configuration @EnableMethodSecurity static class Methods {}
    @AfterEach void clear() { SecurityContextHolder.clearContext(); }
    private AnnotationConfigApplicationContext context() {
        var context = new AnnotationConfigApplicationContext();
        context.registerBean("platformAccess", PlatformAccessService.class, () -> new PlatformAccessService(true));
        context.registerBean(LowcodeSharingService.class, () -> mock(LowcodeSharingService.class));
        context.registerBean(LowcodeService.class, () -> mock(LowcodeService.class));
        context.register(Methods.class, LowcodeSharingController.class, LowcodeFillController.class, LowcodeRecordController.class);
        context.refresh(); return context;
    }
    @Test void sharingSettingsRequireExactPlatformPublishAuthority() {
        try (var context = context()) {
            var controller = context.getBean(LowcodeSharingController.class);
            for (var authorities : List.of(List.<String>of(), List.of("lowcode:record:add"), List.of("lowcode:form:query"))) {
                login("000000", authorities);
                assertThrows(AccessDeniedException.class, () -> controller.settings(1));
                assertThrows(AccessDeniedException.class, () -> controller.configure(1, null));
            }
            login("tenant_a", List.of("lowcode:form:publish"));
            assertThrows(AccessDeniedException.class, () -> controller.settings(1));
            verifyNoInteractions(context.getBean(LowcodeSharingService.class));
            login("000000", List.of("lowcode:form:publish"));
            assertDoesNotThrow(() -> controller.settings(1));
            assertDoesNotThrow(() -> controller.configure(1, null));
        }
    }
    @Test void aFillTokenDoesNotGrantAnyRecordManagementOperation() {
        try (var context = context()) {
            var filling = context.getBean(LowcodeFillController.class);
            var records = context.getBean(LowcodeRecordController.class);
            SecurityContextHolder.getContext().setAuthentication(new AnonymousAuthenticationToken("test", "anonymousUser", List.of(new SimpleGrantedAuthority("ROLE_ANONYMOUS"))));
            for (boolean loggedIn : List.of(false, true)) {
                if (loggedIn) login("000000", List.of());
                assertDoesNotThrow(() -> filling.view("a".repeat(43)));
                assertDoesNotThrow(() -> filling.submit("a".repeat(43), null));
                assertThrows(AccessDeniedException.class, () -> records.list(1, 1, 10, null, null));
                assertThrows(AccessDeniedException.class, () -> records.get(1, 2));
                assertThrows(AccessDeniedException.class, () -> records.create(1, null));
                assertThrows(AccessDeniedException.class, () -> records.update(1, 2, null));
                assertThrows(AccessDeniedException.class, () -> records.remove(1, 2, 0));
            }
            verifyNoInteractions(context.getBean(LowcodeService.class));
        }
    }
    private void login(String tenant, List<String> authorities) {
        var user = new SysUser(); user.setUserId(9L); user.setTenantId(tenant); user.setStatus(1);
        var login = new LoginUser(user, authorities); login.setTenantId(tenant);
        SecurityContextHolder.getContext().setAuthentication(new UsernamePasswordAuthenticationToken(login, null, login.getAuthorities()));
    }
}
