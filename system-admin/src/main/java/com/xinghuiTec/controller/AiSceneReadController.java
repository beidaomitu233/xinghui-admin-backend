package com.xinghuiTec.controller;

import com.xinghuiTec.ai.prompt.*;
import com.xinghuiTec.domain.entity.LoginUser;
import com.xinghuiTec.utils.Result;
import jakarta.servlet.http.HttpServletRequest;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.web.bind.annotation.*;

/** Authenticated business transport only. Never annotate with generic @Log. */
@RestController
@RequestMapping("/ai/scenes")
public class AiSceneReadController {
    public record Response(String appKey,String sceneKey,int publishedRevision,String modelId,String prompt) {
        @Override public String toString() { return "AiSceneReadResponse[prompt=REDACTED]"; }
    }
    private final AiSceneReadService reads;
    public AiSceneReadController(AiSceneReadService reads) { this.reads=reads; }

    @PostMapping(value="/{appKey}/{sceneKey}/render",consumes="application/json")
    public Result<Response> read(@PathVariable String appKey,@PathVariable String sceneKey,
                                 @RequestBody byte[] body,HttpServletRequest request) {
        var state=(AiSceneReadBoundaryFilter.State)request.getAttribute(AiSceneReadBoundaryFilter.STATE);
        if(state!=null && reads.registeredApp(appKey)) state.appKey=appKey;
        var authentication=SecurityContextHolder.getContext().getAuthentication();
        if(state!=null && authentication!=null && authentication.isAuthenticated()
                && authentication.getPrincipal() instanceof LoginUser login && login.getUser()!=null) {
            state.userId=login.getUser().getUserId();
            String tenant=login.getTenantId();
            if(tenant!=null && tenant.matches("[A-Za-z0-9._-]{1,64}")) state.tenantId=tenant;
        }
        var result=reads.read(appKey,sceneKey,body);
        if(state!=null) { state.appKey=result.appKey(); state.sceneKey=result.sceneKey();
            state.publishedRevision=result.publishedRevision(); state.outcome="SUCCESS"; }
        return Result.ok(new Response(result.appKey(),result.sceneKey(),result.publishedRevision(),Long.toString(result.modelId()),result.prompt()));
    }

    @ExceptionHandler(AiBusinessRenderException.class)
    public ResponseEntity<Result<Void>> failure(AiBusinessRenderException exception,HttpServletRequest request) {
        var code=exception.code(); int status; int resultCode;
        switch(code) {
            case UNAUTHENTICATED,INVALID_IDENTITY -> { status=401; resultCode=311; }
            case BUSINESS_PERMISSION_REQUIRED,TENANT_MISMATCH,RESOURCE_FORBIDDEN -> { status=403; resultCode=309; }
            case APP_NOT_REGISTERED,RESOURCE_NOT_FOUND -> { status=404; resultCode=204; }
            case CONTEXT_INVALID -> { status=400; resultCode=202; }
            case SCENE_UNAVAILABLE,VARIABLE_NOT_REGISTERED -> { status=409; resultCode=201; }
            case MISSING_VARIABLE,UNDECLARED_VALUE,INVALID_VARIABLE_VALUE,OUTPUT_TOO_LONG -> { status=422; resultCode=202; }
            default -> { status=503; resultCode=203; }
        }
        var state=(AiSceneReadBoundaryFilter.State)request.getAttribute(AiSceneReadBoundaryFilter.STATE);
        if(state!=null) state.outcome=code.name();
        return ResponseEntity.status(status).body(Result.fail(resultCode,"AI_SCENE_READ:"+code.name()));
    }

    @ExceptionHandler(Exception.class)
    public ResponseEntity<Result<Void>> invalid(Exception exception,HttpServletRequest request) {
        boolean unreadable=exception instanceof org.springframework.http.converter.HttpMessageNotReadableException;
        String outcome=unreadable ? "INVALID_REQUEST" : "INTERNAL_FAILURE";
        var state=(AiSceneReadBoundaryFilter.State)request.getAttribute(AiSceneReadBoundaryFilter.STATE);
        if(state!=null) state.outcome=outcome;
        return ResponseEntity.status(unreadable ? 400 : 503).body(Result.fail(unreadable ? 202 : 203,"AI_SCENE_READ:"+outcome));
    }
}
