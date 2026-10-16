package com.xinghuiTec.controller;

import com.xinghuiTec.utils.Result;
import com.xinghuiTec.wechat.WechatConfigService;
import com.xinghuiTec.wechat.WechatFailure;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.*;

@RestController
@RequestMapping("/system/wechat/config")
public class WechatConfigController {
    private final WechatConfigService configs;
    public WechatConfigController(WechatConfigService configs) { this.configs = configs; }
    @GetMapping
    @PreAuthorize("@platformAccess.isPlatformIdentity() and hasAuthority('system:wechat:query')")
    public Result<WechatConfigService.Config> get() { return Result.ok(configs.get()); }
    @PutMapping
    @PreAuthorize("@platformAccess.isPlatformIdentity() and hasAuthority('system:wechat:edit')")
    public Result<WechatConfigService.Config> save(@RequestBody WechatConfigService.Change change) {
        return Result.fail(409,"请在系统业务配置中保存微信设置");
    }
    @ExceptionHandler(WechatFailure.class)
    public ResponseEntity<Result<Void>> failed(WechatFailure failure) {
        return ResponseEntity.status(failure.status).body(Result.fail(failure.status, failure.getMessage()));
    }
    @ExceptionHandler(AccessDeniedException.class)
    public ResponseEntity<Result<Void>> denied() {
        return ResponseEntity.status(403).body(Result.fail(403, "无权访问微信授权配置"));
    }
    @ModelAttribute
    public void noCache(jakarta.servlet.http.HttpServletResponse response) {
        response.setHeader("Cache-Control", "no-store");
    }
}
