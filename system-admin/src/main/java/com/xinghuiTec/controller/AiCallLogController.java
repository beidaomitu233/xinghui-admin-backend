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
import java.util.*;

@RestController
@RequestMapping("/system/ai/call-logs")
public class AiCallLogController {
    private final AiCallLogService logs;
    private final AiInvocationIdentity identity;
    private final PlatformAccessService platform;
    public AiCallLogController(AiCallLogService logs,AiInvocationIdentity identity,PlatformAccessService platform){this.logs=logs;this.identity=identity;this.platform=platform;}
    public record Query(String scope,String from,String to,String requestId,String modelRef,String providerCode,String status,
                        String entryType,String tenantRef,String subjectRef,String appId,String sceneKey,Integer pageNum,Integer pageSize){
        public AiCallLogService.Filters filters(){try{return new AiCallLogService.Filters(scope==null?AiRequestLogService.Scope.OWN:AiRequestLogService.Scope.valueOf(scope),
                blank(from)?null:Instant.parse(from),blank(to)?null:Instant.parse(to),requestId,modelRef,providerCode,
                blank(status)?null:AiRequestStatus.valueOf(status),blank(entryType)?null:AiCallLogService.EntryType.valueOf(entryType),tenantRef,subjectRef,appId,sceneKey);
        }catch(RuntimeException invalid){throw new AiRuntimeException(ErrorCode.INVALID_INPUT);}}
        private static boolean blank(String value){return value==null||value.isBlank();}
    }
    @GetMapping @PreAuthorize("hasAuthority('ai:request:list')")
    public Result<AiCallLogService.Page> list(@ModelAttribute Query query){return Result.ok(logs.page(identity.current(),platform.isPlatformIdentity(),query.filters(),query.pageNum()==null?1:query.pageNum(),query.pageSize()==null?20:query.pageSize()));}
    @GetMapping("/statistics") @PreAuthorize("hasAuthority('ai:request:list')")
    public Result<AiCallLogService.Statistics> statistics(@ModelAttribute Query query){return Result.ok(logs.statistics(identity.current(),platform.isPlatformIdentity(),query.filters()));}
    @GetMapping("/export") @PreAuthorize("hasAuthority('ai:request:export')")
    public Result<List<Map<String,Object>>> export(@ModelAttribute Query query){return Result.ok(logs.export(identity.current(),platform.isPlatformIdentity(),query.filters()));}
    @GetMapping("/{callId}") @PreAuthorize("hasAuthority('ai:request:query')")
    public Result<AiCallLogService.Detail> detail(@PathVariable String callId,@ModelAttribute Query query){return Result.ok(logs.detail(identity.current(),platform.isPlatformIdentity(),query.filters(),callId));}
    @ModelAttribute public void noCache(HttpServletResponse response){response.setHeader("Cache-Control","no-store, private");}
    @ExceptionHandler(AiRuntimeException.class) public ResponseEntity<Result<Void>> safe(AiRuntimeException error){return AiHttpFailures.response(switch(error.code()){
        case FORBIDDEN -> 403;case NOT_FOUND -> 404;case INVALID_INPUT -> 400;case UNAUTHENTICATED -> 401;default -> 503;
    },error.code().name());}
    @ExceptionHandler(AccessDeniedException.class) public ResponseEntity<Result<Void>> denied(){return AiHttpFailures.response(403,"FORBIDDEN");}
    @ExceptionHandler({IllegalArgumentException.class,org.springframework.validation.BindException.class})
    public ResponseEntity<Result<Void>> invalid(){return AiHttpFailures.response(400,"INVALID_INPUT");}
}
