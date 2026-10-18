package com.xinghuiTec.ai.modelhub;

import com.xinghuiTec.ai.application.AiInvocationIdentity;
import com.xinghuiTec.ai.application.AiRuntimeBodyCodec;
import com.xinghuiTec.ai.application.AiRuntimeBoundaryFilter;
import jakarta.servlet.http.HttpServletRequest;
import com.xinghuiTec.utils.Result;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.*;

@RestController
@RequestMapping("/system/ai/settings/model-hub/models")
public class ModelHubController {
    private final ModelHubService models;
    private final ModelHubTestService tests;
    private final AiInvocationIdentity identity;
    private final AiRuntimeBodyCodec codec;
    public ModelHubController(ModelHubService models,ModelHubTestService tests,AiInvocationIdentity identity,AiRuntimeBodyCodec codec){this.models=models;this.tests=tests;this.identity=identity;this.codec=codec;}
    @GetMapping
    @PreAuthorize("@platformAccess.isPlatformIdentity() and hasAuthority('ai:config:query')")
    public Result<ModelHubDtos.Page> page(@RequestParam(defaultValue="1")int pageNum,@RequestParam(defaultValue="100")int pageSize,@RequestParam(required=false)String search){return Result.ok(models.page(pageNum,pageSize,search));}
    @GetMapping("/summary")
    @PreAuthorize("@platformAccess.isPlatformIdentity() and hasAuthority('ai:config:query')")
    public Result<ModelHubDtos.Summary> summary(){return Result.ok(models.summary());}
    @GetMapping("/{id}")
    @PreAuthorize("@platformAccess.isPlatformIdentity() and hasAuthority('ai:config:query')")
    public Result<ModelHubDtos.Model> get(@PathVariable String id){return Result.ok(models.get(id));}
    @PostMapping
    @PreAuthorize("@platformAccess.isPlatformIdentity() and hasAuthority('ai:config:add')")
    public Result<ModelHubDtos.Model> create(HttpServletRequest request){return Result.ok(models.save(null,body(request,ModelHubDtos.Change.class),identity.current()));}
    @PutMapping("/{id}")
    @PreAuthorize("@platformAccess.isPlatformIdentity() and hasAuthority('ai:config:edit')")
    public Result<ModelHubDtos.Model> save(@PathVariable String id,HttpServletRequest request){return Result.ok(models.save(id,body(request,ModelHubDtos.Change.class),identity.current()));}
    @PostMapping("/{id}/enabled")
    @PreAuthorize("@platformAccess.isPlatformIdentity() and hasAuthority('ai:config:enable')")
    public Result<ModelHubDtos.Model> enabled(@PathVariable String id,HttpServletRequest request){return Result.ok(models.enabled(id,body(request,ModelHubDtos.Enabled.class),identity.current()));}
    @DeleteMapping("/{id}")
    @PreAuthorize("@platformAccess.isPlatformIdentity() and hasAuthority('ai:config:edit')")
    public Result<Boolean> delete(@PathVariable String id,@RequestParam int expectedRevision){models.delete(id,expectedRevision);return Result.ok(true);}
    @PostMapping("/{id}/tests")
    @PreAuthorize("@platformAccess.isPlatformIdentity() and hasAuthority('ai:config:test')")
    public ResponseEntity<Result<ModelHubDtos.TestView>> test(@PathVariable String id,HttpServletRequest request){return ResponseEntity.accepted().body(Result.ok(tests.start(id,body(request,ModelHubDtos.Test.class),identity.current())));}
    @GetMapping("/{id}/tests/{requestId}")
    @PreAuthorize("@platformAccess.isPlatformIdentity() and hasAuthority('ai:config:test')")
    public Result<ModelHubDtos.TestView> test(@PathVariable String id,@PathVariable String requestId){return Result.ok(tests.get(id,requestId,identity.current()));}
    private <T>T body(HttpServletRequest request,Class<T> type){return codec.readSceneConfiguration((byte[])request.getAttribute(AiRuntimeBoundaryFilter.BODY),type);}
}
