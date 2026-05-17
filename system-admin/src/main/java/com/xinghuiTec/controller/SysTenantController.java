package com.xinghuiTec.controller;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import com.xinghuiTec.domain.entity.SysTenant;
import com.xinghuiTec.service.ISysTenantService;
import com.xinghuiTec.utils.Result;
import com.xinghuiTec.utils.TenantHelper;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.*;

/**
 * 租户管理 Controller
 *
 * @author xinghuiTec
 */
@RestController
@RequestMapping("/system/tenant")
public class SysTenantController {

    @Autowired
    private ISysTenantService tenantService;

    /**
     * 查询租户列表
     */
    @GetMapping("/list")
    @PreAuthorize("hasAuthority('system:tenant:list')")
    public Result<Page<SysTenant>> list(@RequestParam(defaultValue = "1") Integer pageNum,
                                        @RequestParam(defaultValue = "10") Integer pageSize) {
        Page<SysTenant> page = TenantHelper.ignore(() ->
            tenantService.page(new Page<>(pageNum, pageSize),
                new LambdaQueryWrapper<SysTenant>().orderByAsc(SysTenant::getId))
        );
        return Result.ok(page);
    }

    /**
     * 根据ID查询租户详情
     */
    @GetMapping("/{id}")
    @PreAuthorize("hasAuthority('system:tenant:query')")
    public Result<SysTenant> getInfo(@PathVariable Long id) {
        return TenantHelper.ignore(() -> Result.ok(tenantService.getById(id)));
    }

    /**
     * 新增租户
     */
    @PostMapping
    @PreAuthorize("hasAuthority('system:tenant:add')")
    public Result<Void> add(@RequestBody SysTenant tenant) {
        TenantHelper.ignore(() -> tenantService.save(tenant));
        return Result.ok();
    }

    /**
     * 修改租户
     */
    @PutMapping
    @PreAuthorize("hasAuthority('system:tenant:edit')")
    public Result<Void> edit(@RequestBody SysTenant tenant) {
        TenantHelper.ignore(() -> tenantService.updateById(tenant));
        return Result.ok();
    }

    /**
     * 删除租户
     */
    @DeleteMapping("/{ids}")
    @PreAuthorize("hasAuthority('system:tenant:remove')")
    public Result<Void> remove(@PathVariable Long[] ids) {
        TenantHelper.ignore(() -> {
            for (Long id : ids) {
                tenantService.removeById(id);
            }
        });
        return Result.ok();
    }
}
