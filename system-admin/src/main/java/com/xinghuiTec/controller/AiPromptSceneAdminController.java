package com.xinghuiTec.controller;

import com.xinghuiTec.ai.prompt.AiPromptSceneService;
import com.xinghuiTec.ai.prompt.AiPromptSceneService.CreateRequest;
import com.xinghuiTec.ai.prompt.AiPromptSceneService.DraftRequest;
import com.xinghuiTec.ai.prompt.AiPromptSceneService.ModelOption;
import com.xinghuiTec.ai.prompt.AiPromptSceneService.Page;
import com.xinghuiTec.ai.prompt.AiPromptSceneService.PreviewRequest;
import com.xinghuiTec.ai.prompt.AiPromptSceneService.PreviewView;
import com.xinghuiTec.ai.prompt.AiPromptSceneService.RevisionRequest;
import com.xinghuiTec.ai.prompt.AiPromptSceneService.StatusRequest;
import com.xinghuiTec.ai.prompt.AiPromptSceneService.View;
import com.xinghuiTec.utils.Result;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.http.converter.HttpMessageNotReadableException;
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

/** Platform-only prompt scene administration. Prompt content is intentionally excluded from generic audit logs. */
@RestController
@RequestMapping("/system/ai/prompts")
public class AiPromptSceneAdminController {
    private final AiPromptSceneService scenes;

    public AiPromptSceneAdminController(AiPromptSceneService scenes) {
        this.scenes = scenes;
    }

    @ExceptionHandler(AccessDeniedException.class)
    public ResponseEntity<Result<Void>> denied() {
        return ResponseEntity.status(HttpStatus.FORBIDDEN).body(Result.fail(ADMIN_ACCESS_FORBIDDEN));
    }

    @ExceptionHandler(HttpMessageNotReadableException.class)
    public ResponseEntity<Result<Void>> invalidBody() {
        return ResponseEntity.badRequest().body(Result.fail("请求数据格式无效"));
    }

    @GetMapping
    @PreAuthorize("@platformAccess.isPlatformIdentity() and hasAuthority('ai:prompt:list')")
    public Result<Page> page(@RequestParam(defaultValue = "1") Integer pageNum,
                             @RequestParam(defaultValue = "20") Integer pageSize,
                             @RequestParam(required = false) String appKey,
                             @RequestParam(required = false) String sceneKey,
                             @RequestParam(required = false) Boolean enabled,
                             @RequestParam(required = false) Boolean published,
                             @RequestParam(required = false) String orderBy,@RequestParam(required = false) String order) {
        return Result.ok(scenes.page(pageNum, pageSize, appKey, sceneKey, enabled, published,orderBy,order));
    }

    @GetMapping("/models")
    @PreAuthorize("@platformAccess.isPlatformIdentity() and (hasAuthority('ai:prompt:add') or hasAuthority('ai:prompt:edit'))")
    public Result<java.util.List<ModelOption>> availableModels() {
        return Result.ok(scenes.availableModels());
    }

    @GetMapping("/{id}")
    @PreAuthorize("@platformAccess.isPlatformIdentity() and hasAuthority('ai:prompt:query')")
    public Result<View> get(@PathVariable long id) {
        return Result.ok(scenes.get(id));
    }

    @PostMapping
    @PreAuthorize("@platformAccess.isPlatformIdentity() and hasAuthority('ai:prompt:add')")
    public Result<Long> create(@RequestBody CreateRequest request) {
        return Result.ok(scenes.create(request));
    }

    @PutMapping("/{id}/draft")
    @PreAuthorize("@platformAccess.isPlatformIdentity() and hasAuthority('ai:prompt:edit')")
    public Result<View> updateDraft(@PathVariable long id, @RequestBody DraftRequest request) {
        return Result.ok(scenes.updateDraft(id, request));
    }

    @PostMapping("/{id}/preview")
    @PreAuthorize("@platformAccess.isPlatformIdentity() and hasAuthority('ai:prompt:query')")
    public Result<PreviewView> preview(@PathVariable long id, @RequestBody PreviewRequest request) {
        return Result.ok(scenes.preview(id, request));
    }

    @PostMapping("/{id}/publish")
    @PreAuthorize("@platformAccess.isPlatformIdentity() and hasAuthority('ai:prompt:publish')")
    public Result<View> publish(@PathVariable long id, @RequestBody RevisionRequest request) {
        return Result.ok(scenes.publish(id, request));
    }

    @PatchMapping("/{id}/enabled")
    @PreAuthorize("@platformAccess.isPlatformIdentity() and hasAuthority('ai:prompt:enable')")
    public Result<View> setEnabled(@PathVariable long id, @RequestBody StatusRequest request) {
        return Result.ok(scenes.setEnabled(id, request));
    }

    @DeleteMapping("/{id}")
    @PreAuthorize("@platformAccess.isPlatformIdentity() and hasAuthority('ai:prompt:remove')")
    public Result<Void> delete(@PathVariable long id, @RequestParam int revision) {
        scenes.delete(id, revision);
        return Result.ok();
    }
}
