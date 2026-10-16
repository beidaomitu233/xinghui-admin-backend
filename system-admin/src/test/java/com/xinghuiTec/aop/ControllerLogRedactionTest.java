package com.xinghuiTec.aop;

import com.xinghuiTec.controller.SysUserController;
import com.xinghuiTec.domain.dto.LoginDTO;
import com.xinghuiTec.domain.dto.SysPasswordUpdateDTO;
import com.xinghuiTec.domain.dto.SysUserAddDTO;
import com.xinghuiTec.domain.entity.SysOperLog;
import com.xinghuiTec.service.SysOperLogService;
import com.xinghuiTec.utils.Result;
import org.aspectj.lang.ProceedingJoinPoint;
import org.aspectj.lang.Signature;
import org.aspectj.lang.reflect.MethodSignature;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.springframework.boot.test.system.CapturedOutput;
import org.springframework.boot.test.system.OutputCaptureExtension;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.test.util.ReflectionTestUtils;
import org.springframework.web.context.request.RequestContextHolder;
import org.springframework.web.context.request.ServletRequestAttributes;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

@ExtendWith(OutputCaptureExtension.class)
class ControllerLogRedactionTest {

    @AfterEach
    void clearRequest() {
        RequestContextHolder.resetRequestAttributes();
    }

    @Test
    void consoleSummaryNeverSerializesLoginOrPasswordChangeSecrets(CapturedOutput output) throws Throwable {
        RequestContextHolder.setRequestAttributes(new ServletRequestAttributes(
                new MockHttpServletRequest("POST", "/user/login")));
        LoginDTO login = new LoginDTO();
        login.setPhone("13900000001");
        login.setPassword("login-password-secret");
        login.setCode("captcha-secret");
        SysPasswordUpdateDTO passwordUpdate = new SysPasswordUpdateDTO();
        passwordUpdate.setOldPassword("old-password-secret");
        passwordUpdate.setNewPassword("new-password-secret");

        ProceedingJoinPoint point = mock(ProceedingJoinPoint.class);
        Signature signature = mock(Signature.class);
        when(signature.toShortString()).thenReturn("LoginController.login(..)");
        when(point.getSignature()).thenReturn(signature);
        when(point.proceed()).thenReturn(Result.ok("jwt-response-secret"), Result.ok());
        when(point.getArgs()).thenReturn(new Object[]{login}, new Object[]{passwordUpdate});

        LogControllerParam aspect = new LogControllerParam();
        aspect.doAround(point);
        aspect.doAround(point);

        String logged = output.getAll();
        assertTrue(logged.contains("LoginController.login(..)"));
        assertTrue(logged.contains("code=200"));
        assertTrue(logged.contains("durationMs="));
        for (String secret : new String[]{"login-password-secret", "captcha-secret", "jwt-response-secret",
                "old-password-secret", "new-password-secret"}) {
            assertFalse(logged.contains(secret), secret + " leaked to console");
        }

        when(point.proceed()).thenThrow(new IllegalStateException("password-in-exception-secret"));
        assertThrows(IllegalStateException.class, () -> aspect.doAround(point));
        assertFalse(output.getAll().contains("password-in-exception-secret"));
    }

    @Test
    void persistedOperationLogOmitsUserPasswordAndResponseToken(CapturedOutput output) throws Throwable {
        RequestContextHolder.setRequestAttributes(new ServletRequestAttributes(
                new MockHttpServletRequest("POST", "/system/user/add")));
        SysUserAddDTO user = new SysUserAddDTO();
        user.setPassword("user-create-password-secret");
        ProceedingJoinPoint point = mock(ProceedingJoinPoint.class);
        MethodSignature signature = mock(MethodSignature.class);
        when(signature.getMethod()).thenReturn(SysUserController.class.getMethod("addUser", SysUserAddDTO.class));
        when(signature.getName()).thenReturn("addUser");
        when(point.getSignature()).thenReturn(signature);
        when(point.getTarget()).thenReturn(new SysUserController());
        when(point.getArgs()).thenReturn(new Object[]{user});
        when(point.proceed()).thenReturn(Result.ok("jwt-response-secret"));

        SysOperLogService service = mock(SysOperLogService.class);
        LogAspect aspect = new LogAspect();
        ReflectionTestUtils.setField(aspect, "sysOperLogService", service);
        aspect.doAround(point);

        org.mockito.ArgumentCaptor<SysOperLog> captured = org.mockito.ArgumentCaptor.forClass(SysOperLog.class);
        verify(service).saveOperLog(captured.capture());
        SysOperLog saved = captured.getValue();
        assertEquals("[omitted]", saved.getOperParam());
        assertEquals("code=200", saved.getJsonResult());
        assertEquals("[omitted]", saved.getOperUrl());
        assertFalse(saved.toString().contains("user-create-password-secret"));
        assertFalse(saved.toString().contains("jwt-response-secret"));

        when(point.proceed()).thenThrow(new IllegalStateException("token-in-exception-secret"));
        assertThrows(IllegalStateException.class, () -> aspect.doAround(point));
        verify(service, times(2)).saveOperLog(captured.capture());
        SysOperLog failed = captured.getAllValues().get(2);
        assertEquals("exception=IllegalStateException", failed.getErrorMsg());
        assertFalse(failed.toString().contains("token-in-exception-secret"));
        assertFalse(output.getAll().contains("token-in-exception-secret"));
    }
}
