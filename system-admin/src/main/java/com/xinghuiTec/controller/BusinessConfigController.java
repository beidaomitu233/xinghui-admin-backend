package com.xinghuiTec.controller;

import com.xinghuiTec.businessconfig.BusinessConfigService;
import com.xinghuiTec.annotation.Log;
import com.xinghuiTec.emues.BusinessType;
import com.xinghuiTec.exception.ServiceException;
import com.xinghuiTec.utils.Result;
import com.xinghuiTec.wechat.WechatFailure;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.*;
import java.util.List;

@RestController
@RequestMapping("/system/businessConfig")
public class BusinessConfigController {
    private final BusinessConfigService configs;
    public BusinessConfigController(BusinessConfigService configs) { this.configs=configs; }
    @GetMapping
    @PreAuthorize("@platformAccess.isPlatformIdentity() and hasAuthority('system:businessConfig:query')")
    public Result<List<BusinessConfigService.View>> all() { return Result.ok(configs.all()); }
    @GetMapping("/{section}")
    @PreAuthorize("@platformAccess.isPlatformIdentity() and hasAuthority('system:businessConfig:query')")
    public Result<BusinessConfigService.View> get(@PathVariable String section) { return Result.ok(configs.get(section)); }
    @PutMapping("/{section}")
    @PreAuthorize("@platformAccess.isPlatformIdentity() and hasAuthority('system:businessConfig:edit')")
    @Log(title="系统业务配置",businessType=BusinessType.UPDATE)
    public Result<BusinessConfigService.View> save(@PathVariable String section,@RequestBody BusinessConfigService.Change change) { return Result.ok(configs.save(section,change)); }
    @ExceptionHandler(BusinessConfigService.Failure.class)
    public ResponseEntity<Result<Void>> failed(BusinessConfigService.Failure failure) { return ResponseEntity.status(failure.status).body(Result.fail(failure.status,failure.getMessage())); }
    @ExceptionHandler(WechatFailure.class)
    public ResponseEntity<Result<Void>> wechatFailed(WechatFailure failure) { return ResponseEntity.status(failure.status).body(Result.fail(failure.status,failure.getMessage())); }
    @ExceptionHandler(ServiceException.class)
    public ResponseEntity<Result<Void>> invalid(ServiceException failure) { return ResponseEntity.badRequest().body(Result.fail(400,failure.getMessage())); }
    @ExceptionHandler(AccessDeniedException.class)
    public ResponseEntity<Result<Void>> denied() { return ResponseEntity.status(403).body(Result.fail(403,"无权操作系统业务配置")); }
    @ModelAttribute public void noCache(jakarta.servlet.http.HttpServletResponse response) { response.setHeader("Cache-Control","no-store"); }
}
