package com.xinghuiTec.ai.template;

import com.xinghuiTec.utils.Result;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.*;
import static com.xinghuiTec.ai.template.TemplateDataService.*;

/** Content is deliberately excluded from generic operation logging. */
@RestController
@RequestMapping("/system/ai/templates")
public class TemplateAdminController {
    private final TemplateDataService service;
    public TemplateAdminController(TemplateDataService service) { this.service = service; }
    @GetMapping @PreAuthorize("@platformAccess.isPlatformIdentity() and hasAuthority('ai:template:list')")
    public Result<Page> page(@RequestParam(defaultValue="1") int pageNum, @RequestParam(defaultValue="20") int pageSize) { return Result.ok(service.page(false, pageNum, pageSize)); }
    @GetMapping("/options")
    @PreAuthorize("@platformAccess.isPlatformIdentity() and hasAnyAuthority('ai:template:query','ai:application:query','ai:application:add','ai:application:edit','ai:application:test')")
    public Result<OptionsPage> options(@RequestParam(defaultValue="1") int pageNum, @RequestParam(defaultValue="20") int pageSize,
                                       @RequestParam(required=false) String keyword, @RequestParam(required=false) String appId) {
        return Result.ok(service.options(pageNum, pageSize, keyword, appId));
    }
    @GetMapping("/legacy-scenes") @PreAuthorize("@platformAccess.isPlatformIdentity() and hasAuthority('ai:template:query')")
    public Result<Page> legacy(@RequestParam(defaultValue="1") int pageNum, @RequestParam(defaultValue="20") int pageSize) { return Result.ok(service.legacyScenes(pageNum, pageSize)); }
    @GetMapping("/{id}") @PreAuthorize("@platformAccess.isPlatformIdentity() and hasAuthority('ai:template:query')")
    public Result<?> get(@PathVariable long id) { return Result.ok(service.get(false, id)); }
    @PostMapping @PreAuthorize("@platformAccess.isPlatformIdentity() and hasAuthority('ai:template:add')")
    public Result<String> create(@RequestBody TemplateDefinition definition) { return Result.ok(service.createTemplate(definition)); }
    @PutMapping("/{id}/draft") @PreAuthorize("@platformAccess.isPlatformIdentity() and hasAuthority('ai:template:edit')")
    public Result<?> save(@PathVariable long id, @RequestBody Draft<TemplateDefinition> draft) { return Result.ok(service.saveTemplate(id, draft)); }
    @PostMapping("/{id}/preview") @PreAuthorize("@platformAccess.isPlatformIdentity() and hasAuthority('ai:template:preview')")
    public Result<?> preview(@PathVariable long id, @RequestBody Test request) { return Result.ok(service.testTemplate(id, request, false)); }
    @PostMapping("/{id}/test") @PreAuthorize("@platformAccess.isPlatformIdentity() and hasAuthority('ai:template:test')")
    public Result<?> test(@PathVariable long id, @RequestBody Test request) { return Result.ok(service.testTemplate(id, request, true)); }
    @PostMapping("/{id}/publish") @PreAuthorize("@platformAccess.isPlatformIdentity() and hasAuthority('ai:template:publish')")
    public Result<?> publish(@PathVariable long id, @RequestBody Revision request) { return Result.ok(service.publish(id, request.revision())); }
    @PatchMapping("/{id}/enabled") @PreAuthorize("@platformAccess.isPlatformIdentity() and hasAuthority('ai:template:disable')")
    public Result<?> enabled(@PathVariable long id, @RequestBody Enabled request) { return Result.ok(service.enabled(false, id, request)); }
    @GetMapping("/{id}/versions/{version}") @PreAuthorize("@platformAccess.isPlatformIdentity() and hasAuthority('ai:template:query')")
    public Result<?> version(@PathVariable long id, @PathVariable long version) { return Result.ok(service.version(false, id, version)); }
    @GetMapping("/{id}/versions/{version}/references") @PreAuthorize("@platformAccess.isPlatformIdentity() and hasAuthority('ai:template:query')")
    public Result<?> references(@PathVariable long id, @PathVariable long version) { return Result.ok(service.references(false, id, version)); }
}
