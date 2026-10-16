package com.xinghuiTec.content;

import com.xinghuiTec.controller.ContentNewsController;
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
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.core.context.SecurityContextHolder;

import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Method;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

class ContentMethodSecurityTest {
    @Configuration
    @EnableMethodSecurity
    static class Security {}

    @AfterEach
    void clear() {
        SecurityContextHolder.clearContext();
    }

    AnnotationConfigApplicationContext context(boolean tenantEnabled, ContentService service) {
        var context = new AnnotationConfigApplicationContext();
        context.register(Security.class);
        context.registerBean("platformAccess", PlatformAccessService.class, () -> new PlatformAccessService(tenantEnabled));
        context.registerBean(ContentService.class, () -> service);
        context.register(ContentNewsController.class);
        context.refresh();
        return context;
    }

    void authenticate(String tenant, String permission) {
        SysUser user = new SysUser();
        user.setUserId(7L);
        user.setTenantId(tenant);
        var permissions = permission == null ? List.<String>of() : List.of(permission);
        var login = new LoginUser(user, permissions);
        login.setTenantId(tenant);
        SecurityContextHolder.getContext().setAuthentication(UsernamePasswordAuthenticationToken.authenticated(
                login, null, permissions.stream().map(SimpleGrantedAuthority::new).toList()));
    }

    Object[] arguments(Method method) {
        return switch (method.getName()) {
            case "list" -> new Object[]{1, 10, null, null};
            case "get", "delete" -> new Object[]{"2052731472993370115"};
            case "create" -> new Object[]{null};
            case "update" -> new Object[]{"2052731472993370115", null};
            default -> throw new IllegalArgumentException();
        };
    }

    void invoke(Object bean, Method method) throws Throwable {
        try {
            method.invoke(bean, arguments(method));
        } catch (InvocationTargetException wrapped) {
            throw wrapped.getCause();
        }
    }

    String operation(Method method) {
        return switch (method.getName()) {
            case "get" -> "query";
            case "create" -> "add";
            case "update" -> "edit";
            case "delete" -> "remove";
            default -> "list";
        };
    }

    @Test
    void everyReadAndWriteRequiresItsOwnPermissionAndPlatformIdentity() {
        ContentService service = mock(ContentService.class);
        try (var context = context(true, service)) {
            Class<?>[] types = {ContentNewsController.class};
            String[] modules = {"news"};
            for (int index = 0; index < types.length; index++) {
                Object controller = context.getBean(types[index]);
                for (Method method : types[index].getDeclaredMethods()) {
                    String permission = "system:" + modules[index] + ":" + operation(method);
                    clearInvocations(service);
                    authenticate("000000", null);
                    assertThrows(AccessDeniedException.class, () -> invoke(controller, method));
                    authenticate("000001", permission);
                    assertThrows(AccessDeniedException.class, () -> invoke(controller, method));
                    verifyNoInteractions(service);
                    authenticate("000000", permission);
                    assertDoesNotThrow(() -> invoke(controller, method));
                }
            }
        }
    }

    @Test
    void tenantDisabledModeUsesExistingAuthorizedManagementRule() {
        ContentService service = mock(ContentService.class);
        try (var context = context(false, service)) {
            Class<?>[] types = {ContentNewsController.class};
            String[] modules = {"news"};
            for (int index = 0; index < types.length; index++) {
                Object controller = context.getBean(types[index]);
                for (Method method : types[index].getDeclaredMethods()) {
                    authenticate("000001", "system:" + modules[index] + ":" + operation(method));
                    assertDoesNotThrow(() -> invoke(controller, method));
                }
            }
        }
    }
}
