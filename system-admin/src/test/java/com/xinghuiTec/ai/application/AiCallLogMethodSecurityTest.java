package com.xinghuiTec.ai.application;

import com.xinghuiTec.ai.contract.AiTypes.TrustedActor;
import com.xinghuiTec.controller.AiCallLogController;
import com.xinghuiTec.security.PlatformAccessService;
import org.junit.jupiter.api.*;
import org.springframework.context.annotation.*;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.config.annotation.method.configuration.EnableMethodSecurity;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.core.context.SecurityContextHolder;
import java.util.*;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

class AiCallLogMethodSecurityTest {
    @Configuration @EnableMethodSecurity static class Security{}
    @AfterEach void clear(){SecurityContextHolder.clearContext();}
    @Test void eachEndpointRequiresItsExactExistingActionGrant(){
        try(var context=new AnnotationConfigApplicationContext()){
            context.register(Security.class);context.registerBean(AiCallLogService.class,()->mock(AiCallLogService.class));
            var identity=mock(AiInvocationIdentity.class);when(identity.current()).thenReturn(new TrustedActor("101","tenant-a",Set.of("ai:request:list"),null));
            context.registerBean(AiInvocationIdentity.class,()->identity);context.registerBean(PlatformAccessService.class,()->mock(PlatformAccessService.class));
            context.registerBean(AiCallLogController.class);context.refresh();var api=context.getBean(AiCallLogController.class);
            var query=new AiCallLogController.Query(null,null,null,null,null,null,null,null,null,null,null,null,null,null);
            SecurityContextHolder.getContext().setAuthentication(new UsernamePasswordAuthenticationToken("fixture",null,List.of(new SimpleGrantedAuthority("ai:request:list"))));
            assertDoesNotThrow(()->api.list(query));assertDoesNotThrow(()->api.statistics(query));
            assertThrows(org.springframework.security.access.AccessDeniedException.class,()->api.export(query));
            assertThrows(org.springframework.security.access.AccessDeniedException.class,()->api.detail(UUID.randomUUID().toString(),query));
            SecurityContextHolder.getContext().setAuthentication(new UsernamePasswordAuthenticationToken("fixture",null,List.of(new SimpleGrantedAuthority("ai:request:audit"))));
            assertThrows(org.springframework.security.access.AccessDeniedException.class,()->api.list(query));
            assertThrows(org.springframework.security.access.AccessDeniedException.class,()->api.statistics(query));
        }
    }
}
