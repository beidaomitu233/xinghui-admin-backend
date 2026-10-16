package com.xinghuiTec.controller;

import com.xinghuiTec.annotation.Log;
import com.xinghuiTec.content.ContentService;
import com.xinghuiTec.content.ContentService.NewsChange;
import com.xinghuiTec.content.ContentService.Module;
import com.xinghuiTec.emues.BusinessType;
import com.xinghuiTec.utils.Result;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.*;

import java.util.Map;

@RestController
@RequestMapping("/system/news")
public class ContentNewsController {
    private final ContentService service;

    public ContentNewsController(ContentService service) {
        this.service = service;
    }

    @GetMapping({"", "/list"})
    @PreAuthorize("@platformAccess.isPlatformIdentity() and hasAuthority('system:news:list')")
    public Result<ContentService.Page> list(
            @RequestParam(defaultValue = "1") int pageNum,
            @RequestParam(defaultValue = "10") int pageSize,
            @RequestParam(required = false) String title,
            @RequestParam(required = false) String status) {
        return Result.ok(service.list(Module.NEWS, pageNum, pageSize, title, status));
    }

    @GetMapping("/{id}")
    @PreAuthorize("@platformAccess.isPlatformIdentity() and hasAuthority('system:news:query')")
    public Result<Map<String, Object>> get(@PathVariable String id) {
        return Result.ok(service.get(Module.NEWS, id));
    }

    @PostMapping
    @PreAuthorize("@platformAccess.isPlatformIdentity() and hasAuthority('system:news:add')")
    @Log(title = "资讯管理", businessType = BusinessType.INSERT)
    public Result<String> create(@RequestBody NewsChange change) {
        return Result.ok(service.createNews(change));
    }

    @PutMapping("/{id}")
    @PreAuthorize("@platformAccess.isPlatformIdentity() and hasAuthority('system:news:edit')")
    @Log(title = "资讯管理", businessType = BusinessType.UPDATE)
    public Result<Void> update(@PathVariable String id, @RequestBody NewsChange change) {
        service.updateNews(id, change);
        return Result.ok();
    }

    @DeleteMapping("/{id}")
    @PreAuthorize("@platformAccess.isPlatformIdentity() and hasAuthority('system:news:remove')")
    @Log(title = "资讯管理", businessType = BusinessType.DELETE)
    public Result<Void> delete(@PathVariable String id) {
        service.remove(Module.NEWS, id);
        return Result.ok();
    }
}
