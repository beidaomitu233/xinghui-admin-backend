package com.xinghuiTec.controller;

import com.xinghuiTec.ratelimiter.annotation.RateLimiter;
import com.xinghuiTec.ratelimiter.enums.LimitType;
import com.xinghuiTec.service.SmsLoginService;
import com.xinghuiTec.utils.Result;
import org.springframework.web.bind.annotation.*;
import java.util.Map;

@RestController
@RequestMapping("/auth/sms")
public class SmsLoginController {
    private final SmsLoginService service;
    public SmsLoginController(SmsLoginService service) { this.service=service; }
    public record Send(String phone,String uuid,String code) {}
    public record Login(String phone,String requestId,String code) {}
    @GetMapping("/capability")
    public Result<Map<String,Boolean>> capability() { return Result.ok(Map.of("available",service.available())); }
    @PostMapping("/code")
    @RateLimiter(limitType=LimitType.IP,time=3600,count=10,message="短信请求过于频繁，请稍后重试")
    public Result<SmsLoginService.Challenge> send(@RequestBody Send body) {
        return Result.ok(service.send(body.phone(),body.uuid(),body.code()));
    }
    @PostMapping("/login")
    @RateLimiter(limitType=LimitType.IP,time=60,count=10,message="登录过于频繁，请稍后重试")
    public Result<String> login(@RequestBody Login body) {
        return Result.ok(service.login(body.phone(),body.requestId(),body.code()));
    }
}
