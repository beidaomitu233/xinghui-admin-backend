package com.xinghuiTec.security;

import com.xinghuiTec.ai.config.*;
import com.xinghuiTec.controller.AiConfigurationController;
import com.xinghuiTec.domain.entity.LoginUser;
import com.xinghuiTec.domain.entity.SysUser;
import org.junit.jupiter.api.*;
import org.springframework.context.annotation.*;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.config.annotation.method.configuration.EnableMethodSecurity;
import org.springframework.security.core.context.SecurityContextHolder;
import java.util.List;
import static com.xinghuiTec.ai.config.AiConfigurationDtos.*;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

class AiConfigurationMethodSecurityTest {
    @AfterEach void clear() { SecurityContextHolder.clearContext(); }
    AnnotationConfigApplicationContext context() {
        var context=new AnnotationConfigApplicationContext(); context.register(Security.class);
        context.registerBean("platformAccess",PlatformAccessService.class,()->new PlatformAccessService(true));
        context.registerBean(AiConfigurationService.class,()->mock(AiConfigurationService.class));
        context.registerBean(AiConfigurationTests.class,()->mock(AiConfigurationTests.class));
        context.registerBean(AiConfigurationController.class); context.refresh(); return context;
    }
    void actor(String tenant,String... permissions) {
        var user=new SysUser(); user.setUserId(1L); user.setTenantId(tenant);
        var login=new LoginUser(user,List.of(permissions)); login.setTenantId(tenant);
        SecurityContextHolder.getContext().setAuthentication(new UsernamePasswordAuthenticationToken(login,null,login.getAuthorities()));
    }
    @Test void tenantCannotUseMisassignedConfigurationPermissions() {
        try(var c=context()) {
            actor("000001","ai:config:list","ai:config:query","ai:config:add","ai:config:edit","ai:config:test","ai:config:enable","ai:config:archive");
            var api=c.getBean(AiConfigurationController.class);
            assertThrows(AccessDeniedException.class,()->api.page(1,10,null,null,null,null));
            assertThrows(AccessDeniedException.class,()->api.get("1"));
            assertThrows(AccessDeniedException.class,()->api.create(new Create(null,"1")));
            assertThrows(AccessDeniedException.class,()->api.draft("1",null));
            assertThrows(AccessDeniedException.class,()->api.test("1",new TestStart(0,"1",true)));
            assertThrows(AccessDeniedException.class,()->api.testResult("1","1"));
            assertThrows(AccessDeniedException.class,()->api.cancel("1","1"));
            assertThrows(AccessDeniedException.class,()->api.activate("1",new Activate(0,"1",true)));
            assertThrows(AccessDeniedException.class,()->api.disable("1",new Revision(0)));
            assertThrows(AccessDeniedException.class,()->api.archive("1",new Revision(0)));
            verifyNoInteractions(c.getBean(AiConfigurationService.class),c.getBean(AiConfigurationTests.class));
        }
    }
    @Test void currentPlatformIdentityNeedsExactActionAndLegacyReadPermissionRemainsCompatible() {
        try(var c=context()) {
            var api=c.getBean(AiConfigurationController.class);
            assertThrows(AccessDeniedException.class,()->api.get("1"));
            actor("000000","ai:model:list");
            assertDoesNotThrow(()->api.page(1,10,null,null,null,null));
            assertThrows(AccessDeniedException.class,()->api.test("1",new TestStart(0,"1",true)));
            actor("000000","ai:config:test");
            assertDoesNotThrow(()->api.testResult("1","1"));
            assertThrows(AccessDeniedException.class,()->api.activate("1",new Activate(0,"1",true)));
        }
    }
    @Configuration @EnableMethodSecurity static class Security {}
}
