package com.xinghuiTec.controller;

import com.xinghuiTec.ai.application.*;
import com.xinghuiTec.ai.contract.AiRuntimeException;
import com.xinghuiTec.ai.contract.AiTypes.ErrorCode;
import com.xinghuiTec.security.PlatformAccessService;
import com.xinghuiTec.utils.Result;
import jakarta.servlet.http.HttpServletResponse;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.*;

import java.time.Instant;
import java.util.List;

/** Read-only metadata actions. AUDIT never grants runtime cancel, provider use or business data access. */
@RestController
@RequestMapping("/system/ai/requests")
public class AiRequestLogController {
    private final AiRequestLogService logs;
    private final AiInvocationIdentity identity;
    private final PlatformAccessService platform;
    public AiRequestLogController(AiRequestLogService logs, AiInvocationIdentity identity, PlatformAccessService platform) {
        this.logs = logs; this.identity = identity; this.platform = platform;
    }
    public record Query(String scope, String appId, String requestId, String businessId, String modelRef, String status,
                        String tenantRef, String subjectRef, String from, String to, Integer pageNum, Integer pageSize) {
        public AiRequestLogService.Filters filters() {
            try {
                return new AiRequestLogService.Filters(scope == null ? AiRequestLogService.Scope.OWN : AiRequestLogService.Scope.valueOf(scope),
                        appId, requestId, businessId, modelRef, blank(status) ? null : AiRequestStatus.valueOf(status), tenantRef, subjectRef,
                        blank(from) ? null : Instant.parse(from), blank(to) ? null : Instant.parse(to));
            } catch (RuntimeException invalid) { throw new AiRuntimeException(ErrorCode.INVALID_INPUT); }
        }
        private static boolean blank(String value) { return value == null || value.isBlank(); }
    }

    @GetMapping
    @PreAuthorize("hasAuthority('ai:request:list')")
    public Result<AiRequestLogService.Page> list(@ModelAttribute Query query) {
        return Result.ok(logs.page(identity.current(), platform.isPlatformIdentity(), query.filters(),
                query.pageNum() == null ? 1 : query.pageNum(), query.pageSize() == null ? 20 : query.pageSize()));
    }
    @GetMapping("/{id}")
    @PreAuthorize("hasAuthority('ai:request:query')")
    public Result<AiRequestLogService.Detail> detail(@PathVariable long id, @ModelAttribute Query query) {
        return Result.ok(logs.detail(identity.current(), platform.isPlatformIdentity(), query.filters(), id));
    }
    @GetMapping("/statistics")
    @PreAuthorize("hasAuthority('ai:request:list')")
    public Result<AiRequestLogService.Statistics> statistics(@ModelAttribute Query query) {
        return Result.ok(logs.statistics(identity.current(), platform.isPlatformIdentity(), query.filters()));
    }
    @GetMapping("/export")
    @PreAuthorize("hasAuthority('ai:request:export')")
    public Result<List<AiRequestLogService.Row>> export(@ModelAttribute Query query) {
        return Result.ok(logs.export(identity.current(), platform.isPlatformIdentity(), query.filters()));
    }
    @ModelAttribute public void noCache(HttpServletResponse response) { response.setHeader("Cache-Control", "no-store, private"); }
    @ExceptionHandler(AiRuntimeException.class)
    public ResponseEntity<Result<Void>> safe(AiRuntimeException error) {
        int status = switch (error.code()) { case FORBIDDEN -> 403; case NOT_FOUND -> 404; case INVALID_INPUT -> 400; default -> 503; };
        return AiHttpFailures.response(status, error.code().name());
    }
    @ExceptionHandler(AccessDeniedException.class)
    public ResponseEntity<Result<Void>> denied() { return AiHttpFailures.response(403, "FORBIDDEN"); }
    @ExceptionHandler({IllegalArgumentException.class, org.springframework.web.method.annotation.MethodArgumentTypeMismatchException.class})
    public ResponseEntity<Result<Void>> invalid() { return AiHttpFailures.response(400, "INVALID_INPUT"); }
}
