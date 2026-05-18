package com.xinghuiTec.oss.controller;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import com.xinghuiTec.oss.entity.SysOssConfig;
import com.xinghuiTec.oss.service.ISysOssConfigService;
import com.xinghuiTec.utils.Result;
import lombok.RequiredArgsConstructor;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.*;

/**
 * OSS 存储配置管理
 *
 * @author xinghuiTec
 */
@RestController
@RequestMapping("/system/oss/config")
@RequiredArgsConstructor
public class SysOssConfigController {

    private final ISysOssConfigService configService;

    /** 配置列表 */
    @PreAuthorize("hasAuthority('system:oss:config:list')")
    @GetMapping("/list")
    public Result<Page<SysOssConfig>> list(@RequestParam(defaultValue = "1") Integer pageNum,
                                            @RequestParam(defaultValue = "10") Integer pageSize) {
        LambdaQueryWrapper<SysOssConfig> qw = new LambdaQueryWrapper<>();
        qw.orderByAsc(SysOssConfig::getOssConfigId);
        return Result.ok(configService.page(new Page<>(pageNum, pageSize), qw));
    }

    /** 配置详情 */
    @PreAuthorize("hasAuthority('system:oss:config:query')")
    @GetMapping("/{configId}")
    public Result<SysOssConfig> getInfo(@PathVariable Long configId) {
        return Result.ok(configService.getById(configId));
    }

    /** 新增配置 */
    @PreAuthorize("hasAuthority('system:oss:config:add')")
    @PostMapping
    public Result<Void> add(@RequestBody SysOssConfig config) {
        configService.save(config);
        return Result.ok();
    }

    /** 修改配置 */
    @PreAuthorize("hasAuthority('system:oss:config:edit')")
    @PutMapping
    public Result<Void> edit(@RequestBody SysOssConfig config) {
        configService.updateById(config);
        return Result.ok();
    }

    /** 删除配置 */
    @PreAuthorize("hasAuthority('system:oss:config:remove')")
    @DeleteMapping("/{configIds}")
    public Result<Void> remove(@PathVariable Long[] configIds) {
        for (Long id : configIds) {
            configService.removeById(id);
        }
        return Result.ok();
    }
}
