package com.xinghuiTec.ai.scene;

import com.xinghuiTec.ai.application.*;
import com.xinghuiTec.utils.Result;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.*;

import java.util.List;

@RestController
public class SceneRuntimeController {
    private final SceneRuntimeService service;private final AiInvocationIdentity identity;private final AiRuntimeBodyCodec codec;
    public SceneRuntimeController(SceneRuntimeService service,AiInvocationIdentity identity,AiRuntimeBodyCodec codec){this.service=service;this.identity=identity;this.codec=codec;}
    @GetMapping("/ai/scenes") @PreAuthorize("hasAuthority('ai:scene:invoke')")
    public Result<List<SceneDtos.Entry>> candidates(@RequestParam(required=false)SceneDtos.Kind kind,@RequestParam(required=false)String formId){return Result.ok(service.candidates(kind,formId,identity.current()));}
    @GetMapping("/ai/scenes/record-summary") @PreAuthorize("hasAuthority('ai:scene:invoke')")
    public Result<SceneDtos.Entry> summary(@RequestParam String formId){return Result.ok(service.recordSummary(formId,identity.current()));}
    @PostMapping("/ai/scenes/{key}/requests") @PreAuthorize("hasAuthority('ai:scene:invoke')")
    public ResponseEntity<Result<SceneDtos.Request>> submit(@PathVariable String key,HttpServletRequest request,HttpServletResponse response){var accepted=service.submit(key,body(request,SceneDtos.Submission.class),identity.current());response.setHeader("X-Request-ID",accepted.requestId());return ResponseEntity.accepted().body(Result.ok(accepted));}
    @PostMapping("/system/ai/scenes/{key}/test-requests") @PreAuthorize("@platformAccess.isPlatformIdentity() and hasAuthority('ai:scene:test') and hasAuthority('ai:scene:invoke')")
    public ResponseEntity<Result<SceneDtos.Request>> test(@PathVariable String key,HttpServletRequest request,HttpServletResponse response){var accepted=service.test(key,body(request,SceneDtos.TestInput.class),identity.current());response.setHeader("X-Request-ID",accepted.requestId());return ResponseEntity.accepted().body(Result.ok(accepted));}
    @PostMapping("/system/ai/scenes/{key}/preview") @PreAuthorize("@platformAccess.isPlatformIdentity() and hasAuthority('ai:scene:test') and hasAuthority('ai:scene:invoke')")
    public Result<SceneDtos.Preview> preview(@PathVariable String key,HttpServletRequest request){return Result.ok(service.preview(key,body(request,SceneDtos.PreviewInput.class),identity.current()));}
    @GetMapping("/ai/scenes/requests/{id}") @PreAuthorize("hasAuthority('ai:scene:invoke')")
    public Result<SceneDtos.Request> status(@PathVariable long id){return Result.ok(service.status(positive(id),identity.current()));}
    @PostMapping("/ai/scenes/requests/{id}/cancel") @PreAuthorize("hasAuthority('ai:scene:invoke')")
    public Result<SceneDtos.Request> cancel(@PathVariable long id){return Result.ok(service.cancel(positive(id),identity.current()));}
    private <T>T body(HttpServletRequest request,Class<T>type){return codec.read((byte[])request.getAttribute(AiRuntimeBoundaryFilter.BODY),type);}
    private long positive(long id){SceneFailure.require(id>0,400,"INVALID_INPUT");return id;}
}
