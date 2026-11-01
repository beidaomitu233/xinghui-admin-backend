package com.xinghuiTec.service;

import com.xinghuiTec.config.BusinessConfigSource;
import com.xinghuiTec.config.CaptchaProperties;
import com.xinghuiTec.domain.entity.LoginUser;
import com.xinghuiTec.domain.entity.SysUser;
import com.xinghuiTec.exception.ServiceException;
import com.xinghuiTec.sms.core.RuntimeSmsService;
import com.xinghuiTec.utils.JwtUtil;
import com.xinghuiTec.utils.RedisCacheUtils;
import com.xinghuiTec.utils.TenantHelper;
import org.dromara.sms4j.api.entity.SmsResponse;
import org.junit.jupiter.api.Test;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.script.RedisScript;
import org.springframework.security.core.userdetails.UserDetailsService;
import org.springframework.security.core.userdetails.UsernameNotFoundException;

import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.function.Supplier;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

@SuppressWarnings("unchecked")
class SmsLoginServiceTest {
    final StringRedisTemplate redis=mock(StringRedisTemplate.class);
    final RuntimeSmsService sms=mock(RuntimeSmsService.class);
    final BusinessConfigSource configs=mock(BusinessConfigSource.class);
    final UserDetailsService users=mock(UserDetailsService.class);
    final RedisCacheUtils sessions=mock(RedisCacheUtils.class);
    final CaptchaService captcha=mock(CaptchaService.class);
    final PublishedConfigReader published=mock(PublishedConfigReader.class);
    final CaptchaProperties properties=new CaptchaProperties();
    final SmsLoginService service=new SmsLoginService(redis,sms,configs,users,sessions,captcha,properties,published);
    static final String PHONE="18800000310", ID="12d2d32f-5ddb-4ac4-afb0-5c0e0f107871";
    void enabled() {
        when(configs.current("sms")).thenReturn(Optional.of(new BusinessConfigSource.Snapshot("sms",true,1,Map.of(),Map.of())));
    }
    LoginUser account(int status) {
        var user=new SysUser(); user.setUserId(42L);user.setTenantId("fixture");user.setStatus(status);user.setMobile(PHONE);
        var login=new LoginUser(user,List.of("system:user:query"));login.setTenantId("fixture");return login;
    }
    @Test void disabledConfigurationFailsClosedBeforeRedisOrSender() {
        when(configs.current("sms")).thenReturn(Optional.empty());
        assertFalse(service.available());
        assertThrows(ServiceException.class,()->service.send(PHONE,null,null));
        assertThrows(ServiceException.class,()->service.login(PHONE,ID,"123456"));
        verifyNoInteractions(redis,sms,users,sessions);
    }
    @Test void invalidPhoneAndCaptchaNeverSend() {
        assertThrows(ServiceException.class,()->service.send("bad",null,null));
        enabled(); when(published.isCaptchaEnabled(anyBoolean())).thenReturn(true);
        when(captcha.validateCaptcha("image","wrong")).thenReturn(false);
        assertThrows(ServiceException.class,()->service.send(PHONE,"image","wrong"));
        verify(sms,never()).send(anyString(),any());verifyNoInteractions(redis,users,sessions);
    }
    @Test void phoneCooldownCannotBeBypassedByAnotherChallenge() {
        enabled(); when(redis.execute(any(RedisScript.class),anyList(),any(Object[].class))).thenReturn(0L);
        assertThrows(ServiceException.class,()->service.send(PHONE,null,null));
        verify(sms,never()).send(anyString(),any());verifyNoInteractions(users,sessions);
    }
    @Test void sendStoresOnlyDigestAndActivatesOnlyAfterSupplierSuccess() {
        enabled();when(redis.execute(any(RedisScript.class),anyList(),any(Object[].class))).thenReturn(1L);
        when(redis.execute(any(RedisScript.class),anyList())).thenReturn(1L);
        try(var tenant=mockStatic(TenantHelper.class)) {
            tenant.when(()->TenantHelper.ignore(any(Supplier.class))).thenAnswer(i->((Supplier<?>)i.getArgument(0)).get());
            when(users.loadUserByUsername(PHONE)).thenReturn(account(1));
            var response=new SmsResponse(); response.setSuccess(true);
            when(sms.send(eq(PHONE),any())).thenReturn(response);
            var result=service.send(PHONE,null,null);
            assertEquals(300,result.expiresInSeconds());assertEquals(60,result.retryAfterSeconds());
            assertFalse(result.toString().contains("digest"));
            verify(sms).send(eq(PHONE),argThat(v->v.get("code").matches("[0-9]{6}")));
            verify(redis).execute(any(RedisScript.class),anyList(),eq(PHONE),matches("[a-f0-9]{64}"));
            verify(redis).execute(any(RedisScript.class),eq(List.of("global:{sms_login}:challenge:"+result.requestId())));
            verifyNoInteractions(sessions);
        }
    }
    @Test void missingAccountGetsSameChallengeWithoutSendingOrRegistration() {
        enabled();when(redis.execute(any(RedisScript.class),anyList(),any(Object[].class))).thenReturn(1L);
        try(var tenant=mockStatic(TenantHelper.class)) {
            tenant.when(()->TenantHelper.ignore(any(Supplier.class))).thenAnswer(i->((Supplier<?>)i.getArgument(0)).get());
            when(users.loadUserByUsername(PHONE)).thenThrow(new UsernameNotFoundException("missing"));
            assertNotNull(service.send(PHONE,null,null).requestId());
            verify(sms,never()).send(anyString(),any());verifyNoInteractions(sessions);
        }
    }
    @Test void expiredOrWrongOtpNeverLoadsIdentityOrCreatesSession() {
        enabled();when(redis.execute(any(RedisScript.class),anyList(),any(Object[].class))).thenReturn(0L);
        assertThrows(ServiceException.class,()->service.login(PHONE,ID,"123456"));
        verifyNoInteractions(users,sessions);
    }
    @Test void disabledAccountIsRecheckedAfterConsumingCode() {
        enabled();when(redis.execute(any(RedisScript.class),anyList(),any(Object[].class))).thenReturn(1L);
        try(var tenant=mockStatic(TenantHelper.class)) {
            tenant.when(()->TenantHelper.ignore(any(Supplier.class))).thenAnswer(i->((Supplier<?>)i.getArgument(0)).get());
            when(users.loadUserByUsername(PHONE)).thenReturn(account(0));
            assertThrows(ServiceException.class,()->service.login(PHONE,ID,"123456"));
            verifyNoInteractions(sessions);
        }
    }
    @Test void successfulLoginRetainsCurrentTenantPermissionsAndRejectsReplay() {
        enabled();when(redis.execute(any(RedisScript.class),anyList(),any(Object[].class))).thenReturn(1L,0L);
        try(var tenant=mockStatic(TenantHelper.class);var jwt=mockStatic(JwtUtil.class)) {
            tenant.when(()->TenantHelper.ignore(any(Supplier.class))).thenAnswer(i->((Supplier<?>)i.getArgument(0)).get());
            var account=account(1);when(users.loadUserByUsername(PHONE)).thenReturn(account);
            jwt.when(()->JwtUtil.createJWT("42","fixture")).thenReturn("opaque-session");
            assertEquals("opaque-session",service.login(PHONE,ID,"123456"));
            assertThrows(ServiceException.class,()->service.login(PHONE,ID,"123456"));
            verify(users,times(1)).loadUserByUsername(PHONE);
            verify(sessions,times(1)).setCacheObject(anyString(),same(account),anyInt(),eq(java.util.concurrent.TimeUnit.SECONDS));
            verify(sessions).deleteObject(com.xinghuiTec.constants.RedisConstants.USER_INFO_PREFIX+42);
            verify(sessions).deleteObject(com.xinghuiTec.constants.RedisConstants.USER_ROUTER_PREFIX+42);
        }
    }
}
