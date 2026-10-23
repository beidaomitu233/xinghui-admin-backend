package com.xinghuiTec.lowcode;

import com.xinghuiTec.utils.Result;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.Map;

import static com.xinghuiTec.emues.ResultCodeEnum.ADMIN_ACCESS_FORBIDDEN;

@RestController
@RequestMapping("/lowcode/forms")
public class LowcodeFormController {
    private final LowcodeService service;

    public LowcodeFormController(LowcodeService service) {
        this.service = service;
    }

    @ExceptionHandler({org.springframework.http.converter.HttpMessageNotReadableException.class,
            org.springframework.web.method.annotation.MethodArgumentTypeMismatchException.class,
            org.springframework.web.bind.MissingServletRequestParameterException.class})
    public ResponseEntity<Result<Void>> badRequest() {
        return ResponseEntity.status(HttpStatus.BAD_REQUEST).body(Result.fail(400, "请求参数或数据格式不正确"));
    }

    @ExceptionHandler(AccessDeniedException.class)
    public ResponseEntity<Result<Void>> denied() {
        return ResponseEntity.status(HttpStatus.FORBIDDEN).body(Result.fail(ADMIN_ACCESS_FORBIDDEN));
    }

    @GetMapping
    @PreAuthorize("@platformAccess.isPlatformIdentity() and hasAuthority('lowcode:form:list')")
    public Result<LowcodeService.Page> list(@RequestParam(defaultValue = "1") int pageNum,
            @RequestParam(defaultValue = "10") int pageSize, @RequestParam(required = false) String name,
            @RequestParam(required = false) String orderBy, @RequestParam(required = false) String order) {
        return Result.ok(service.forms(pageNum, pageSize, name, orderBy, order));
    }

    @GetMapping("/{id}")
    @PreAuthorize("@platformAccess.isPlatformIdentity() and hasAuthority('lowcode:form:query')")
    public Result<Map<String, Object>> get(@PathVariable long id) {
        return Result.ok(service.form(id));
    }

    @PostMapping
    @PreAuthorize("@platformAccess.isPlatformIdentity() and hasAuthority('lowcode:form:add')")
    public Result<String> create(@RequestBody LowcodeService.Draft input) {
        return Result.ok(service.create(input));
    }

    @PutMapping("/{id}")
    @PreAuthorize("@platformAccess.isPlatformIdentity() and hasAuthority('lowcode:form:edit')")
    public Result<Map<String, Object>> update(@PathVariable long id, @RequestBody LowcodeService.Draft input) {
        return Result.ok(service.update(id, input));
    }

    @PostMapping("/{id}/publish")
    @PreAuthorize("@platformAccess.isPlatformIdentity() and hasAuthority('lowcode:form:publish')")
    public Result<Map<String, Object>> publish(@PathVariable long id, @RequestBody LowcodeService.VersionRequest input) {
        return Result.ok(service.publish(id, input.revision()));
    }

    @DeleteMapping("/{id}")
    @PreAuthorize("@platformAccess.isPlatformIdentity() and hasAuthority('lowcode:form:remove')")
    public Result<Void> remove(@PathVariable long id, @RequestParam int revision) {
        service.removeForm(id, revision);
        return Result.ok();
    }
}
