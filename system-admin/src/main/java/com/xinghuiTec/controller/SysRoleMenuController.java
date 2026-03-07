package com.xinghuiTec.controller;

import com.xinghuiTec.service.SysRoleMenuService;
import org.springframework.web.bind.annotation.*;
import jakarta.annotation.Resource;

/**
 * 角色和菜单关联表(SysRoleMenu)表控制层
 *
 * @author beidoa23
 * @since 2025-12-25 19:33:19
 */

@RestController
@RequestMapping("/sysRoleMenu")
public class SysRoleMenuController{
    @Resource
    private SysRoleMenuService sysRoleMenuService;

}


