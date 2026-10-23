package com.xinghuiTec.wechat;

import com.xinghuiTec.mapper.SysUserMapper;
import com.xinghuiTec.domain.entity.*;
import com.xinghuiTec.utils.RedisCacheUtils;
import org.junit.jupiter.api.Test;
import org.springframework.security.core.userdetails.UserDetailsService;
import java.util.List;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

class WechatAccountServiceTest {
    @Test void reloadRejectsDisabledDeletedMissingAndWrongTenantBeforeSessionCreation() {
        var users=mock(SysUserMapper.class);var details=mock(UserDetailsService.class);var redis=mock(RedisCacheUtils.class);
        var service=new WechatAccountService(users,details,redis);
        var user=new SysUser();user.setUserId(42L);user.setTenantId("fixture");user.setMobile("18800000310");user.setStatus(0);
        when(users.selectById(42L)).thenReturn(user);
        assertThrows(WechatFailure.class,()->service.login(42,"fixture"));
        user.setStatus(1);assertThrows(WechatFailure.class,()->service.login(42,"other-tenant"));
        when(users.selectById(42L)).thenReturn(null);assertThrows(WechatFailure.class,()->service.login(42,"fixture"));
        verifyNoInteractions(details,redis);
    }
    @Test void reloadRejectsMobileReassignedToAnotherAccount() {
        var users=mock(SysUserMapper.class);var details=mock(UserDetailsService.class);var redis=mock(RedisCacheUtils.class);
        var service=new WechatAccountService(users,details,redis);
        var user=new SysUser();user.setUserId(42L);user.setTenantId("fixture");user.setMobile("18800000310");user.setStatus(1);
        when(users.selectById(42L)).thenReturn(user);
        var other=new SysUser();other.setUserId(43L);other.setTenantId("fixture");other.setStatus(1);var login=new LoginUser(other,List.of());login.setTenantId("fixture");
        when(details.loadUserByUsername("18800000310")).thenReturn(login);
        assertThrows(WechatFailure.class,()->service.login(42,"fixture"));verifyNoInteractions(redis);
    }
}
