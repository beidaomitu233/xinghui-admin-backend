package com.xinghuiTec.controller;

import com.xinghuiTec.ai.model.AiModelCredentialService;
import com.xinghuiTec.ai.model.AiModelConfigService;
import com.xinghuiTec.ai.model.OpenRouterModelTester;
import com.xinghuiTec.annotation.Log;
import com.xinghuiTec.emues.BusinessType;
import com.xinghuiTec.utils.Result;
import jakarta.validation.Valid;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

@RestController
@RequestMapping("/system/ai/models")
public class AiModelTestController {
    private final AiModelCredentialService credentials;
    private final OpenRouterModelTester tester;
    public AiModelTestController(AiModelCredentialService credentials,OpenRouterModelTester tester) {
        this.credentials=credentials;this.tester=tester;
    }
    @PutMapping("/{id}/credential")
    @PreAuthorize("@platformAccess.isPlatformIdentity() and hasAuthority('ai:model:edit')")
    @Log(title="AI模型凭据",businessType=BusinessType.UPDATE)
    public Result<AiModelConfigService.View> save(@PathVariable long id,@Valid @RequestBody AiModelCredentialService.Change change) {
        return Result.ok(credentials.save(id,change));
    }
    @PostMapping("/{id}/test")
    @PreAuthorize("@platformAccess.isPlatformIdentity() and hasAuthority('ai:model:test')")
    @Log(title="AI模型测试",businessType=BusinessType.OTHER)
    public Result<OpenRouterModelTester.Outcome> test(@PathVariable long id) { return Result.ok(tester.test(id)); }
    @ModelAttribute
    public void noCache(jakarta.servlet.http.HttpServletResponse response) { response.setHeader("Cache-Control","no-store"); }
    @ExceptionHandler(AccessDeniedException.class)
    public ResponseEntity<Result<Void>> denied() { return ResponseEntity.status(403).body(Result.fail(403,"无权操作模型测试或凭据")); }
}
