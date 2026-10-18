package com.xinghuiTec.ai.scene;

import com.xinghuiTec.ai.application.*;
import com.xinghuiTec.utils.Result;
import jakarta.servlet.http.HttpServletRequest;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.*;

/** Polling exposes reviewed candidates only; there is no assistance SSE endpoint. */
@RestController
@RequestMapping("/ai/scenes/sql-assistance")
@PreAuthorize("@platformAccess.isPlatformIdentity() and hasAuthority('ai:scene:test') and hasAnyAuthority('ai:scene:add','ai:scene:edit')")
public class SqlAssistanceController {
    private final SqlAssistanceService service;
    private final AiInvocationIdentity identity;
    private final AiRuntimeBodyCodec codec;

    public SqlAssistanceController(SqlAssistanceService service,AiInvocationIdentity identity,AiRuntimeBodyCodec codec) {
        this.service=service;this.identity=identity;this.codec=codec;
    }
    @PostMapping
    public ResponseEntity<Result<SqlAssistanceDtos.Accepted>> start(HttpServletRequest request) {
        return ResponseEntity.accepted().body(Result.ok(service.start(body(request,SqlAssistanceDtos.Start.class),identity.current())));
    }
    @GetMapping("/{requestId}")
    public Result<SqlAssistanceDtos.View> get(@PathVariable String requestId) {
        return Result.ok(service.get(requestId(requestId),identity.current()));
    }
    @PostMapping("/{requestId}/cancel")
    public Result<SqlAssistanceDtos.View> cancel(@PathVariable String requestId,HttpServletRequest request) {
        body(request,SqlAssistanceDtos.Cancel.class);
        return Result.ok(service.cancel(requestId(requestId),identity.current()));
    }
    private long requestId(String id){SceneStore.decimal(id);return Long.parseLong(id);}
    private <T>T body(HttpServletRequest request,Class<T> type){return codec.read((byte[])request.getAttribute(AiRuntimeBoundaryFilter.BODY),type);}
}
