package com.xinghuiTec.filter;

import com.xinghuiTec.constants.RedisConstants;
import com.xinghuiTec.domain.entity.LoginUser;
import com.xinghuiTec.domain.entity.SysUser;
import com.xinghuiTec.utils.JwtUtil;
import com.xinghuiTec.utils.RedisCacheUtils;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.test.util.ReflectionTestUtils;

import java.util.List;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

class LoginFilterTest {

    @AfterEach
    void clearContext() {
        SecurityContextHolder.clearContext();
    }

    @Test
    void acceptsOnlyTheCurrentTokenForAnExistingSession() throws Exception {
        String oldToken = JwtUtil.createJWT("42", "tenant-a");
        String currentToken = JwtUtil.createJWT("42", "tenant-a");
        assertNotEquals(oldToken, currentToken);

        SysUser user = new SysUser();
        user.setUserId(42L);
        LoginUser session = new LoginUser(user, List.of());
        session.setToken(currentToken);
        RedisCacheUtils redis = mock(RedisCacheUtils.class);
        when(redis.getCacheObject(RedisConstants.ADMIN_LOGIN_PREFIX + "42")).thenReturn(session);

        LoginFilter filter = new LoginFilter();
        ReflectionTestUtils.setField(filter, "redisCacheUtils", redis);

        MockHttpServletRequest staleRequest = new MockHttpServletRequest("GET", "/user/info");
        staleRequest.addHeader("Authorization", oldToken);
        filter.doFilter(staleRequest, new MockHttpServletResponse(), (request, response) ->
                assertNull(SecurityContextHolder.getContext().getAuthentication()));

        MockHttpServletRequest currentRequest = new MockHttpServletRequest("GET", "/user/info");
        currentRequest.addHeader("Authorization", currentToken);
        filter.doFilter(currentRequest, new MockHttpServletResponse(), (request, response) ->
                assertNotNull(SecurityContextHolder.getContext().getAuthentication()));
    }
}
