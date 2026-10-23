package com.xinghuiTec.lowcode;

import com.xinghuiTec.domain.entity.LoginUser;
import com.xinghuiTec.domain.entity.SysUser;
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

class LowcodeSecurityTest {
    private record Operation(String permission, Runnable call) {}

    @AfterEach
    void clear() {
        SecurityContextHolder.clearContext();
    }

    @Test
    void tenantCannotDesignEvenWithAllActionsAndPlatformNeedsExactActions() {
        for (boolean enabled : List.of(true, false)) {
            try (var context = context(enabled)) {
                var controller = context.getBean(LowcodeFormController.class);
                var operations = List.of(
                        new Operation("lowcode:form:list", () -> controller.list(1, 10, null, null, null)),
                        new Operation("lowcode:form:query", () -> controller.get(1)),
                        new Operation("lowcode:form:add", () -> controller.create(null)),
                        new Operation("lowcode:form:edit", () -> controller.update(1, null)),
                        new Operation("lowcode:form:publish", () -> controller.publish(1, new LowcodeService.VersionRequest(0))),
                        new Operation("lowcode:form:remove", () -> controller.remove(1, 0)));
                if (enabled) {
                    authenticate("tenant_a", operations.stream().map(Operation::permission).toList());
                    operations.forEach(op -> assertThrows(AccessDeniedException.class, op.call()::run));
                }
                authenticate(enabled ? "000000" : "tenant_a", List.of());
                operations.forEach(op -> assertThrows(AccessDeniedException.class, op.call()::run));
                verifyNoInteractions(context.getBean(LowcodeService.class));
                for (var operation : operations) {
                    authenticate(enabled ? "000000" : "tenant_a", List.of(operation.permission()));
                    assertDoesNotThrow(operation.call()::run);
                    var other = operations.stream().filter(op -> !op.permission().equals(operation.permission())).findFirst().orElseThrow();
                    assertThrows(AccessDeniedException.class, other.call()::run);
                }
            }
        }
    }

    @Test
    void recordActionsAreExplicitAndPublishedRuntimeDoesNotRequireDesignPrivilege() {
        try (var context = context(true)) {
            var controller = context.getBean(LowcodeRecordController.class);
            var operations = List.of(
                    new Operation("lowcode:record:list", () -> controller.list(1, 1, 10, null, null, null, null)),
                    new Operation("lowcode:record:query", () -> controller.get(1, 1)),
                    new Operation("lowcode:record:add", () -> controller.create(1, null)),
                    new Operation("lowcode:record:edit", () -> controller.update(1, 1, null)),
                    new Operation("lowcode:record:remove", () -> controller.remove(1, 1, 0)));
            authenticate("tenant_a", List.of("lowcode:form:query"));
            operations.forEach(op -> assertThrows(AccessDeniedException.class, op.call()::run));
            assertThrows(AccessDeniedException.class, () -> controller.runtime(1));
            verifyNoInteractions(context.getBean(LowcodeService.class));
            for (var operation : operations) {
                authenticate("tenant_a", List.of(operation.permission()));
                assertDoesNotThrow(operation.call()::run);
                assertDoesNotThrow(() -> controller.runtime(1));
                var other = operations.stream().filter(op -> !op.permission().equals(operation.permission())).findFirst().orElseThrow();
                assertThrows(AccessDeniedException.class, other.call()::run);
            }
        }
    }

    @Test
    void identityUsesAuthenticatedTenantAndNoTenantModeUsesDefaultScope() {
        var enabled = new LowcodeIdentity(true);
        assertThrows(AccessDeniedException.class, enabled::tenant);
        authenticate("tenant_a", List.of());
        assertEquals("tenant_a", enabled.tenant());
        assertEquals("1", enabled.creator());
        assertEquals("000000", new LowcodeIdentity(false).tenant());
        login().setTenantId(null);
        assertThrows(AccessDeniedException.class, enabled::tenant);
    }

    private static LoginUser login() {
        return (LoginUser) SecurityContextHolder.getContext().getAuthentication().getPrincipal();
    }

    private static AnnotationConfigApplicationContext context(boolean enabled) {
        var context = new AnnotationConfigApplicationContext();
        context.register(MethodSecurity.class);
        context.registerBean("platformAccess", PlatformAccessService.class, () -> new PlatformAccessService(enabled));
        context.registerBean(LowcodeService.class, () -> mock(LowcodeService.class));
        context.registerBean(LowcodeSharingService.class, () -> mock(LowcodeSharingService.class));
        context.register(LowcodeFormController.class, LowcodeRecordController.class);
        context.refresh();
        return context;
    }

    private static void authenticate(String tenant, List<String> permissions) {
        var user = new SysUser();
        user.setUserId(1L);
        user.setTenantId(tenant);
        var login = new LoginUser(user, permissions);
        login.setTenantId(tenant);
        SecurityContextHolder.getContext().setAuthentication(
                new UsernamePasswordAuthenticationToken(login, null, login.getAuthorities()));
    }

    @Configuration
    @EnableMethodSecurity
    static class MethodSecurity {}
}
