package com.xinghuiTec.ai.prompt;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.xinghuiTec.utils.Result;
import jakarta.servlet.FilterChain;
import jakarta.servlet.ReadListener;
import jakarta.servlet.ServletException;
import jakarta.servlet.ServletInputStream;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletRequestWrapper;
import jakarta.servlet.http.HttpServletResponse;
import org.springframework.core.Ordered;
import org.springframework.core.annotation.Order;
import org.springframework.stereotype.Component;
import org.springframework.web.filter.OncePerRequestFilter;

import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.util.UUID;

/** Bounded body/no-cache/audit envelope, installed before the existing authentication filter chain. */
@Component
@Order(Ordered.HIGHEST_PRECEDENCE+20)
public class AiSceneReadBoundaryFilter extends OncePerRequestFilter {
    public static final String STATE=AiSceneReadBoundaryFilter.class.getName()+".state";
    public static final int MAX_BODY_BYTES=16_384;
    public static final class State {
        public final String traceId=UUID.randomUUID().toString();
        public Long userId; public String tenantId; public String appKey; public String sceneKey;
        public Integer publishedRevision; public String outcome;
    }
    private final AiSceneReadAudit audit;
    public AiSceneReadBoundaryFilter(AiSceneReadAudit audit) { this.audit=audit; }

    @Override protected boolean shouldNotFilter(HttpServletRequest request) {
        // Containers expose the decoded mapping path here; raw URI alone misses /%61i/...
        String path=request.getServletPath();
        if(request.getPathInfo()!=null) path+=request.getPathInfo();
        return !path.startsWith("/ai/scenes/")
                && !request.getRequestURI().substring(request.getContextPath().length()).startsWith("/ai/scenes/");
    }

    @Override protected void doFilterInternal(HttpServletRequest request,HttpServletResponse response,FilterChain chain)
            throws ServletException,IOException {
        State state=new State(); request.setAttribute(STATE,state);
        response.setHeader("Cache-Control","no-store"); response.setHeader("Pragma","no-cache");
        response.setHeader("X-Content-Type-Options","nosniff"); response.setHeader("X-Request-ID",state.traceId);
        try {
            HttpServletRequest bounded=request;
            if("POST".equals(request.getMethod())) {
                String type=request.getContentType(); String encoding=request.getHeader("Content-Encoding");
                if(type==null || !type.split(";",2)[0].trim().equalsIgnoreCase("application/json")
                        || (encoding!=null && !encoding.equalsIgnoreCase("identity"))) {
                    reject(response,state,415,"UNSUPPORTED_CONTENT"); return;
                }
                if(request.getContentLengthLong()>MAX_BODY_BYTES) { reject(response,state,413,"REQUEST_TOO_LARGE"); return; }
                byte[] bytes=request.getInputStream().readNBytes(MAX_BODY_BYTES+1);
                if(bytes.length>MAX_BODY_BYTES) { reject(response,state,413,"REQUEST_TOO_LARGE"); return; }
                bounded=new HttpServletRequestWrapper(request) {
                    @Override public int getContentLength() { return bytes.length; }
                    @Override public long getContentLengthLong() { return bytes.length; }
                    @Override public ServletInputStream getInputStream() {
                        var input=new ByteArrayInputStream(bytes);
                        return new ServletInputStream() {
                            public int read() { return input.read(); }
                            public boolean isFinished() { return input.available()==0; }
                            public boolean isReady() { return true; }
                            public void setReadListener(ReadListener listener) { throw new UnsupportedOperationException("Synchronous input only"); }
                        };
                    }
                    @Override public java.io.BufferedReader getReader() { return new java.io.BufferedReader(new java.io.InputStreamReader(getInputStream(),java.nio.charset.StandardCharsets.UTF_8)); }
                };
            }
            chain.doFilter(bounded,response);
        } catch(IOException | ServletException failure) {
            state.outcome="INTERNAL_FAILURE";
            if(!response.isCommitted()) {
                response.resetBuffer(); response.setStatus(503); response.setContentType("application/json;charset=UTF-8");
                new ObjectMapper().writeValue(response.getOutputStream(),Result.fail(203,"AI_SCENE_READ:INTERNAL_FAILURE"));
            }
        } finally {
            String outcome=state.outcome==null ? "HTTP_"+response.getStatus() : state.outcome;
            audit.record(new AiSceneReadAudit.Event(state.traceId,state.userId,state.tenantId,state.appKey,state.sceneKey,
                    state.publishedRevision,outcome,response.getStatus()));
        }
    }

    private void reject(HttpServletResponse response,State state,int status,String outcome) throws IOException {
        state.outcome=outcome; response.setStatus(status); response.setContentType("application/json;charset=UTF-8");
        new ObjectMapper().writeValue(response.getOutputStream(),Result.fail(202,"AI_SCENE_READ:"+outcome));
    }
}
