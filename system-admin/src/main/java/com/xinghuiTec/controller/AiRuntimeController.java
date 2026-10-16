package com.xinghuiTec.controller;

import com.xinghuiTec.ai.application.*;
import com.xinghuiTec.ai.contract.AiRuntimeException;
import com.xinghuiTec.ai.contract.AiTypes.*;
import com.xinghuiTec.utils.Result;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;

import java.util.Map;
import java.util.UUID;

/** Current authenticated principal only; a request ID is never authority to read or cancel another run. */
@RestController
public class AiRuntimeController {
    private final AiRuntimeService runtime;
    private final AiInvocationIdentity identity;
    private final AiRuntimeBodyCodec codec;
    private final AiRequestStreams streams;
    private final AiApplicationPreview previews;

    public AiRuntimeController(AiRuntimeService runtime, AiInvocationIdentity identity, AiRuntimeBodyCodec codec, AiRequestStreams streams, AiApplicationPreview previews) {
        this.runtime = runtime; this.identity = identity; this.codec = codec; this.streams = streams; this.previews = previews;
    }
    public record TestInput(Integer revision, Integer modelIndex, BusinessRef businessRef, Map<String, Object> inputs, String idempotencyKey) {
        @Override public String toString() { return "TestInput[REDACTED]"; }
    }

    @PostMapping("/system/ai/applications/{appId}/preview")
    @PreAuthorize("@platformAccess.isPlatformIdentity() and hasAuthority('ai:application:test')")
    public Result<com.xinghuiTec.ai.contract.AiTemplatePreviewRuntime.PublishedPreview> preview(
            @PathVariable long appId, HttpServletRequest request, HttpServletResponse response) {
        var input = codec.read((byte[]) request.getAttribute(AiRuntimeBoundaryFilter.BODY), AiApplicationPreview.Input.class);
        return Result.ok(previews.preview(positive(appId), input, identity.current(), response.getHeader("X-Request-ID")));
    }

    @PostMapping("/ai/applications/{appId}/requests")
    @PreAuthorize("isAuthenticated()")
    public ResponseEntity<Result<AiRequestStore.Request>> submit(@PathVariable long appId, HttpServletRequest request, HttpServletResponse response) {
        var input = codec.read((byte[]) request.getAttribute(AiRuntimeBoundaryFilter.BODY), AiRuntimeService.Submission.class);
        var accepted = runtime.submit(positive(appId), input, identity.current());
        response.setHeader("X-Request-ID", accepted.requestId());
        return ResponseEntity.accepted().body(Result.ok(accepted));
    }

    @PostMapping("/system/ai/applications/{appId}/test-requests")
    @PreAuthorize("@platformAccess.isPlatformIdentity() and hasAuthority('ai:application:test')")
    public ResponseEntity<Result<AiRequestStore.Request>> test(@PathVariable long appId, HttpServletRequest request, HttpServletResponse response) {
        var input = codec.read((byte[]) request.getAttribute(AiRuntimeBoundaryFilter.BODY), TestInput.class);
        if (input.revision() == null || input.revision() < 0 || input.modelIndex() == null || input.modelIndex() < 0) throw new AiRuntimeException(ErrorCode.INVALID_INPUT);
        var accepted = runtime.test(positive(appId), input.revision(), input.modelIndex(),
                new AiRuntimeService.Submission(input.businessRef(), input.inputs(), input.idempotencyKey()), identity.current());
        response.setHeader("X-Request-ID", accepted.requestId());
        return ResponseEntity.accepted().body(Result.ok(accepted));
    }

    @GetMapping("/ai/applications/record-summary")
    @PreAuthorize("isAuthenticated()")
    public Result<AiRuntimeService.Entry> entry(@RequestParam String formId) { return Result.ok(runtime.recordSummary(formId, identity.current())); }

    @GetMapping("/ai/requests/{id}")
    @PreAuthorize("isAuthenticated()")
    public Result<AiRequestStore.Request> status(@PathVariable long id, HttpServletResponse response) {
        var current = runtime.status(positive(id), identity.current()); response.setHeader("X-Request-ID", current.requestId()); return Result.ok(current);
    }

    @PostMapping("/ai/requests/{id}/cancel")
    @PreAuthorize("isAuthenticated()")
    public Result<AiRequestStore.Request> cancel(@PathVariable long id, HttpServletResponse response) {
        var current = runtime.cancel(positive(id), identity.current()); response.setHeader("X-Request-ID", current.requestId()); return Result.ok(current);
    }

    @GetMapping(value = "/ai/requests/{id}/events", produces = "text/event-stream")
    @PreAuthorize("isAuthenticated()")
    public SseEmitter events(@PathVariable long id, HttpServletResponse response) {
        var emitter = streams.subscribe(positive(id), identity.current());
        response.setHeader("X-Accel-Buffering", "no"); response.setHeader("X-Request-ID", Long.toString(positive(id)));
        return emitter;
    }

    @ModelAttribute public void noCache(HttpServletResponse response) {
        if (response.getHeader("Cache-Control") == null) response.setHeader("Cache-Control", "no-store"); response.setHeader("Pragma", "no-cache");
        response.setHeader("X-Content-Type-Options", "nosniff");
        if (response.getHeader("X-Request-ID") == null) response.setHeader("X-Request-ID", UUID.randomUUID().toString());
    }

    @ExceptionHandler(AiRuntimeService.AdmissionFailure.class)
    public ResponseEntity<Result<Void>> failedAdmission(AiRuntimeService.AdmissionFailure failure, HttpServletResponse response) {
        response.setHeader("X-Request-ID", failure.requestId()); return failure(failure.code());
    }
    @ExceptionHandler(AiRuntimeException.class)
    public ResponseEntity<Result<Void>> safe(AiRuntimeException error) { return failure(error.code()); }
    @ExceptionHandler(AccessDeniedException.class)
    public ResponseEntity<Result<Void>> denied() { return AiHttpFailures.response(403, "FORBIDDEN"); }
    @ExceptionHandler(IllegalArgumentException.class)
    public ResponseEntity<Result<Void>> invalid() { return AiHttpFailures.response(400, "INVALID_INPUT"); }

    private ResponseEntity<Result<Void>> failure(ErrorCode code) {
        int status = switch (code) {
            case UNAUTHENTICATED -> 401;
            case FORBIDDEN, RESOURCE_FORBIDDEN -> 403;
            case NOT_FOUND -> 404;
            case VERSION_CONFLICT, MODEL_UNAVAILABLE, NO_USABLE_KEY, TEMPLATE_UNAVAILABLE, RESOURCE_UNAVAILABLE -> 409;
            case INVALID_INPUT -> 400;
            case MISSING_VARIABLE, CAPABILITY_UNSUPPORTED -> 422;
            case RESOURCE_TIMEOUT, DEADLINE_EXCEEDED -> 504;
            case RATE_LIMITED -> 429;
            default -> 503;
        };
        return AiHttpFailures.response(status, code.name());
    }
    private long positive(long value) { if (value < 1) throw new AiRuntimeException(ErrorCode.INVALID_INPUT); return value; }
}
