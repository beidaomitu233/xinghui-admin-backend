package com.xinghuiTec.security;

import com.xinghuiTec.config.CaptchaProperties;
import com.xinghuiTec.constants.RedisConstants;
import com.xinghuiTec.domain.dto.LoginDTO;
import com.xinghuiTec.domain.entity.*;
import com.xinghuiTec.service.PublishedConfigReader;
import com.xinghuiTec.service.impl.LoginServiceImpl;
import com.xinghuiTec.utils.*;
import org.junit.jupiter.api.Test;
import org.springframework.security.authentication.*;
import org.springframework.test.util.ReflectionTestUtils;
import java.util.*;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

class LoginNavigationCacheTest {
    LoginServiceImpl service(AuthenticationManager authentication,RedisCacheUtils redis){
        var service=new LoginServiceImpl();
        ReflectionTestUtils.setField(service,"authenticationManager",authentication);
        ReflectionTestUtils.setField(service,"redisCacheUtils",redis);
        ReflectionTestUtils.setField(service,"captchaProperties",new CaptchaProperties());
        ReflectionTestUtils.setField(service,"publishedConfigReader",mock(PublishedConfigReader.class));
        return service;
    }
    LoginDTO input(){var input=new LoginDTO();input.setPhone("18800000310");input.setPassword("synthetic-password");return input;}
    @Test void successfulPasswordLoginRemovesOnlyItsOldPermissionAndMenuSnapshots(){
        var authentication=mock(AuthenticationManager.class);var redis=mock(RedisCacheUtils.class);
        var user=new SysUser();user.setUserId(42L);user.setTenantId("fixture");user.setStatus(1);
        var principal=new LoginUser(user,List.of("ai:agent:invoke"));principal.setTenantId("fixture");
        when(authentication.authenticate(any())).thenReturn(new UsernamePasswordAuthenticationToken(principal,null,principal.getAuthorities()));
        try(var jwt=mockStatic(JwtUtil.class)){
            jwt.when(()->JwtUtil.createJWT("42","fixture")).thenReturn("synthetic-session");
            assertEquals("synthetic-session",service(authentication,redis).login(input()));
        }
        verify(redis).deleteObject(RedisConstants.USER_INFO_PREFIX+42);verify(redis).deleteObject(RedisConstants.USER_ROUTER_PREFIX+42);
        verify(redis,never()).deleteObject(RedisConstants.USER_INFO_PREFIX+43);
        verify(redis,never()).deleteObject(RedisConstants.USER_ROUTER_PREFIX+43);
    }
    @Test void failedAuthenticationCannotInvalidateAnyoneElsesNavigation(){
        var authentication=mock(AuthenticationManager.class);var redis=mock(RedisCacheUtils.class);
        when(authentication.authenticate(any())).thenThrow(new BadCredentialsException("controlled failure"));
        assertThrows(BadCredentialsException.class,()->service(authentication,redis).login(input()));verifyNoInteractions(redis);
    }
}
