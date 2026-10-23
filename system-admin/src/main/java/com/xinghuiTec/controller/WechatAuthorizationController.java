package com.xinghuiTec.controller;

import com.xinghuiTec.social.utils.WechatWebsiteAuthorization;
import com.xinghuiTec.utils.Result;
import com.xinghuiTec.wechat.*;
import jakarta.servlet.http.Cookie;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.springframework.http.ResponseCookie;
import org.springframework.http.ResponseEntity;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.web.bind.annotation.*;
import java.util.Arrays;

@RestController
@RequestMapping("/auth/wechat")
public class WechatAuthorizationController {
    public static final String COOKIE = "WX_FLOW_BIND";
    private final WechatConfigService configs;
    private final WechatStateStore states;
    private final String resultPath;
    @org.springframework.beans.factory.annotation.Autowired
    private WechatLoginFlowService loginFlows;
    @org.springframework.beans.factory.annotation.Autowired
    private WechatLoginFlowController loginController;
    public WechatAuthorizationController(WechatConfigService configs, WechatStateStore states,
                                         @Value("${wechat.result-use-hash:true}") boolean hashRouter) {
        this.configs = configs; this.states = states;
        this.resultPath = hashRouter ? "/#/auth/wechat-result" : "/auth/wechat-result";
    }
    public record Capability(boolean enabled, boolean available, String reason) {}
    public record Authorization(String authorizationUrl, int expiresInSeconds) {}
    @GetMapping("/capability")
    public Result<Capability> capability() {
        try { return Result.ok(capability(configs.get())); }
        catch (RuntimeException e) { return Result.ok(new Capability(false, false, "CONFIG_UNAVAILABLE")); }
    }
    private Capability capability(WechatConfigService.Config c) {
        String reason = !c.enabled() ? "DISABLED" : c.clientId().isBlank() || c.callbackUri().isBlank()
                ? "NOT_CONFIGURED" : !c.secretAvailable() ? "SECRET_UNAVAILABLE"
                : !states.available() ? "STATE_STORE_UNAVAILABLE" : "AVAILABLE";
        return new Capability(c.enabled(), "AVAILABLE".equals(reason), reason);
    }
    @PostMapping("/authorize")
    public Result<Authorization> authorize(HttpServletRequest request, HttpServletResponse response) {
        // Do not bind or read a body: this endpoint has no input payload, including whitespace/chunks.
        if (request.getContentLengthLong() > 0 || request.getHeader("Transfer-Encoding") != null
                || request.getQueryString() != null)
            throw new WechatFailure(400, "授权发起不接受自定义参数");
        try {
            return configs.withCurrent(c -> {
                if (!capability(c).available()) throw new WechatFailure(503, "微信授权暂时不可用");
                if (!WechatConfigService.origin(c).equals(request.getHeader("Origin")))
                    throw new WechatFailure(403, "请从本站登录页发起授权");
                String binding = binding(request);
                if (!WechatStateStore.valid(binding)) binding = WechatStateStore.random();
                String state = WechatStateStore.random();
                String url = WechatWebsiteAuthorization.authorize(c.clientId(),
                        configs.currentSecret(), c.callbackUri(), state);
                states.create(state, binding, c.revision(), request.getRemoteAddr());
                response.addHeader("Set-Cookie", ResponseCookie.from(COOKIE, binding).httpOnly(true)
                        .secure(c.callbackUri().startsWith("https://")).sameSite("Lax").path("/")
                        .maxAge(WechatStateStore.TTL).build().toString());
                return Result.ok(new Authorization(url, WechatStateStore.TTL));
            });
        } catch (WechatFailure e) { throw e; }
        catch (RuntimeException e) { throw new WechatFailure(503, "微信授权暂时不可用"); }
    }
    @GetMapping("/callback")
    public ResponseEntity<Void> callback(@RequestParam(required = false) String state,
                                         @RequestParam(required = false) String code,
                                         HttpServletRequest request) {
        try {
            if(loginFlows!=null && loginFlows.hasState(state)) return loginController.callback("website",state,code,request);
            return configs.withCurrent(c -> {
                String status = "invalid";
                if (capability(c).available() && states.consume(state, binding(request), c.revision()))
                    status = code == null || code.isBlank() ? "cancelled" : "pending";
                String location = c.callbackUri().isBlank() ? resultPath + "?status=invalid"
                        : WechatConfigService.origin(c) + resultPath + "?status=" + status;
                return ResponseEntity.status(303).header("Location", location).build();
            });
        } catch (RuntimeException e) {
            return ResponseEntity.status(503).build();
        }
    }
    private String binding(HttpServletRequest request) {
        if (request.getCookies() == null) return null;
        return Arrays.stream(request.getCookies()).filter(c -> COOKIE.equals(c.getName()))
                .map(Cookie::getValue).findFirst().orElse(null);
    }
    @ModelAttribute
    public void headers(HttpServletResponse response) {
        response.setHeader("Cache-Control", "no-store");
        response.setHeader("Referrer-Policy", "no-referrer");
    }
    @ExceptionHandler(WechatFailure.class)
    public ResponseEntity<Result<Void>> failed(WechatFailure failure) {
        return ResponseEntity.status(failure.status).body(Result.fail(failure.status, failure.getMessage()));
    }
}
