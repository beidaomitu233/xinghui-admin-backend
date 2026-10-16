package com.xinghuiTec.security;

import com.xinghuiTec.businessconfig.BusinessConfigService;
import com.xinghuiTec.ai.model.*;
import com.xinghuiTec.controller.*;
import com.xinghuiTec.domain.entity.*;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.context.annotation.*;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.config.annotation.method.configuration.EnableMethodSecurity;
import org.springframework.security.core.context.SecurityContextHolder;
import java.util.List;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

class BusinessConfigMethodSecurityTest {
    @Configuration @EnableMethodSecurity static class Security {}
    @AfterEach void clear() { SecurityContextHolder.clearContext(); }
    AnnotationConfigApplicationContext context(boolean tenants) {
        var c=new AnnotationConfigApplicationContext();c.register(Security.class);
        c.registerBean("platformAccess",PlatformAccessService.class,()->new PlatformAccessService(tenants));
        c.registerBean(BusinessConfigService.class,()->mock(BusinessConfigService.class));
        c.registerBean(AiModelCredentialService.class,()->mock(AiModelCredentialService.class));
        c.registerBean(OpenRouterModelTester.class,()->mock(OpenRouterModelTester.class));
        c.registerBean(BusinessConfigController.class);c.registerBean(AiModelTestController.class);c.refresh();return c;
    }
    void authenticate(String tenant,String... permissions) {
        var user=new SysUser();user.setUserId(1L);user.setTenantId(tenant);
        var login=new LoginUser(user,List.of(permissions));login.setTenantId(tenant);
        SecurityContextHolder.getContext().setAuthentication(new UsernamePasswordAuthenticationToken(login,null,login.getAuthorities()));
    }
    @Test void tenantWithMisassignedPermissionsCannotReadWriteOrTest() {
        try(var c=context(true)) {
            authenticate("000001","system:businessConfig:query","system:businessConfig:edit","ai:model:edit","ai:model:test");
            var business=c.getBean(BusinessConfigController.class);var ai=c.getBean(AiModelTestController.class);
            assertThrows(AccessDeniedException.class,business::all);
            assertThrows(AccessDeniedException.class,()->business.save("oss",null));
            assertThrows(AccessDeniedException.class,()->ai.save(1,null));
            assertThrows(AccessDeniedException.class,()->ai.test(1));
            verifyNoInteractions(c.getBean(BusinessConfigService.class),c.getBean(AiModelCredentialService.class),c.getBean(OpenRouterModelTester.class));
        }
    }
    @Test void exactQueryPermissionDoesNotAuthorizeEditingOrModelTesting() {
        try(var c=context(true)) {
            authenticate("000000","system:businessConfig:query");
            var business=c.getBean(BusinessConfigController.class);var ai=c.getBean(AiModelTestController.class);
            assertDoesNotThrow(business::all);
            assertThrows(AccessDeniedException.class,()->business.save("oss",null));
            assertThrows(AccessDeniedException.class,()->ai.test(1));
            verify(c.getBean(BusinessConfigService.class)).all();
        }
    }
    @Test void modelTestPermissionIsIndependentOfCredentialEditing() {
        try(var c=context(true)) {
            authenticate("000000","ai:model:test");
            var ai=c.getBean(AiModelTestController.class);
            assertDoesNotThrow(()->ai.test(1));
            assertThrows(AccessDeniedException.class,()->ai.save(1,null));
            verify(c.getBean(OpenRouterModelTester.class)).test(1);
        }
    }
    @Test void tenantDisabledStillRequiresOperationPermission() {
        try(var c=context(false)) {
            authenticate("000001","system:businessConfig:edit");
            var business=c.getBean(BusinessConfigController.class);
            assertDoesNotThrow(()->business.save("sms",null));
            assertThrows(AccessDeniedException.class,business::all);
        }
    }
}
