package com.xinghuiTec.controller;

import com.xinghuiTec.ai.config.*;
import com.xinghuiTec.exception.ServiceException;
import com.xinghuiTec.utils.Result;
import com.xinghuiTec.emues.ResultCodeEnum;
import jakarta.servlet.http.HttpServletResponse;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.*;

import static com.xinghuiTec.ai.config.AiConfigurationDtos.*;

/** No generic @Log: draft writes contain keys; bodies/responses never enter operation logging. */
@RestController
@RequestMapping("/system/ai/configurations")
public class AiConfigurationController {
    private final AiConfigurationService configs;
    private final AiConfigurationTests tests;
    private final com.xinghuiTec.ai.application.AiInvocationIdentity identity=new com.xinghuiTec.ai.application.AiInvocationIdentity();
    public AiConfigurationController(AiConfigurationService configs,AiConfigurationTests tests) { this.configs=configs;this.tests=tests; }
    @ModelAttribute public void noCache(HttpServletResponse response) { response.setHeader("Cache-Control","no-store"); }
    @GetMapping
    @PreAuthorize("@platformAccess.isPlatformIdentity() and (hasAuthority('ai:config:list') or hasAuthority('ai:model:list'))")
    public Result<Page> page(@RequestParam(defaultValue="1") int pageNum,@RequestParam(defaultValue="20") int pageSize,
                             @RequestParam(required=false) String name,@RequestParam(required=false) String providerCode,
                             @RequestParam(required=false) String environment,@RequestParam(required=false) String status,
                             @RequestParam(required=false) String orderBy,@RequestParam(required=false) String order) {
        return Result.ok(configs.page(pageNum,pageSize,name,providerCode,environment,status,orderBy,order));
    }
    @GetMapping("/{id}")
    @PreAuthorize("@platformAccess.isPlatformIdentity() and (hasAuthority('ai:config:query') or hasAuthority('ai:model:query'))")
    public Result<View> get(@PathVariable String id) { return Result.ok(configs.get(AiConfigurationService.id(id))); }
    @PostMapping
    @PreAuthorize("@platformAccess.isPlatformIdentity() and (hasAuthority('ai:config:add') or hasAuthority('ai:model:add'))")
    public Result<View> create(@RequestBody Create request) { return Result.ok(configs.create(request)); }
    @PutMapping("/{id}/draft")
    @PreAuthorize("@platformAccess.isPlatformIdentity() and (hasAuthority('ai:config:edit') or hasAuthority('ai:model:edit'))")
    public Result<View> draft(@PathVariable String id,@RequestBody DraftChange request) { return Result.ok(configs.save(AiConfigurationService.id(id),request)); }
    @GetMapping("/{id}/versions")
    @PreAuthorize("@platformAccess.isPlatformIdentity() and (hasAuthority('ai:config:query') or hasAuthority('ai:model:query'))")
    public Result<java.util.List<Version>> versions(@PathVariable String id) { return Result.ok(configs.versions(AiConfigurationService.id(id))); }
    @GetMapping("/{id}/impact")
    @PreAuthorize("@platformAccess.isPlatformIdentity() and (hasAuthority('ai:config:query') or hasAuthority('ai:model:query'))")
    public Result<?> impact(@PathVariable String id) { return Result.ok(configs.impact(AiConfigurationService.id(id))); }
    @PostMapping("/{id}/test-runs")
    @PreAuthorize("@platformAccess.isPlatformIdentity() and (hasAuthority('ai:config:test') or hasAuthority('ai:model:test'))")
    public Result<TestRun> test(@PathVariable String id,@RequestBody TestStart request) { return Result.ok(tests.start(AiConfigurationService.id(id),request,identity.current())); }
    @GetMapping("/{id}/test-runs/{testId}")
    @PreAuthorize("@platformAccess.isPlatformIdentity() and (hasAuthority('ai:config:test') or hasAuthority('ai:model:test'))")
    public Result<TestRun> testResult(@PathVariable String id,@PathVariable String testId) { return Result.ok(tests.get(AiConfigurationService.id(id),AiConfigurationService.id(testId))); }
    @PostMapping("/{id}/test-runs/{testId}/cancel")
    @PreAuthorize("@platformAccess.isPlatformIdentity() and (hasAuthority('ai:config:test') or hasAuthority('ai:model:test'))")
    public Result<TestRun> cancel(@PathVariable String id,@PathVariable String testId) { return Result.ok(tests.cancel(AiConfigurationService.id(id),AiConfigurationService.id(testId))); }
    @PostMapping("/{id}/activate")
    @PreAuthorize("@platformAccess.isPlatformIdentity() and hasAuthority('ai:config:enable')")
    public Result<View> activate(@PathVariable String id,@RequestBody Activate request) { return Result.ok(configs.activate(AiConfigurationService.id(id),request)); }
    @PostMapping("/{id}/disable")
    @PreAuthorize("@platformAccess.isPlatformIdentity() and hasAuthority('ai:config:enable')")
    public Result<View> disable(@PathVariable String id,@RequestBody Revision request) { return Result.ok(configs.disable(AiConfigurationService.id(id),request.revision())); }
    @PostMapping("/{id}/archive")
    @PreAuthorize("@platformAccess.isPlatformIdentity() and hasAuthority('ai:config:archive')")
    public Result<View> archive(@PathVariable String id,@RequestBody Revision request) { return Result.ok(configs.archive(AiConfigurationService.id(id),request.revision())); }
    @ExceptionHandler(AiConfigurationException.class)
    public ResponseEntity<Result<Void>> failure(AiConfigurationException error) {
        return ResponseEntity.status(error.status()).body(Result.fail(businessCode(error.status()).getCode(),"AI_CONFIG:"+classification(error.status())+":"+error.getMessage()));
    }
    @ExceptionHandler(AccessDeniedException.class)
    public ResponseEntity<Result<Void>> forbidden() { return ResponseEntity.status(403).body(Result.fail(ResultCodeEnum.ADMIN_ACCESS_FORBIDDEN.getCode(),"AI_CONFIG:FORBIDDEN")); }
    @ExceptionHandler(ServiceException.class)
    public ResponseEntity<Result<Void>> invalid(ServiceException error) { return ResponseEntity.badRequest().body(Result.fail(ResultCodeEnum.PARAM_ERROR.getCode(),"AI_CONFIG:INVALID_INPUT")); }
    @ExceptionHandler(Exception.class)
    public ResponseEntity<Result<Void>> internal(Exception error) {
        int status=error instanceof org.springframework.web.HttpMediaTypeNotSupportedException?415:
                error instanceof org.springframework.http.converter.HttpMessageNotReadableException||error instanceof IllegalArgumentException
                        ||error instanceof org.springframework.web.bind.MethodArgumentNotValidException
                        ||error instanceof org.springframework.beans.TypeMismatchException
                        ||error instanceof org.springframework.web.bind.ServletRequestBindingException?400:503;
        return ResponseEntity.status(status).body(Result.fail(businessCode(status).getCode(),"AI_CONFIG:"+classification(status)));
    }
    private ResultCodeEnum businessCode(int status) {
        return switch(status) {
            case 400,413,415,422 -> ResultCodeEnum.PARAM_ERROR;
            case 401 -> ResultCodeEnum.ADMIN_ACCOUNT_NOT_LOGIN_ERROR;
            case 403 -> ResultCodeEnum.ADMIN_ACCESS_FORBIDDEN;
            case 404 -> ResultCodeEnum.DATA_ERROR;
            case 409 -> ResultCodeEnum.FAIL;
            default -> ResultCodeEnum.SERVICE_ERROR;
        };
    }
    private String classification(int status) {
        return switch(status) {
            case 400,413,415,422 -> "INVALID_INPUT";
            case 401 -> "UNAUTHENTICATED";
            case 403 -> "FORBIDDEN";
            case 404 -> "NOT_FOUND";
            case 409 -> "VERSION_CONFLICT";
            default -> "DEPENDENCY_UNAVAILABLE";
        };
    }
}
