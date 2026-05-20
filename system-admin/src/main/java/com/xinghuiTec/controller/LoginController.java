package com.xinghuiTec.controller;

import com.xinghuiTec.domain.dto.loginDTO;
import com.xinghuiTec.domain.vo.CaptchaVO;
import com.xinghuiTec.domain.vo.SysMenuVO;
import com.xinghuiTec.service.CaptchaService;
import com.xinghuiTec.service.LoginService;
import com.xinghuiTec.utils.Result;
import com.xinghuiTec.domain.vo.UserInfoVO;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RestController;
import com.xinghuiTec.ratelimiter.annotation.RateLimiter;

import java.util.List;

@RestController
public class LoginController {

    @Autowired
    private LoginService loginService;

    @Autowired
    private CaptchaService captchaService;

    /**
     * 禁用验证码时的缓存响应 (避免重复创建 Result 对象)
     * 登录
     * 获取用户信息
     * 退出登录
     * 获取验证码 (可选)
     * 获取路由信息
     */
    private static final Result<CaptchaVO> DISABLED_CAPTCHA_RESULT = Result.ok(new CaptchaVO("", "", false));

    /**
     * 获取验证码
     * 
     * @return 验证码 VO,包含 UUID 和 Base64 图片
     */
    @GetMapping("/captcha")
    public Result<CaptchaVO> getCaptcha() {
        CaptchaVO captchaVO = captchaService.generateCaptcha();

        // 性能优化: 禁用状态直接返回缓存的 Result 对象
        if (!captchaVO.getEnabled()) {
            return DISABLED_CAPTCHA_RESULT;
        }

        return Result.ok(captchaVO);
    }

    /**
     * 用户登录
     * 
     * @param user 登录信息(包含用户名、密码、验证码和UUID)
     * @return JWT token
     */
    @RateLimiter(key = "#user.phone", time = 60, count = 5, message = "登录过于频繁，请1分钟后再试")
    @PostMapping("/user/login")
    public Result<String> login(@RequestBody loginDTO user) {
        String token = loginService.login(user);
        return Result.ok(token);
    }

    /**
     * 用户注销
     *
     * @return ok
     */
    @GetMapping("/user/logout")
    public Result<String> logout() {
        String message = loginService.logout();
        return Result.ok(message);
    }

    /**
     * 获取用户信息
     *
     * @return 用户信息
     */
    @GetMapping("/user/info")
    public Result<UserInfoVO> getUserInfo() {
        UserInfoVO userInfo = loginService.getUserInfo();
        return Result.ok(userInfo);
    }

    /**
     * 获取用户router
     *
     * @return 树型结构router列表
     */
    @GetMapping("/user/router")
    public Result<List<SysMenuVO>> getUserRouter() {
        List<SysMenuVO> router = loginService.getUserRouter();
        return Result.ok(router);
    }

}
