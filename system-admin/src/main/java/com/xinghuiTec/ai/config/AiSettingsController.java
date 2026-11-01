package com.xinghuiTec.ai.config;

import com.xinghuiTec.ai.application.AiInvocationIdentity;
import com.xinghuiTec.utils.Result;
import jakarta.servlet.http.HttpServletResponse;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.*;
import java.util.List;

/**
 * No operation-log annotation: incoming password fields must never enter audit
 * argument logging.
 */
@RestController
@RequestMapping("/system/ai/settings")
public class AiSettingsController {
    private final AiSettingsService service;
    private final AiInvocationIdentity identity;

    public AiSettingsController(AiSettingsService service, AiInvocationIdentity identity) {
        this.service = service;
        this.identity = identity;
    }

    @ModelAttribute
    public void noCache(HttpServletResponse response) {
        response.setHeader("Cache-Control", "no-store, private");
    }

    @GetMapping("/providers")
    @PreAuthorize("@platformAccess.isPlatformIdentity() and (hasAnyAuthority('ai:config:list','ai:config:query','ai:config:add','ai:config:edit','ai:config:test','ai:model:list','ai:model:query','ai:model:add','ai:model:edit','ai:model:test'))")
    public Result<List<AiSettingsDtos.Provider>> providers() {
        return Result.ok(service.providers());
    }

    @GetMapping
    @PreAuthorize("@platformAccess.isPlatformIdentity() and hasAnyAuthority('ai:config:list','ai:model:list')")
    public Result<AiSettingsDtos.Page> page(@RequestParam(defaultValue = "1") int pageNum,
            @RequestParam(defaultValue = "20") int pageSize,@RequestParam(required=false) String search,
            @RequestParam(required=false) String orderBy,@RequestParam(required=false) String order) {
        return Result.ok(service.page(pageNum, pageSize,search,orderBy,order));
    }

    @GetMapping("/{id}")
    @PreAuthorize("@platformAccess.isPlatformIdentity() and hasAnyAuthority('ai:config:query','ai:model:query')")
    public Result<AiSettingsDtos.View> get(@PathVariable String id) {
        return Result.ok(service.get(AiConfigurationService.id(id)));
    }

    @PostMapping
    @PreAuthorize("@platformAccess.isPlatformIdentity() and hasAnyAuthority('ai:config:add','ai:model:add')")
    public Result<AiSettingsDtos.View> create(@RequestBody AiSettingsDtos.Change body) {
        return Result.ok(service.create(body, identity.current()));
    }

    @PutMapping("/{id}")
    @PreAuthorize("@platformAccess.isPlatformIdentity() and hasAnyAuthority('ai:config:edit','ai:model:edit')")
    public Result<AiSettingsDtos.View> save(@PathVariable String id, @RequestBody AiSettingsDtos.Change body) {
        return Result.ok(service.save(AiConfigurationService.id(id), body, identity.current()));
    }

    @PostMapping("/{id}/connection-tests")
    @PreAuthorize("@platformAccess.isPlatformIdentity() and hasAnyAuthority('ai:config:test','ai:model:test')")
    public ResponseEntity<Result<AiSettingsDtos.TestRun>> test(@PathVariable String id,
            @RequestBody AiSettingsDtos.Test body) {
        return ResponseEntity.accepted()
                .body(Result.ok(service.startTest(AiConfigurationService.id(id), body, identity.current())));
    }

    @GetMapping("/{id}/connection-tests/{testId}")
    @PreAuthorize("@platformAccess.isPlatformIdentity() and hasAnyAuthority('ai:config:test','ai:model:test')")
    public Result<AiSettingsDtos.TestRun> testResult(@PathVariable String id, @PathVariable String testId) {
        return Result.ok(service.testResult(AiConfigurationService.id(id), AiConfigurationService.id(testId)));
    }
}
