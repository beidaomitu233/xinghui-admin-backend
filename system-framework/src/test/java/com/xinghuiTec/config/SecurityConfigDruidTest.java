package com.xinghuiTec.config;

import com.xinghuiTec.filter.AuthenticationEntryPointImpl;
import com.xinghuiTec.filter.LoginFilter;
import com.xinghuiTec.utils.RedisCacheUtils;
import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletRequest;
import jakarta.servlet.ServletResponse;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Import;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;
import org.springframework.security.web.FilterChainProxy;
import org.springframework.data.redis.core.RedisTemplate;
import org.springframework.test.context.junit.jupiter.web.SpringJUnitWebConfig;
import org.springframework.web.context.WebApplicationContext;
import org.springframework.web.servlet.config.annotation.EnableWebMvc;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;

@SpringJUnitWebConfig(classes = SecurityConfigDruidTest.TestConfiguration.class)
class SecurityConfigDruidTest {
    @Autowired private WebApplicationContext context;

    @Test
    void druidConsoleIsDeniedEvenWithoutLogin() throws Exception {
        var chain = context.getBean(FilterChainProxy.class);
        var request = new MockHttpServletRequest("GET", "/druid/index.html");
        request.setServletPath("/druid/index.html");
        var response = new MockHttpServletResponse();
        chain.doFilter(request, response, (req, res) -> fail("Druid request reached application"));
        assertEquals(401, response.getStatus());
    }

    @Test
    void captchaRemainsAnonymous() throws Exception {
        var chain = context.getBean(FilterChainProxy.class);
        var request = new MockHttpServletRequest("GET", "/captcha");
        request.setServletPath("/captcha");
        var response = new MockHttpServletResponse();
        chain.doFilter(request, response, (req, res) -> {});
        assertEquals(200, response.getStatus());
    }

    @Configuration
    @EnableWebMvc
    @Import(SecurityConfig.class)
    static class TestConfiguration {
        @Bean
        LoginFilter loginFilter() throws Exception {
            LoginFilter filter = mock(LoginFilter.class);
            doAnswer(invocation -> {
                FilterChain chain = invocation.getArgument(2);
                chain.doFilter(invocation.getArgument(0), invocation.getArgument(1));
                return null;
            }).when(filter).doFilter(any(ServletRequest.class), any(ServletResponse.class), any(FilterChain.class));
            return filter;
        }

        @Bean
        AuthenticationEntryPointImpl authenticationEntryPoint() {
            return new AuthenticationEntryPointImpl();
        }

        @Bean
        RedisCacheUtils redisCacheUtils() {
            return mock(RedisCacheUtils.class);
        }

        @Bean
        RedisTemplate<?, ?> redisTemplate() {
            return mock(RedisTemplate.class);
        }
    }
}
