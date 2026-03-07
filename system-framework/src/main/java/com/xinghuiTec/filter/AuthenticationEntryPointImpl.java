package com.xinghuiTec.filter;

import com.alibaba.fastjson2.JSON;
import com.xinghuiTec.utils.Result;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.springframework.security.core.AuthenticationException;
import org.springframework.security.web.AuthenticationEntryPoint;
import org.springframework.stereotype.Component;

import java.io.IOException;

import static com.xinghuiTec.emues.ResultCodeEnum.*;

/**
 * 认证入口点处理器
 * 当用户未登录或token无效时，返回统一的JSON响应
 * 
 * @author 长辉
 * @since 2025-12-28
 */
@Component
public class AuthenticationEntryPointImpl implements AuthenticationEntryPoint {

    /**
     * 当认证失败时调用此方法
     * 
     * @param request       HTTP请求
     * @param response      HTTP响应
     * @param authException 认证异常
     * @throws IOException      IO异常
     * @throws ServletException Servlet异常
     */
    @Override
    public void commence(HttpServletRequest request,
            HttpServletResponse response,
            AuthenticationException authException) throws IOException, ServletException {

        // 设置响应状态码为401（未授权）
        response.setStatus(HttpServletResponse.SC_UNAUTHORIZED);
        // 设置响应内容类型为JSON，并指定UTF-8编码
        response.setContentType("application/json;charset=UTF-8");

        // 创建统一的错误响应对象
        Result<Void> result = Result.fail(ADMIN_ACCOUNT_NOT_LOGIN_ERROR);

        // 将Result对象转换为JSON并写入响应
        response.getWriter().write(JSON.toJSONString(result));
    }
}
