package com.xinghuiTec.controller;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import com.xinghuiTec.domain.entity.SysTenantPackage;
import com.xinghuiTec.service.ISysTenantPackageService;
import com.xinghuiTec.utils.Result;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.*;

/**
 * 租户套餐管理 Controller
 *
 * @author xinghuiTec
 */
@RestController
@RequestMapping("/system/tenantPackage")
public class SysTenantPackageController {

    @Autowired
    private ISysTenantPackageService packageService;

    /**
     * 查询套餐列表
     */
    @GetMapping("/list")
    @PreAuthorize("hasAuthority('system:tenantPackage:list')")
    public Result<Page<SysTenantPackage>> list(@RequestParam(defaultValue = "1") Integer pageNum,
                                               @RequestParam(defaultValue = "10") Integer pageSize) {
        Page<SysTenantPackage> page = packageService.page(new Page<>(pageNum, pageSize),
            new LambdaQueryWrapper<SysTenantPackage>().orderByAsc(SysTenantPackage::getPackageId));
        return Result.ok(page);
    }

    /**
     * 查询套餐详情
     */
    @GetMapping("/{packageId}")
    @PreAuthorize("hasAuthority('system:tenantPackage:query')")
    public Result<SysTenantPackage> getInfo(@PathVariable Long packageId) {
        return Result.ok(packageService.getById(packageId));
    }

    /**
     * 新增套餐
     */
    @PostMapping
    @PreAuthorize("hasAuthority('system:tenantPackage:add')")
    public Result<Void> add(@RequestBody SysTenantPackage tenantPackage) {
        packageService.save(tenantPackage);
        return Result.ok();
    }

    /**
     * 修改套餐
     */
    @PutMapping
    @PreAuthorize("hasAuthority('system:tenantPackage:edit')")
    public Result<Void> edit(@RequestBody SysTenantPackage tenantPackage) {
        packageService.updateById(tenantPackage);
        return Result.ok();
    }

    /**
     * 删除套餐
     */
    @DeleteMapping("/{packageIds}")
    @PreAuthorize("hasAuthority('system:tenantPackage:remove')")
    public Result<Void> remove(@PathVariable Long[] packageIds) {
        for (Long id : packageIds) {
            packageService.removeById(id);
        }
        return Result.ok();
    }
}
