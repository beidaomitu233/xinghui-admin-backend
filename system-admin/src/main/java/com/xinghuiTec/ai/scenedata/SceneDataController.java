package com.xinghuiTec.ai.scenedata;

import com.xinghuiTec.ai.application.AiHttpFailures;
import com.xinghuiTec.ai.application.AiInvocationIdentity;
import com.xinghuiTec.ai.contract.*;
import com.xinghuiTec.ai.contract.AiSceneDataRuntime.*;
import com.xinghuiTec.ai.contract.AiTypes.*;
import com.xinghuiTec.utils.Result;
import jakarta.servlet.http.HttpServletResponse;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.*;
import java.time.Instant;

/** Metadata and sample identities only; never accepts an actor, raw business JSON or legacy app ID. */
@RestController
@RequestMapping("/system/ai/scene-data")
@PreAuthorize("@platformAccess.isPlatformIdentity() and hasAnyAuthority('ai:scene:query','ai:scene:add','ai:scene:edit','ai:scene:test')")
public class SceneDataController {
    private final AiSceneDataRuntime data;
    private final AiInvocationIdentity identity;
    public SceneDataController(AiSceneDataRuntime data,AiInvocationIdentity identity) {this.data=data;this.identity=identity;}

    @GetMapping("/catalog") public Result<Catalog> catalog() {return Result.ok(data.catalog(identity.current(),control()));}
    @GetMapping("/forms") public Result<Page<FormOption>> forms(@RequestParam(required=false) String keyword,
            @RequestParam(defaultValue="1") int pageNum,@RequestParam(defaultValue="20") int pageSize) {
        return Result.ok(data.forms(new OptionQuery(keyword,pageNum,pageSize),identity.current(),control()));
    }
    @GetMapping("/fields") public Result<FieldCatalog> fields(@RequestParam String objectKey,@RequestParam String formId) {
        return Result.ok(data.fields(objectKey,formId,identity.current(),control()));
    }
    @GetMapping("/samples") public Result<Page<SampleOption>> samples(@RequestParam String objectKey,@RequestParam String formId,
            @RequestParam(required=false) String keyword,@RequestParam(defaultValue="1") int pageNum,@RequestParam(defaultValue="20") int pageSize) {
        return Result.ok(data.samples(new SampleQuery(objectKey,formId,keyword,pageNum,pageSize),identity.current(),control()));
    }
    @GetMapping("/database/tables") public Result<Page<DatabaseTable>> tables(@RequestParam(name="keyword",required=false)String keyword,@RequestParam(name="pageNum",defaultValue="1")int pageNum,@RequestParam(name="pageSize",defaultValue="20")int pageSize){return Result.ok(data.tables(new OptionQuery(keyword,pageNum,pageSize),identity.current(),control()));}
    @GetMapping("/database/fields") public Result<DatabaseFields> databaseFields(@RequestParam(name="sourceKey") String sourceKey){return Result.ok(data.databaseFields(sourceKey,identity.current(),control()));}
    @GetMapping("/database/relations") public Result<java.util.List<DatabaseRelation>> relations(@RequestParam(name="sourceKey") String sourceKey){return Result.ok(data.relations(sourceKey,identity.current(),control()));}
    @PostMapping("/database/records") public Result<DatabaseRecords> records(jakarta.servlet.http.HttpServletRequest request){return Result.ok(data.records(body(request,DatabaseRecordQuery.class),identity.current(),control()));}
    @PostMapping("/bindings/preview") public Result<BindingPreview> previewBindings(jakarta.servlet.http.HttpServletRequest request){return Result.ok(data.previewBindings(body(request,BindingPreviewRequest.class),identity.current(),control()));}
    private <T>T body(jakarta.servlet.http.HttpServletRequest request,Class<T> type){
        if(request.getContentType()==null||!request.getContentType().split(";",2)[0].trim().equalsIgnoreCase("application/json")||(request.getHeader("Content-Encoding")!=null&&!request.getHeader("Content-Encoding").equalsIgnoreCase("identity")))throw new BodyFailure(415);
        try{byte[] bytes=request.getInputStream().readNBytes(131073);if(bytes.length>131072)throw new BodyFailure(413);
            return new com.fasterxml.jackson.databind.ObjectMapper().enable(com.fasterxml.jackson.databind.DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES).enable(com.fasterxml.jackson.databind.DeserializationFeature.FAIL_ON_TRAILING_TOKENS).readValue(bytes,type);
        }catch(java.io.IOException invalid){throw new IllegalArgumentException("Invalid body");}
    }
    private static final class BodyFailure extends RuntimeException{final int status;BodyFailure(int status){super("INVALID_CONTENT");this.status=status;}}
    @ExceptionHandler(BodyFailure.class) public ResponseEntity<Result<Void>> content(BodyFailure failure){return AiHttpFailures.response(failure.status,"INVALID_CONTENT");}
    @ModelAttribute public void noCache(HttpServletResponse response) {response.setHeader("Cache-Control","no-store, private");response.setHeader("Pragma","no-cache");response.setHeader("X-Content-Type-Options","nosniff");}
    @ExceptionHandler(AiRuntimeException.class) public ResponseEntity<Result<Void>> safe(AiRuntimeException e) {
        int status=switch(e.code()) {case UNAUTHENTICATED->401;case FORBIDDEN,RESOURCE_FORBIDDEN->403;case NOT_FOUND->404;case INVALID_INPUT->400;case RESOURCE_UNAVAILABLE->409;case RESOURCE_TIMEOUT,DEADLINE_EXCEEDED->504;default->503;};
        return AiHttpFailures.response(status,e.code().name());
    }
    @ExceptionHandler(AccessDeniedException.class) public ResponseEntity<Result<Void>> denied() {return AiHttpFailures.response(403,"FORBIDDEN");}
    @ExceptionHandler({IllegalArgumentException.class,org.springframework.web.bind.ServletRequestBindingException.class,org.springframework.web.method.annotation.MethodArgumentTypeMismatchException.class})
    public ResponseEntity<Result<Void>> invalid() {return AiHttpFailures.response(400,"INVALID_INPUT");}
    @ExceptionHandler(Exception.class) public ResponseEntity<Result<Void>> unavailable() {return AiHttpFailures.response(503,"DEPENDENCY_UNAVAILABLE");}
    private InvocationControl control() {var deadline=Instant.now().plusSeconds(10);return new InvocationControl(){public Instant deadline(){return deadline;}public boolean cancelled(){return Thread.currentThread().isInterrupted();}};}
}
