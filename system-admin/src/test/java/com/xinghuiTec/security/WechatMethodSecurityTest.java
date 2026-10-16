package com.xinghuiTec.security;

import com.xinghuiTec.controller.WechatConfigController;
import com.xinghuiTec.wechat.WechatConfigService;
import com.xinghuiTec.domain.entity.LoginUser;
import com.xinghuiTec.domain.entity.SysUser;
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

class WechatMethodSecurityTest {
    @Test void tenantAndPlatformAndNonTenantBoundariesInvokeActualSecurityProxy() {
        for (boolean tenant : new boolean[]{true,false}) {
            try (var context = new AnnotationConfigApplicationContext()) {
                context.register(MethodSecurity.class);
                context.registerBean("platformAccess",PlatformAccessService.class,() -> new PlatformAccessService(tenant));
                context.registerBean(WechatConfigService.class,() -> mock(WechatConfigService.class));
                context.registerBean(WechatConfigController.class); context.refresh();
                var controller=context.getBean(WechatConfigController.class);
                var service=context.getBean(WechatConfigService.class);
                auth("000001","system:wechat:query","system:wechat:edit");
                if(tenant) {
                    assertThrows(AccessDeniedException.class,controller::get);
                    assertThrows(AccessDeniedException.class,() -> controller.save(null));
                    verifyNoInteractions(service);
                } else { assertDoesNotThrow(controller::get); assertDoesNotThrow(() -> controller.save(null)); }
                auth("000000"); assertThrows(AccessDeniedException.class,controller::get);
                auth("000000","system:wechat:query"); assertDoesNotThrow(controller::get);
                assertThrows(AccessDeniedException.class,() -> controller.save(null));
                auth("000000","system:wechat:edit"); assertDoesNotThrow(() -> controller.save(null));
                assertThrows(AccessDeniedException.class,controller::get);
            } finally { SecurityContextHolder.clearContext(); }
        }
    }
    private void auth(String tenant,String... permissions) {
        var user=new SysUser(); user.setUserId(1L); user.setTenantId(tenant);
        var login=new LoginUser(user,List.of(permissions)); login.setTenantId(tenant);
        SecurityContextHolder.getContext().setAuthentication(new UsernamePasswordAuthenticationToken(login,null,login.getAuthorities()));
    }
    @Configuration @EnableMethodSecurity static class MethodSecurity {}
}
