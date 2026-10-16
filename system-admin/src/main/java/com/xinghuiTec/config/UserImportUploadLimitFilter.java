package com.xinghuiTec.config;

import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.springframework.core.Ordered;
import org.springframework.core.annotation.Order;
import org.springframework.stereotype.Component;
import org.springframework.web.filter.OncePerRequestFilter;

import java.io.IOException;

/** Rejects oversized or lengthless import bodies before servlet multipart parsing starts. */
@Component
@Order(Ordered.LOWEST_PRECEDENCE - 100)
public class UserImportUploadLimitFilter extends OncePerRequestFilter {
    public static final long MAX_REQUEST_BYTES = 6L * 1024 * 1024;

    @Override
    protected boolean shouldNotFilter(HttpServletRequest request) {
        String path = request.getRequestURI().substring(request.getContextPath().length());
        return !"POST".equalsIgnoreCase(request.getMethod()) || !"/system/user/import".equals(path);
    }

    @Override
    protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response, FilterChain chain)
            throws ServletException, IOException {
        long contentLength = request.getContentLengthLong();
        if (contentLength < 0) {
            response.sendError(HttpServletResponse.SC_LENGTH_REQUIRED, "导入请求必须包含 Content-Length");
            return;
        }
        if (contentLength > MAX_REQUEST_BYTES) {
            response.sendError(HttpServletResponse.SC_REQUEST_ENTITY_TOO_LARGE, "导入请求不能超过 6 MiB");
            return;
        }
        chain.doFilter(request, response);
    }
}
