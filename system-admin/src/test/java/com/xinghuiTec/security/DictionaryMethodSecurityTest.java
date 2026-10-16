package com.xinghuiTec.security;

import com.xinghuiTec.controller.DictionaryAdminController;
import com.xinghuiTec.controller.DictionaryReadController;
import com.xinghuiTec.domain.entity.LoginUser;
import com.xinghuiTec.domain.entity.SysUser;
import com.xinghuiTec.service.DictionaryService;
import com.xinghuiTec.service.DictionaryService.TypeCreate;
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

class DictionaryMethodSecurityTest {
    @AfterEach
    void clear() {
        SecurityContextHolder.clearContext();
    }

    @Test
    void tenantEnabledRejectsConfigEvenWithPermissionButAllowsBusinessRead() {
        try (var context = context(true)) {
            var admin = context.getBean(DictionaryAdminController.class);
            var read = context.getBean(DictionaryReadController.class);
            var service = context.getBean(DictionaryService.class);
            authenticate("000001", "000001", "system:dict:list", "system:dict:type:add");
            assertThrows(AccessDeniedException.class, admin::types);
            assertThrows(AccessDeniedException.class, () -> admin.addType(
                    new TypeCreate("sample", "示例", true, null)));
            assertDoesNotThrow(() -> read.options("sample"));
            verify(service).options("sample");
            verify(service, never()).listTypes();
            verify(service, never()).addType(any());

            authenticate("000000", "000000", "system:dict:list");
            assertDoesNotThrow(admin::types);
            assertThrows(AccessDeniedException.class, () -> admin.addType(
                    new TypeCreate("sample", "示例", true, null)));
        }
    }

    @Test
    void tenantDisabledUsesExactOperationPermission() {
        try (var context = context(false)) {
            var admin = context.getBean(DictionaryAdminController.class);
            var service = context.getBean(DictionaryService.class);
            authenticate("000001", "000001", "system:dict:type:add");
            admin.addType(new TypeCreate("sample", "示例", true, null));
            verify(service).addType(any());
            assertThrows(AccessDeniedException.class, admin::types);
        }
    }

    private static AnnotationConfigApplicationContext context(boolean tenantEnabled) {
        var context = new AnnotationConfigApplicationContext();
        context.register(MethodSecurityConfiguration.class);
        context.registerBean("platformAccess", PlatformAccessService.class,
                () -> new PlatformAccessService(tenantEnabled));
        context.registerBean(DictionaryAdminController.class);
        context.registerBean(DictionaryReadController.class);
        context.registerBean(DictionaryService.class, () -> mock(DictionaryService.class));
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
