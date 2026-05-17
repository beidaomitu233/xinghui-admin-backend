package com.xinghuiTec.controller;

import com.xinghuiTec.domain.entity.SysTenantPackage;
import com.xinghuiTec.service.ISysTenantPackageService;
import com.xinghuiTec.utils.Result;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.*;

import java.util.List;

/**
 * 租户套餐管理控制器
 *
 * @author xinghuiTec
 */
@RestController
@RequestMapping("/system/tenant/package")
@RequiredArgsConstructor
public class SysTenantPackageController {

    private final ISysTenantPackageService tenantPackageService;

    /**
     * 查询租户套餐列表
     */
    @GetMapping("/list")
    public Result<List<SysTenantPackage>> list(SysTenantPackage tenantPackage) {
        return Result.success(tenantPackageService.queryList(tenantPackage));
    }

    /**
     * 根据ID获取租户套餐详细信息
     */
    @GetMapping("/{packageId}")
    public Result<SysTenantPackage> getInfo(@PathVariable Long packageId) {
        return Result.success(tenantPackageService.queryById(packageId));
    }

    /**
     * 新增租户套餐
     */
    @PostMapping("/add")
    public Result<String> add(@RequestBody SysTenantPackage tenantPackage) {
        tenantPackageService.insertByBo(tenantPackage);
        return Result.success("创建成功");
    }

    /**
     * 修改租户套餐
     */
    @PostMapping("/edit")
    public Result<String> edit(@RequestBody SysTenantPackage tenantPackage) {
        tenantPackageService.updateByBo(tenantPackage);
        return Result.success("修改成功");
    }

    /**
     * 删除租户套餐
     */
    @PostMapping("/remove/{ids}")
    public Result<String> remove(@PathVariable List<Long> ids) {
        tenantPackageService.deleteWithValidByIds(ids, true);
        return Result.success("删除成功");
    }
}
