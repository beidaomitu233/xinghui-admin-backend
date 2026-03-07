package com.xinghuiTec.controller;

import com.xinghuiTec.service.SysUserRoleService;
import org.springframework.web.bind.annotation.*;
import jakarta.annotation.Resource;

/**
 * 用户和角色关联表(SysUserRole)表控制层
 *
 * @author beidoa23
 * @since 2025-12-25 19:33:19
 */

@RestController
@RequestMapping("/sysUserRole")
public class SysUserRoleController{
    @Resource
    private SysUserRoleService sysUserRoleService;

}


