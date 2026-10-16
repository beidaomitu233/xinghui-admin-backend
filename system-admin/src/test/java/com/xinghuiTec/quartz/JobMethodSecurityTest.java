package com.xinghuiTec.quartz;

import com.xinghuiTec.domain.entity.LoginUser;
import com.xinghuiTec.domain.entity.SysUser;
import com.xinghuiTec.quartz.controller.SysJobController;
import com.xinghuiTec.quartz.domain.JobChangeRequest;
import com.xinghuiTec.quartz.service.SysJobService;
import com.xinghuiTec.quartz.service.JobEngine;
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

class JobMethodSecurityTest {
    private static final JobChangeRequest REQUEST = new JobChangeRequest(null, "test", "DEFAULT",
            "safe.ping", "{}", "0 0 2 * * ?", "Asia/Shanghai", "3", "1", "1", null);

    @AfterEach
    void clear() {
        SecurityContextHolder.clearContext();
    }

    @Test
    void tenantModeRequiresPlatformIdentityAndSpecificOperationCode() {
        try (var context = context(true)) {
            SysJobController controller = context.getBean(SysJobController.class);
            SysJobService service = context.getBean(SysJobService.class);
            authenticate("000001", "000001", "monitor:job:add");
            assertThrows(AccessDeniedException.class, () -> controller.add(REQUEST));
            authenticate("000000", "000000", "monitor:job:edit");
            assertThrows(AccessDeniedException.class, () -> controller.add(REQUEST));
            verifyNoInteractions(service);
            authenticate("000000", "000000", "monitor:job:add");
            assertDoesNotThrow(() -> controller.add(REQUEST));
            verify(service).addManaged(REQUEST);
        }
    }

    @Test
    void tenantDisabledStillRequiresOperationCode() {
        try (var context = context(false)) {
            SysJobController controller = context.getBean(SysJobController.class);
            authenticate("000001", "000001", "monitor:job:list");
            assertThrows(AccessDeniedException.class, () -> controller.add(REQUEST));
            authenticate("000001", "000001", "monitor:job:add");
            assertDoesNotThrow(() -> controller.add(REQUEST));
        }
    }

    @Test
    void manualRunRequiresItsOwnCodeAndPlatformIdentity() {
        try (var context = context(true)) {
            SysJobController controller = context.getBean(SysJobController.class);
            JobEngine engine = context.getBean(JobEngine.class);
            authenticate("000001", "000001", "monitor:job:run");
            assertThrows(AccessDeniedException.class, () -> controller.run(1L));
            authenticate("000000", "000000", "monitor:job:edit");
            assertThrows(AccessDeniedException.class, () -> controller.run(1L));
            verify(engine, never()).runNow(anyLong());
            authenticate("000000", "000000", "monitor:job:run");
            assertDoesNotThrow(() -> controller.run(1L));
            verify(engine).runNow(1L);
        }
    }

    @Test
    void statusChangeUsesSeparateOperationCode() {
        try (var context = context(true)) {
            SysJobController controller = context.getBean(SysJobController.class);
            SysJobService service = context.getBean(SysJobService.class);
            var request = new SysJobController.StatusChangeRequest("1");
            authenticate("000000", "000000", "monitor:job:edit");
            assertThrows(AccessDeniedException.class, () -> controller.changeStatus(1L, request));
            authenticate("000001", "000001", "monitor:job:changeStatus");
            assertThrows(AccessDeniedException.class, () -> controller.changeStatus(1L, request));
            verify(service, never()).changeStatusManaged(anyLong(), anyString());
            authenticate("000000", "000000", "monitor:job:changeStatus");
            assertDoesNotThrow(() -> controller.changeStatus(1L, request));
            verify(service).changeStatusManaged(1L, "1");
        }
    }

    private static AnnotationConfigApplicationContext context(boolean tenantEnabled) {
        var context = new AnnotationConfigApplicationContext();
        context.register(MethodSecurityConfiguration.class);
        context.registerBean("platformAccess", PlatformAccessService.class,
                () -> new PlatformAccessService(tenantEnabled));
        context.registerBean(SysJobController.class);
        context.registerBean(SysJobService.class, () -> mock(SysJobService.class));
        context.registerBean(JobEngine.class, () -> mock(JobEngine.class));
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
    static class MethodSecurityConfiguration {
    }
}
