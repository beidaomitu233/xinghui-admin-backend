package com.xinghuiTec.controller;

import com.xinghuiTec.domain.entity.SysTenant;
import com.xinghuiTec.service.ISysTenantService;
import com.xinghuiTec.utils.Result;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.*;

import java.util.List;

/**
 * 租户管理控制器
 *
 * @author xinghuiTec
 */
@RestController
@RequestMapping("/system/tenant")
@RequiredArgsConstructor
public class SysTenantController {

    private final ISysTenantService tenantService;

    /**
     * 查询租户列表
     */
    @GetMapping("/list")
    public Result<List<SysTenant>> list(SysTenant tenant) {
        return Result.success(tenantService.queryList(tenant));
    }

    /**
     * 根据ID获取租户详细信息
     */
    @GetMapping("/{id}")
    public Result<SysTenant> getInfo(@PathVariable Long id) {
        return Result.success(tenantService.queryById(id));
    }

    /**
     * 根据租户ID获取租户详细信息
     */
    @GetMapping("/info/{tenantId}")
    public Result<SysTenant> getInfoByTenantId(@PathVariable String tenantId) {
        return Result.success(tenantService.queryByTenantId(tenantId));
    }

    /**
     * 新增租户
     */
    @PostMapping("/add")
    public Result<String> add(@RequestBody SysTenant tenant,
                              @RequestParam String username,
                              @RequestParam String password) {
        if (!tenantService.checkCompanyNameUnique(tenant)) {
            return Result.fail("企业名称已存在");
        }
        tenantService.insertByBo(tenant, username, password);
        return Result.success("创建成功");
    }

    /**
     * 修改租户
     */
    @PostMapping("/edit")
    public Result<String> edit(@RequestBody SysTenant tenant) {
        tenantService.updateByBo(tenant);
        return Result.success("修改成功");
    }

    /**
     * 修改租户状态
     */
    @PostMapping("/changeStatus")
    public Result<String> changeStatus(@RequestBody SysTenant tenant) {
        tenantService.updateTenantStatus(tenant);
        return Result.success("修改成功");
    }

    /**
     * 删除租户
     */
    @PostMapping("/remove/{ids}")
    public Result<String> remove(@PathVariable List<Long> ids) {
        tenantService.deleteWithValidByIds(ids, true);
        return Result.success("删除成功");
    }
}
