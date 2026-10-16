package com.xinghuiTec.controller;

import com.xinghuiTec.annotation.Log;
import com.xinghuiTec.domain.dto.ConfigChangeRequest;
import com.xinghuiTec.domain.vo.ConfigView;
import com.xinghuiTec.emues.BusinessType;
import com.xinghuiTec.service.SysConfigService;
import com.xinghuiTec.utils.Result;
import jakarta.annotation.Resource;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.validation.annotation.Validated;
import org.springframework.web.bind.annotation.*;

import java.util.List;

/** Managed, platform-owned system parameters. */
@RestController
@RequestMapping("/sysConfig")
public class SysConfigController {
    @Resource
    private SysConfigService sysConfigService;

    @GetMapping("/list")
    @PreAuthorize("@platformAccess.isPlatformIdentity() and hasAuthority('system:config:list')")
    public Result<List<ConfigView>> list() {
        return Result.ok(sysConfigService.listManaged());
    }

    @GetMapping("/{id}")
    @PreAuthorize("@platformAccess.isPlatformIdentity() and hasAuthority('system:config:query')")
    public Result<ConfigView> get(@PathVariable Integer id) {
        return Result.ok(sysConfigService.getManaged(id));
    }

    @GetMapping("/value/{key}")
    @PreAuthorize("@platformAccess.isPlatformIdentity() and hasAuthority('system:config:query')")
    public Result<String> value(@PathVariable String key) {
        return Result.ok(sysConfigService.getPublishedValue(key));
    }

    @PostMapping
    @PreAuthorize("@platformAccess.isPlatformIdentity() and hasAuthority('system:config:add')")
    @Log(title = "系统参数", businessType = BusinessType.INSERT)
    public Result<Integer> add(@Validated @RequestBody ConfigChangeRequest request) {
        return Result.ok(sysConfigService.addManaged(request));
    }

    @PutMapping("/{id}")
    @PreAuthorize("@platformAccess.isPlatformIdentity() and hasAuthority('system:config:edit')")
    @Log(title = "系统参数", businessType = BusinessType.UPDATE)
    public Result<Void> edit(@PathVariable Integer id, @Validated @RequestBody ConfigChangeRequest request) {
        sysConfigService.updateManaged(id, request);
        return Result.ok();
    }

    @DeleteMapping("/{id}")
    @PreAuthorize("@platformAccess.isPlatformIdentity() and hasAuthority('system:config:remove')")
    @Log(title = "系统参数", businessType = BusinessType.DELETE)
    public Result<Void> remove(@PathVariable Integer id) {
        sysConfigService.removeManaged(id);
        return Result.ok();
    }
}
