package com.xinghuiTec.app.mobile.controller;

import com.xinghuiTec.utils.Result;
import lombok.extern.slf4j.Slf4j;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * 应用端业务模块示例控制器
 * 用于验证 web-app 模块装配是否生效，业务开发时可参考本类结构后删除
 *
 * @author xinghuiTec
 */
@Slf4j
@RestController
@RequestMapping("/app/demo")
public class MobileDemoController {

    /**
     * 模块连通性检查（需登录访问）
     */
    @GetMapping
    public Result<String> demo() {
        return Result.ok("web-app 模块装配成功");
    }
}
