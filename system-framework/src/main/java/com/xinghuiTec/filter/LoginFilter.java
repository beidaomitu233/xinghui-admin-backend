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
// OncePerRequestFilter 在每次需要认证的派发中执行一次。
public class LoginFilter extends OncePerRequestFilter {

    /** Stateless security has no saved context on MVC's async completion dispatch (including SSE).
     * Revalidate the original JWT/current session there before AuthorizationFilter runs; never
     * exempt async requests from authorization or copy authentication into a global/thread context. */
    @Override
    protected boolean shouldNotFilterAsyncDispatch() {
        return false;
    }

    /**
     * 自定义Redis缓存工具类，用于从Redis中获取缓存对象
     */
    @Resource
    private RedisCacheUtils redisCacheUtils;

    /**
     * 过滤器核心方法，在REQUEST及ASYNC派发中分别认证一次
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

        // 1) JWT 签名/过期校验与解析：失败一律按"未认证"处理——清空上下文后放行，
        // 由 AuthenticationEntryPoint 统一返回 401，而不是抛出 RuntimeException
        // 导致 HTTP 500（过滤器异常不经过 @RestControllerAdvice）
        Object userIdObj = null;
        Object tenantIdObj = null;
        try {
            if (JwtUtil.verify(authorization)) {
                JWT jwt = JwtUtil.parseToken(authorization);
                userIdObj = jwt.getPayload(JwtConstants.PAYLOAD_USER_ID);
                tenantIdObj = jwt.getPayload(JwtConstants.PAYLOAD_TENANT_ID);
            }
        } catch (Exception e) {
            logger.warn("Token 解析失败，按未认证处理: " + e.getMessage());
            userIdObj = null;
        }

        if (Objects.isNull(userIdObj)) {
            SecurityContextHolder.clearContext();
            filterChain.doFilter(request, response);
            return;
        }

        // 2) 读取 Redis 登录态：基础设施故障必须与"凭证过期"区分开，
        // 返回 503 并记录完整堆栈，避免 Redis 抖动时在线用户被集体"踢下线"
        LoginUser loginUser;
        try {
            loginUser = redisCacheUtils.getCacheObject(RedisConstants.ADMIN_LOGIN_PREFIX + userIdObj);
        } catch (Exception e) {
            logger.error("读取登录态缓存失败（Redis 异常）", e);
            response.setStatus(HttpServletResponse.SC_SERVICE_UNAVAILABLE);
            response.setContentType("application/json;charset=UTF-8");
            response.getWriter().write("{\"code\":503,\"message\":\"服务暂时不可用，请稍后重试\",\"data\":null}");
            return;
        }

        // 缓存不存在视为登录凭证已过期，按未认证处理
        if (Objects.isNull(loginUser) || !authorization.equals(loginUser.getToken())) {
            SecurityContextHolder.clearContext();
            filterChain.doFilter(request, response);
            return;
        }

        // 从JWT中获取租户ID并设置到loginUser
        if (tenantIdObj != null) {
            loginUser.setTenantId(tenantIdObj.toString());
        }

        // 将用户信息存入Security上下文，参数为：用户信息、凭证、权限列表
        UsernamePasswordAuthenticationToken authenticationToken = new UsernamePasswordAuthenticationToken(loginUser,
                null, loginUser.getAuthorities());
        SecurityContextHolder.getContext().setAuthentication(authenticationToken);

        // 验证完成，继续执行过滤器链
        filterChain.doFilter(request, response);
    }
}
