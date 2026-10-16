package com.xinghuiTec.ai.template;

import com.xinghuiTec.utils.Result;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.*;
import java.util.List;
import java.util.Map;
import static com.xinghuiTec.ai.template.TemplateDataService.*;

@RestController
@RequestMapping("/system/ai/data-resources")
public class DataResourceAdminController {
    private final TemplateDataService service;
    public DataResourceAdminController(TemplateDataService service) { this.service = service; }
    @GetMapping @PreAuthorize("@platformAccess.isPlatformIdentity() and hasAuthority('ai:data:list')")
    public Result<Page> page(@RequestParam(defaultValue="1") int pageNum, @RequestParam(defaultValue="20") int pageSize) { return Result.ok(service.page(true, pageNum, pageSize)); }
    @GetMapping("/sources") @PreAuthorize("@platformAccess.isPlatformIdentity() and hasAuthority('ai:data:query')")
    public Result<?> sources() { return Result.ok(List.of(Map.of("key", "lowcode.record", "name", "低代码表单记录", "parameters", List.of("formId", "recordId"), "requiredAuthority", "lowcode:record:query",
            "inputSchema", Map.of("type", "object", "additionalProperties", false, "required", List.of("formId", "recordId"), "properties", Map.of("formId", Map.of("type", "string", "pattern", "[1-9][0-9]{0,18}"), "recordId", Map.of("type", "string", "pattern", "[1-9][0-9]{0,18}")))))); }
    @GetMapping("/{id}") @PreAuthorize("@platformAccess.isPlatformIdentity() and hasAuthority('ai:data:query')")
    public Result<?> get(@PathVariable long id) { return Result.ok(service.get(true, id)); }
    @PostMapping @PreAuthorize("@platformAccess.isPlatformIdentity() and hasAuthority('ai:data:add')")
    public Result<String> create(@RequestBody ResourceDefinition definition) { return Result.ok(service.createResource(definition)); }
    @PutMapping("/{id}/draft") @PreAuthorize("@platformAccess.isPlatformIdentity() and hasAuthority('ai:data:edit')")
    public Result<?> save(@PathVariable long id, @RequestBody Draft<ResourceDefinition> request) { return Result.ok(service.saveResource(id, request)); }
    @PostMapping("/{id}/test") @PreAuthorize("@platformAccess.isPlatformIdentity() and hasAuthority('ai:data:test')")
    public Result<?> test(@PathVariable long id, @RequestBody Test request) { return Result.ok(service.testResource(id, request)); }
    @PostMapping("/{id}/submit") @PreAuthorize("@platformAccess.isPlatformIdentity() and hasAuthority('ai:data:submit')")
    public Result<?> submit(@PathVariable long id, @RequestBody Revision request) { return Result.ok(service.submit(id, request.revision())); }
    @PostMapping("/{id}/approval") @PreAuthorize("@platformAccess.isPlatformIdentity() and hasAuthority('ai:data:approve')")
    public Result<?> approval(@PathVariable long id, @RequestBody Approval request) { return Result.ok(service.approve(id, request)); }
    @PatchMapping("/{id}/enabled") @PreAuthorize("@platformAccess.isPlatformIdentity() and hasAuthority('ai:data:disable')")
    public Result<?> enabled(@PathVariable long id, @RequestBody Enabled request) { return Result.ok(service.enabled(true, id, request)); }
    @GetMapping("/{id}/checks") @PreAuthorize("@platformAccess.isPlatformIdentity() and hasAuthority('ai:data:query')")
    public Result<?> checks(@PathVariable long id) { return Result.ok(service.evidence(id)); }
    @GetMapping("/{id}/versions/{version}") @PreAuthorize("@platformAccess.isPlatformIdentity() and hasAuthority('ai:data:query')")
    public Result<?> version(@PathVariable long id, @PathVariable long version) { return Result.ok(service.version(true, id, version)); }
    @GetMapping("/{id}/versions/{version}/references") @PreAuthorize("@platformAccess.isPlatformIdentity() and hasAuthority('ai:data:query')")
    public Result<?> references(@PathVariable long id, @PathVariable long version) { return Result.ok(service.references(true, id, version)); }
}
