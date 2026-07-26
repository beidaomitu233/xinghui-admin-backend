package com.xinghuiTec.filter;

import cn.hutool.jwt.Claims;
import cn.hutool.jwt.JWT;
import com.xinghuiTec.constants.HttpConstants;
import com.xinghuiTec.constants.JwtConstants;
import com.xinghuiTec.constants.RedisConstants;
import com.xinghuiTec.domain.entity.LoginUser;
import com.xinghuiTec.utils.JwtUtil;
import com.xinghuiTec.utils.RedisCacheUtils;
import jakarta.annotation.Resource;
import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.stereotype.Component;
import org.springframework.util.StringUtils;
import org.springframework.web.filter.OncePerRequestFilter;

import java.io.IOException;
import java.util.Objects;

/**
 * 登录认证过滤器
 * 用于验证用户请求中的JWT令牌，并从Redis中获取用户信息存入Security上下文
 * 
 * @Author 长辉
 * @Date 2025/10/12 00:00
 * @Version 1.0
 */
@Component
// OncePerRequestFilter 每次HTTP请求只会被调用一次
public class LoginFilter extends OncePerRequestFilter {

    /**
     * 自定义Redis缓存工具类，用于从Redis中获取缓存对象
     */
    @Resource
    private RedisCacheUtils redisCacheUtils;

    /**
     * 过滤器核心方法，每次HTTP请求只会被调用一次
     * 主要功能：
     * 1. 从请求头中获取Authorization令牌
     * 2. 如果没有令牌则放行，由Spring Security的其他过滤器处理
     * 3. 如果有令牌则验证JWT并从Redis中获取用户信息
     * 4. 将用户信息存入Security上下文，供后续授权使用
     *
     * @param request     HTTP请求对象
     * @param response    HTTP响应对象
     * @param filterChain 过滤器链
     * @throws ServletException Servlet异常
     * @throws IOException      IO异常
     */
    @Override
    protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response, FilterChain filterChain)
            throws ServletException, IOException {
        // 从请求头中获取Authorization字段
        String authorization = request.getHeader(HttpConstants.HEADER_AUTHORIZATION);

        // 如果Authorization字段为空，则直接放行
        if (!StringUtils.hasText(authorization)) {
            // 没有Authorization放行，此时SecurityContextHolder没有用户信息，会被Spring Security的过滤器拦截
            filterChain.doFilter(request, response);
            return;
        }

        // 验证JWT令牌并获取用户信息
        // 校验失败一律按"未认证"处理：清空上下文后放行，由 AuthenticationEntryPoint 统一返回 401，
        // 而不是抛出 RuntimeException 导致 HTTP 500（过滤器异常不经过 @RestControllerAdvice）
        LoginUser loginUser = null;
        try {
            // 先验证JWT签名和过期时间，防止伪造Token
            if (JwtUtil.verify(authorization)) {
                // 解析JWT令牌获取载荷信息
                JWT jwt = JwtUtil.parseToken(authorization);
                Object userIdObj = jwt.getPayload(JwtConstants.PAYLOAD_USER_ID);
                if (userIdObj != null) {
                    // 从Redis缓存中获取登录用户信息（缓存不存在视为登录凭证已过期）
                    loginUser = redisCacheUtils.getCacheObject(RedisConstants.ADMIN_LOGIN_PREFIX + userIdObj);
                }
                if (loginUser != null) {
                    // 从JWT中获取租户ID并设置到loginUser
                    Object tenantIdObj = jwt.getPayload(JwtConstants.PAYLOAD_TENANT_ID);
                    if (tenantIdObj != null) {
                        loginUser.setTenantId(tenantIdObj.toString());
                    }
                }
            }
        } catch (Exception e) {
            logger.warn("Token 解析失败，按未认证处理: " + e.getMessage());
            loginUser = null;
        }

        if (Objects.isNull(loginUser)) {
            SecurityContextHolder.clearContext();
            filterChain.doFilter(request, response);
            return;
        }

        // 将用户信息存入Security上下文，参数为：用户信息、凭证、权限列表
        UsernamePasswordAuthenticationToken authenticationToken = new UsernamePasswordAuthenticationToken(loginUser,
                null, loginUser.getAuthorities());
        SecurityContextHolder.getContext().setAuthentication(authenticationToken);

        // 验证完成，继续执行过滤器链
        filterChain.doFilter(request, response);
    }
}