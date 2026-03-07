package com.xinghuiTec.controller;

import com.xinghuiTec.service.SysConfigService;
import jakarta.annotation.Resource;
import org.springframework.web.bind.annotation.*;


/**
 * 参数配置表(SysConfig)表控制层
 *
 * @author beidoa23
 * @since 2025-12-25 19:33:06
 */

@RestController
@RequestMapping("/sysConfig")
public class SysConfigController{
    @Resource
    private SysConfigService sysConfigService;

}


