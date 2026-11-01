package com.xinghuiTec.ai.scene;

import com.xinghuiTec.ai.application.AiInvocationIdentity;
import com.xinghuiTec.ai.application.AiRuntimeBodyCodec;
import com.xinghuiTec.ai.application.AiRuntimeBoundaryFilter;
import com.xinghuiTec.ai.contract.AiProviderRuntime;
import com.xinghuiTec.ai.contract.AiTypes.*;
import com.xinghuiTec.utils.Result;
import jakarta.servlet.http.HttpServletRequest;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.*;

import java.util.List;
import java.util.Set;

@RestController
@RequestMapping("/system/ai/scenes")
public class SceneAdminController {
    private final SceneManagementService service;
    private final AiInvocationIdentity identity;
    private final AiRuntimeBodyCodec codec;
    private final ObjectProvider<AiProviderRuntime> providers;
    public SceneAdminController(SceneManagementService service,AiInvocationIdentity identity,AiRuntimeBodyCodec codec,ObjectProvider<AiProviderRuntime> providers){this.service=service;this.identity=identity;this.codec=codec;this.providers=providers;}
    @GetMapping @PreAuthorize("@platformAccess.isPlatformIdentity() and hasAuthority('ai:scene:list')")
    public Result<SceneDtos.Page> page(@RequestParam(defaultValue="1")int pageNum,@RequestParam(defaultValue="20")int pageSize,@RequestParam(required=false)String search,@RequestParam(required=false)String orderBy,@RequestParam(required=false)String order){return Result.ok(service.page(pageNum,pageSize,search,orderBy,order));}
    @GetMapping("/workspace") @PreAuthorize("@platformAccess.isPlatformIdentity() and hasAuthority('ai:scene:list')")
    public Result<SceneDtos.Workspace> workspace(@RequestParam(defaultValue="1")int pageNum,@RequestParam(defaultValue="20")int pageSize,
            @RequestParam(required=false)String search,@RequestParam(required=false)String selectedSceneKey){
        if(selectedSceneKey!=null && !identity.current().authorities().contains("ai:scene:query"))throw new SceneFailure(403,"FORBIDDEN");
        return Result.ok(service.workspace(pageNum,pageSize,search,selectedSceneKey,identity.current()));
    }
    @GetMapping("/{key}") @PreAuthorize("@platformAccess.isPlatformIdentity() and hasAuthority('ai:scene:query')")
    public Result<SceneDtos.Detail> detail(@PathVariable String key){return Result.ok(service.detail(key,identity.current()));}
    @PostMapping @PreAuthorize("@platformAccess.isPlatformIdentity() and hasAuthority('ai:scene:add')")
    public Result<SceneDtos.Detail> create(HttpServletRequest request){return Result.ok(service.create(body(request,SceneDtos.Create.class),identity.current()));}
    @PutMapping("/{key}") @PreAuthorize("@platformAccess.isPlatformIdentity() and hasAuthority('ai:scene:edit')")
    public Result<SceneDtos.SaveResult> save(@PathVariable String key,HttpServletRequest request){return Result.ok(service.save(key,body(request,SceneDtos.Save.class),identity.current()));}
    @PostMapping("/{key}/enabled") @PreAuthorize("@platformAccess.isPlatformIdentity() and hasAuthority('ai:scene:enable')")
    public Result<SceneDtos.SaveResult> enabled(@PathVariable String key,HttpServletRequest request){return Result.ok(service.enabled(key,body(request,SceneDtos.Enabled.class),identity.current()));}
    @GetMapping("/options/models") @PreAuthorize("@platformAccess.isPlatformIdentity() and (hasAuthority('ai:scene:query') or hasAuthority('ai:scene:add') or hasAuthority('ai:scene:edit') or hasAuthority('ai:scene:test'))")
    public Result<List<SceneDtos.ModelOption>> models(@RequestParam(defaultValue="TEXT")SceneDtos.OutputFormat outputFormat){
        var provider=providers.getIfUnique();if(provider==null)throw new SceneFailure(503,"DEPENDENCY_UNAVAILABLE");
        return Result.ok(provider.availableModels(new Requirements(OutputFormat.valueOf(outputFormat.name()),true,false)).stream().map(m->new SceneDtos.ModelOption(
                new SceneDtos.ModelRef(m.model().modelRef(),m.model().configVersion()),m.configId(),m.configName(),m.providerCode(),m.modelCode(),m.capabilities().streaming(),
                m.capabilities().outputFormats().stream().map(f->SceneDtos.OutputFormat.valueOf(f.name())).toList(),m.usableKeyCount())).toList());
    }
    @GetMapping("/options/keys") @PreAuthorize("@platformAccess.isPlatformIdentity() and (hasAuthority('ai:scene:query') or hasAuthority('ai:scene:add') or hasAuthority('ai:scene:edit') or hasAuthority('ai:scene:test'))")
    public Result<List<SceneDtos.KeyOption>> keys(@RequestParam String modelRef,@RequestParam String configVersion){
        SceneStore.decimal(modelRef);SceneStore.decimal(configVersion);var provider=providers.getIfUnique();if(provider==null)throw new SceneFailure(503,"DEPENDENCY_UNAVAILABLE");
        return Result.ok(provider.resolveTargets(new AiProviderRuntime.ModelBinding(new ModelVersionRef(modelRef,configVersion),AiProviderRuntime.KeyMode.AUTO,Set.of(),null,List.of()),Set.of(),SceneManagementService.control(5))
                .stream().map(t->new SceneDtos.KeyOption(new SceneDtos.KeyRef(t.key().keyRef(),t.key().keyVersion()),t.maskedKeyLabel())).toList());
    }
    private <T>T body(HttpServletRequest request,Class<T> type){return codec.readSceneConfiguration((byte[])request.getAttribute(AiRuntimeBoundaryFilter.BODY),type);}
}
