package com.xinghuiTec.controller;

import com.xinghuiTec.annotation.Log;
import com.xinghuiTec.businessconfig.*;
import com.xinghuiTec.emues.BusinessType;
import com.xinghuiTec.ratelimiter.annotation.RateLimiter;
import com.xinghuiTec.ratelimiter.enums.LimitType;
import com.xinghuiTec.utils.Result;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.*;

@RestController
@RequestMapping("/system/businessConfig")
public class BusinessConfigProbeController {
    private final BusinessConfigProbe probes;
    public BusinessConfigProbeController(BusinessConfigProbe probes){this.probes=probes;}
    @PostMapping("/{section}/test")
    @PreAuthorize("@platformAccess.isPlatformIdentity() and hasAuthority('system:businessConfig:edit')")
    @RateLimiter(limitType=LimitType.IP,time=60,count=3,message="测试过于频繁，请稍后重试")
    @Log(title="业务配置测试",businessType=BusinessType.OTHER)
    public Result<BusinessConfigProbe.Receipt> test(@PathVariable String section,@RequestBody BusinessConfigProbe.Request request){return Result.ok(probes.test(section,request));}
    @ExceptionHandler(BusinessConfigService.Failure.class)
    public ResponseEntity<Result<Void>> failed(BusinessConfigService.Failure failure){return ResponseEntity.status(failure.status).body(Result.fail(failure.status,failure.getMessage()));}
    @ModelAttribute public void noCache(jakarta.servlet.http.HttpServletResponse response){response.setHeader("Cache-Control","no-store");}
}
