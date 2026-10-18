package com.xinghuiTec.ai.application;

import com.fasterxml.jackson.databind.ObjectMapper;
import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.core.Ordered;
import org.springframework.core.annotation.Order;
import org.springframework.stereotype.Component;
import org.springframework.web.filter.OncePerRequestFilter;

import java.io.IOException;
import java.util.UUID;

/** Before JWT: even rejected identities get a server-generated searchable trace, never echoed inputs. */
@Component
@Order(Ordered.HIGHEST_PRECEDENCE + 21)
public class AiRuntimeBoundaryFilter extends OncePerRequestFilter {
    public static final String BODY = AiRuntimeBoundaryFilter.class.getName() + ".body";
    public static final int MAX_BODY_BYTES = 32_768;
    private static final Logger LOG = LoggerFactory.getLogger(AiRuntimeBoundaryFilter.class);
    private final ObjectMapper mapper;

    public AiRuntimeBoundaryFilter(ObjectMapper mapper) { this.mapper = mapper; }

    private boolean target(String path) {
        return path.startsWith("/system/ai/settings/model-hub/models") || path.startsWith("/system/ai/settings/scene-routes/") || path.startsWith("/ai/applications/") || path.startsWith("/ai/requests/")
                || path.equals("/system/ai/scenes") || path.startsWith("/system/ai/scenes/")
                || path.equals("/ai/scenes") || path.startsWith("/ai/scenes/")
                || path.equals("/ai/conversations") || path.startsWith("/ai/conversations/")
                || (path.startsWith("/system/ai/applications/") && (path.endsWith("/test-requests") || path.endsWith("/preview")));
    }

    @Override protected boolean shouldNotFilter(HttpServletRequest request) {
        String mapped = request.getServletPath() + (request.getPathInfo() == null ? "" : request.getPathInfo());
        String raw = request.getRequestURI().substring(request.getContextPath().length());
        return !target(mapped) && !target(raw);
    }

    @Override protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response, FilterChain chain)
            throws ServletException, IOException {
        response.setHeader("Cache-Control", request.getRequestURI().endsWith("/preview") ? "no-store, private" : "no-store"); response.setHeader("Pragma", "no-cache");
        response.setHeader("X-Content-Type-Options", "nosniff");
        response.setHeader("X-Request-ID", UUID.randomUUID().toString());
        try {
            String path = request.getServletPath();
            if (path.isEmpty()) path = request.getRequestURI().substring(request.getContextPath().length());
            boolean sceneConfig=(path.startsWith("/system/ai/settings/model-hub/models") || path.equals("/system/ai/scenes") || path.startsWith("/system/ai/scenes/") || path.startsWith("/system/ai/settings/scene-routes/"))
                    && ("PUT".equals(request.getMethod()) || ("POST".equals(request.getMethod()) && !path.endsWith("/preview") && !path.endsWith("/test-requests")));
            if (sceneConfig || ("POST".equals(request.getMethod()) && (path.equals("/ai/scenes/sql-assistance") || path.matches("/ai/scenes/sql-assistance/[0-9]+/cancel") || path.equals("/ai/conversations") || path.startsWith("/ai/conversations/") || path.endsWith("/requests") || path.endsWith("/test-requests") || path.endsWith("/preview")))) {
                String type = request.getContentType(), encoding = request.getHeader("Content-Encoding");
                if (type == null || !type.split(";", 2)[0].trim().equalsIgnoreCase("application/json")
                        || (encoding != null && !encoding.equalsIgnoreCase("identity"))) {
                    reject(response, 415, "UNSUPPORTED_CONTENT"); return;
                }
                int limit=sceneConfig?131072:MAX_BODY_BYTES;
                if (request.getContentLengthLong() > limit) { reject(response, 413, "REQUEST_TOO_LARGE"); return; }
                byte[] bytes = request.getInputStream().readNBytes(limit + 1);
                if (bytes.length > limit) { reject(response, 413, "REQUEST_TOO_LARGE"); return; }
                request.setAttribute(BODY, bytes);
            }
            chain.doFilter(request, response);
        } finally {
            // This is HTTP admission metadata, not the asynchronous model terminal outcome.
            LOG.info("AI_RUNTIME_HTTP traceId={} httpStatus={} async={}", response.getHeader("X-Request-ID"),
                    response.getStatus(), request.isAsyncStarted());
        }
    }

    private void reject(HttpServletResponse response, int status, String safeCode) throws IOException {
        response.setStatus(status); response.setContentType("application/json;charset=UTF-8");
        mapper.writeValue(response.getOutputStream(), AiHttpFailures.response(status, safeCode).getBody());
    }
}
