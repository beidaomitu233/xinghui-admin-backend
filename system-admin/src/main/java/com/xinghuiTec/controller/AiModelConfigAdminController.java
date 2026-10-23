package com.xinghuiTec.controller;

import com.xinghuiTec.ai.model.AiModelConfigService;
import com.xinghuiTec.ai.model.AiModelConfigService.CreateRequest;
import com.xinghuiTec.ai.model.AiModelConfigService.Page;
import com.xinghuiTec.ai.model.AiModelConfigService.StatusRequest;
import com.xinghuiTec.ai.model.AiModelConfigService.UpdateRequest;
import com.xinghuiTec.ai.model.AiModelConfigService.View;
import com.xinghuiTec.annotation.Log;
import com.xinghuiTec.emues.BusinessType;
import com.xinghuiTec.utils.Result;
import jakarta.validation.Valid;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PatchMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import static com.xinghuiTec.emues.ResultCodeEnum.ADMIN_ACCESS_FORBIDDEN;

/** Platform-only administration for model metadata; this controller never invokes a provider. */
@RestController
@RequestMapping("/system/ai/models")
public class AiModelConfigAdminController {
    private final AiModelConfigService models;

    public AiModelConfigAdminController(AiModelConfigService models) {
        this.models = models;
    }

    @ExceptionHandler(AccessDeniedException.class)
    public ResponseEntity<Result<Void>> denied() {
        return ResponseEntity.status(HttpStatus.FORBIDDEN).body(Result.fail(ADMIN_ACCESS_FORBIDDEN));
    }

    @GetMapping
    @PreAuthorize("@platformAccess.isPlatformIdentity() and hasAuthority('ai:model:list')")
    public Result<Page> page(@RequestParam(defaultValue = "1") Integer pageNum,
                             @RequestParam(defaultValue = "20") Integer pageSize,
                             @RequestParam(required = false) String providerCode,
                             @RequestParam(required = false) String modelCode,
                             @RequestParam(required = false) Boolean enabled,
                             @RequestParam(required = false) String orderBy,
                             @RequestParam(required = false) String order) {
        return Result.ok(models.page(pageNum, pageSize, providerCode, modelCode, enabled, orderBy, order));
    }

    @GetMapping("/{id}")
    @PreAuthorize("@platformAccess.isPlatformIdentity() and hasAuthority('ai:model:query')")
    public Result<View> get(@PathVariable long id) {
        return Result.ok(models.get(id));
    }

    @PostMapping
    @PreAuthorize("@platformAccess.isPlatformIdentity() and hasAuthority('ai:model:add')")
    @Log(title = "AI模型配置", businessType = BusinessType.INSERT)
    public Result<Long> create(@Valid @RequestBody CreateRequest request) {
        return Result.ok(models.create(request));
    }

    @PutMapping("/{id}")
    @PreAuthorize("@platformAccess.isPlatformIdentity() and hasAuthority('ai:model:edit')")
    @Log(title = "AI模型配置", businessType = BusinessType.UPDATE)
    public Result<View> update(@PathVariable long id, @Valid @RequestBody UpdateRequest request) {
        return Result.ok(models.update(id, request));
    }

    @PatchMapping("/{id}/status")
    @PreAuthorize("@platformAccess.isPlatformIdentity() and hasAuthority('ai:model:edit')")
    @Log(title = "AI模型配置状态", businessType = BusinessType.UPDATE)
    public Result<View> setEnabled(@PathVariable long id, @Valid @RequestBody StatusRequest request) {
        return Result.ok(models.setEnabled(id, request));
    }

    @DeleteMapping("/{id}")
    @PreAuthorize("@platformAccess.isPlatformIdentity() and hasAuthority('ai:model:remove')")
    @Log(title = "AI模型配置", businessType = BusinessType.DELETE)
    public Result<Void> delete(@PathVariable long id, @RequestParam int revision) {
        models.delete(id, revision);
        return Result.ok();
    }
}
