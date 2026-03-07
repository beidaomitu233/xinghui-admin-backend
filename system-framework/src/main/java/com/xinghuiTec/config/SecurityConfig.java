package com.xinghuiTec.config;

import com.xinghuiTec.constants.HttpConstants;
import com.xinghuiTec.filter.AuthenticationEntryPointImpl;
import com.xinghuiTec.filter.loginFilter;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.security.authentication.AuthenticationManager;
import org.springframework.security.config.annotation.authentication.configuration.AuthenticationConfiguration;
import org.springframework.security.config.annotation.method.configuration.EnableMethodSecurity;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.config.annotation.web.configuration.EnableWebSecurity;
import org.springframework.security.config.http.SessionCreationPolicy;
import org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.security.web.SecurityFilterChain;
import org.springframework.security.web.authentication.UsernamePasswordAuthenticationFilter;

@Configuration
@EnableWebSecurity
@EnableMethodSecurity
public class SecurityConfig {

    @Autowired
    private AuthenticationConfiguration authenticationConfiguration;

    @Autowired
    private loginFilter jwtAuthenticationFilter;

    @Autowired
    private AuthenticationEntryPointImpl authenticationEntryPoint;

    // 更改默认的spring security密码加密方式
    @Bean
    public PasswordEncoder bcPasswordEncoder() {
        return new BCryptPasswordEncoder();
    }

    /**
     * 创建AuthenticationManager实例，用于处理用户认证。
     *
     * @return 创建的AuthenticationManager实例。
     * @throws Exception 如果创建过程中发生错误，则抛出异常。
     */
    @Bean
    public AuthenticationManager authenticationManagerBean() throws Exception {
        return authenticationConfiguration.getAuthenticationManager();
    }

    /**
     * 配置Spring Security的过滤链。 用于替代代替拦截器
     * 使用Spring Security 6的Lambda DSL语法
     *
     * @param http 用于构建安全配置的HttpSecurity对象。
     * @return 返回配置好的SecurityFilterChain对象。
     * @throws Exception 如果配置过程中发生错误，则抛出异常。
     */
    @Bean
    SecurityFilterChain securityFilterChain(HttpSecurity http) throws Exception {
        http
                // 禁用CSRF保护
                .csrf(csrf -> csrf.disable())
                // 设置会话创建策略为无状态
                .sessionManagement(session -> session.sessionCreationPolicy(SessionCreationPolicy.STATELESS))
                // 配置授权规则 指定user/login路径.允许匿名访问(未登录可访问已登陆不能访问). 其他路径需要身份认证
                .authorizeHttpRequests(auth -> auth
                        .requestMatchers(HttpConstants.PATH_LOGIN, HttpConstants.PATH_ROOT, HttpConstants.PATH_CAPTCHA,
                                "/druid/**")
                        .anonymous()
                        .anyRequest().authenticated())
                // 配置异常处理
                .exceptionHandling(exception -> exception
                        // 认证失败处理器（未登录或token无效）
                        .authenticationEntryPoint(authenticationEntryPoint))
                // 添加JWT认证过滤器
                .addFilterBefore(jwtAuthenticationFilter, UsernamePasswordAuthenticationFilter.class)
                // 禁用跨域访问
                .cors(cors -> cors.disable());
        // 构建并返回安全过滤链
        return http.build();
    }
}