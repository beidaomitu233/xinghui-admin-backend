package com.xinghuiTec.app.admin.controller;

import com.xinghuiTec.utils.Result;
import lombok.extern.slf4j.Slf4j;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * 管理端业务模块示例控制器
 * 用于验证 web-admin 模块装配是否生效，业务开发时可参考本类结构后删除
 *
 * @author xinghuiTec
 */
@Slf4j
@RestController
@RequestMapping("/admin/demo")
public class AdminDemoController {

    /**
     * 模块连通性检查（需登录访问）
     */
    @GetMapping
    public Result<String> demo() {
        return Result.ok("web-admin 模块装配成功");
    }
}
