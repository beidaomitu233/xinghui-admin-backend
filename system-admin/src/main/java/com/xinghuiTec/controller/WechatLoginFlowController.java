package com.xinghuiTec.controller;

import com.xinghuiTec.wechat.*;
import com.xinghuiTec.domain.entity.LoginUser;
import com.xinghuiTec.utils.Result;
import jakarta.servlet.http.HttpServletRequest;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.*;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.*;
import java.net.URI;
import java.util.*;

@RestController
@RequestMapping("/auth/wechat")
public class WechatLoginFlowController {
    private final WechatLoginFlowService flows;
    private final com.xinghuiTec.wechat.WechatConfigService configs;
    private final com.xinghuiTec.businessconfig.BusinessConfigService business;
    private final boolean hash;
    public WechatLoginFlowController(WechatLoginFlowService flows,WechatConfigService configs,com.xinghuiTec.businessconfig.BusinessConfigService business,
                                     @Value("${wechat.result-use-hash:true}") boolean hash) {
        this.flows=flows;this.configs=configs;this.business=business;this.hash=hash;
    }
    public record Start(String channel,String returnPath) {}
    public record FlowRequest(String flow) {}
    @GetMapping("/login-capability")
    public Result<Map<String,Boolean>> capability() { return Result.ok(flows.capability()); }
    @PostMapping("/flow")
    public ResponseEntity<Result<WechatLoginFlowService.Started>> start(@RequestBody Start change,HttpServletRequest request) {
        String ua=Optional.ofNullable(request.getHeader("User-Agent")).orElse("").toLowerCase(Locale.ROOT);
        if("mp".equals(change.channel()) && !ua.contains("micromessenger")) throw new WechatFailure(400,"请在微信内打开页面，或使用手机号登录");
        if("website".equals(change.channel()) && (ua.contains("micromessenger") || ua.contains("mobile") || ua.contains("android") || ua.contains("iphone")))
            throw new WechatFailure(400,"网站扫码登录请在电脑浏览器打开；手机可使用手机号登录");
        String binding=binding(request);
        if(!WechatStateStore.valid(binding)) binding=WechatStateStore.random();
        var started=flows.start(change.channel(),change.returnPath(),binding,request.getHeader("Origin"),request.getRemoteAddr());
        boolean secure=request.getHeader("Origin")!=null && request.getHeader("Origin").startsWith("https://");
        var cookie=ResponseCookie.from("WX_FLOW_BIND",binding).httpOnly(true).secure(secure).sameSite("Lax").path("/").maxAge(600).build();
        return ResponseEntity.ok().header(HttpHeaders.SET_COOKIE,cookie.toString()).body(Result.ok(started));
    }
    @GetMapping("/flow/status")
    public Result<WechatLoginFlowService.Status> status(@RequestParam String flow,HttpServletRequest request) { return Result.ok(flows.status(flow,binding(request))); }
    @PostMapping("/flow/cancel")
    public Result<Void> cancel(@RequestBody FlowRequest input,HttpServletRequest request) {
        flows.cancel(input.flow(),binding(request),request.getHeader("Origin"));return Result.ok();
    }
    @PostMapping("/flow/complete")
    public Result<WechatLoginFlowService.Completed> complete(@RequestBody FlowRequest input,HttpServletRequest request) {
        return Result.ok(flows.complete(input.flow(),binding(request),request.getHeader("Origin")));
    }
    // Authenticated endpoint, intentionally omitted from SecurityConfig's anonymous allowlist.
    @PostMapping("/flow/bind")
    public Result<WechatLoginFlowService.Status> bind(@RequestBody FlowRequest input,HttpServletRequest request,@AuthenticationPrincipal LoginUser user) {
        if(user==null) throw new WechatFailure(401,"请先登录已有产品账号，再确认关联");
        return Result.ok(flows.bind(input.flow(),binding(request),request.getHeader("Origin"),user));
    }
    @GetMapping("/mp-callback")
    public ResponseEntity<Void> mpCallback(@RequestParam(required=false) String state,@RequestParam(required=false) String code,HttpServletRequest request) {
        return callback("mp",state,code,request);
    }
    public ResponseEntity<Void> callback(String channel,String state,String code,HttpServletRequest request) {
        String status;
        try { status=flows.callback(channel,state,code,binding(request)); }
        catch(RuntimeException invalid) { status="invalid"; }
        String callback="mp".equals(channel)?business.get("wechatMp").parameters().get("callbackUri"):configs.get().callbackUri();
        String origin="";
        try {
            var uri=URI.create(callback);
            if(uri.getHost()!=null && uri.getUserInfo()==null && java.util.Set.of("https","http").contains(uri.getScheme())) origin=uri.getScheme()+"://"+uri.getRawAuthority();
        } catch(RuntimeException unavailable) { /* Fixed relative result path, never an untrusted Host header. */ }
        return ResponseEntity.status(303).header(HttpHeaders.LOCATION,origin+(hash?"/#/auth/wechat-result":"/auth/wechat-result")+"?status="+status).build();
    }
    static String binding(HttpServletRequest request) {
        if(request.getCookies()!=null) for(var cookie:request.getCookies()) if("WX_FLOW_BIND".equals(cookie.getName())) return cookie.getValue();
        return null;
    }
    @ModelAttribute public void headers(jakarta.servlet.http.HttpServletResponse response) {
        response.setHeader("Cache-Control","no-store");response.setHeader("Referrer-Policy","no-referrer");
    }
    @ExceptionHandler(WechatFailure.class)
    public ResponseEntity<Result<Void>> failed(WechatFailure failure) { return ResponseEntity.status(failure.status).body(Result.fail(failure.status,failure.getMessage())); }
}
