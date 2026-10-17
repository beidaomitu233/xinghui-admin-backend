package com.xinghuiTec.ai.conversation;

import com.xinghuiTec.ai.application.*;
import com.xinghuiTec.security.PlatformAccessService;
import com.xinghuiTec.utils.Result;
import jakarta.servlet.http.*;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

/**
 * Body reads/writes enforce current owner/audit permissions in the service;
 * never trust body identity fields.
 */
@RestController
@RequestMapping("/ai/conversations")
public class ConversationController {
    private final ConversationService service;
    private final AiInvocationIdentity identity;
    private final PlatformAccessService platform;
    private final AiRuntimeBodyCodec codec;
    public ConversationController(ConversationService service, AiInvocationIdentity identity,
            PlatformAccessService platform, AiRuntimeBodyCodec codec) {
        this.service = service;
        this.identity = identity;
        this.platform = platform;
        this.codec = codec;
    }
    @ModelAttribute
    public void noCache(HttpServletResponse response) {
        response.setHeader("Cache-Control", "no-store, private");
    }
    @GetMapping
    public Result<ConversationDtos.Page> page(@RequestParam(defaultValue = "OWN") String scope,
            @RequestParam(required = false) String sceneKey, @RequestParam(required = false) String requestKind,
            @RequestParam(required = false) String search, @RequestParam(defaultValue = "1") int pageNum,
            @RequestParam(defaultValue = "20") int pageSize) {
        return Result.ok(service.page(identity.current(), platform.isPlatformIdentity(), scope, sceneKey, requestKind,
                search, pageNum, pageSize));
    }
    @GetMapping("/{id}")
    public Result<ConversationDtos.Detail> detail(@PathVariable String id,
            @RequestParam(defaultValue = "OWN") String scope) {
        return Result.ok(service.detail(ConversationStore.positive(id), identity.current(),
                platform.isPlatformIdentity(), scope));
    }
    @PostMapping
    public ResponseEntity<Result<ConversationDtos.Accepted>> start(HttpServletRequest request) {
        return ResponseEntity.accepted().body(Result.ok(service.start(
                codec.read((byte[]) request.getAttribute(AiRuntimeBoundaryFilter.BODY), ConversationDtos.Start.class),
                identity.current(), platform.isPlatformIdentity())));
    }
    @PostMapping("/{id}/messages")
    public ResponseEntity<Result<ConversationDtos.Accepted>> continueConversation(@PathVariable String id,
            HttpServletRequest request) {
        return ResponseEntity.accepted()
                .body(Result
                        .ok(service.continueConversation(ConversationStore.positive(id),
                                codec.read((byte[]) request.getAttribute(AiRuntimeBoundaryFilter.BODY),
                                        ConversationDtos.Continue.class),
                                identity.current(), platform.isPlatformIdentity())));
    }
    @ExceptionHandler(ConversationFailure.class)
    public ResponseEntity<Result<?>> failure(ConversationFailure failure) {
        var base = AiHttpFailures.response(failure.status(), failure.getMessage());
        Result<ConversationDtos.ErrorDetails> result = Result.fail(base.getBody().getCode(), failure.getMessage());
        result.setData(failure.details());
        return ResponseEntity.status(failure.status()).body(result);
    }
    @ExceptionHandler({org.springframework.validation.BindException.class,
            org.springframework.beans.TypeMismatchException.class})
    public ResponseEntity<?> invalid() {
        return AiHttpFailures.response(400, "INVALID_INPUT");
    }
}
