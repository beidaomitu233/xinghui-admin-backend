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
@RequestMapping("/lowcode/forms/{formId}")
public class LowcodeRecordController {
    private final LowcodeService service;

    public LowcodeRecordController(LowcodeService service) {
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

    @GetMapping("/runtime")
    @PreAuthorize("hasAnyAuthority('lowcode:record:list','lowcode:record:query','lowcode:record:add','lowcode:record:edit','lowcode:record:remove')")
    public Result<Map<String, Object>> runtime(@PathVariable long formId) {
        return Result.ok(service.runtime(formId));
    }

    @GetMapping("/records")
    @PreAuthorize("hasAuthority('lowcode:record:list')")
    public Result<LowcodeService.Page> list(@PathVariable long formId, @RequestParam(defaultValue = "1") int pageNum,
            @RequestParam(defaultValue = "10") int pageSize, @RequestParam(required = false) String filterField,
            @RequestParam(required = false) String filterValue) {
        return Result.ok(service.records(formId, pageNum, pageSize, filterField, filterValue));
    }

    @GetMapping("/records/{recordId}")
    @PreAuthorize("hasAuthority('lowcode:record:query')")
    public Result<Map<String, Object>> get(@PathVariable long formId, @PathVariable long recordId) {
        return Result.ok(service.record(formId, recordId));
    }

    @PostMapping("/records")
    @PreAuthorize("hasAuthority('lowcode:record:add')")
    public Result<Map<String, Object>> create(@PathVariable long formId, @RequestBody LowcodeService.Submission input) {
        return Result.ok(service.addRecord(formId, input));
    }

    @PutMapping("/records/{recordId}")
    @PreAuthorize("hasAuthority('lowcode:record:edit')")
    public Result<Map<String, Object>> update(@PathVariable long formId, @PathVariable long recordId,
            @RequestBody LowcodeService.Submission input) {
        return Result.ok(service.updateRecord(formId, recordId, input));
    }

    @DeleteMapping("/records/{recordId}")
    @PreAuthorize("hasAuthority('lowcode:record:remove')")
    public Result<Void> remove(@PathVariable long formId, @PathVariable long recordId, @RequestParam int revision) {
        service.removeRecord(formId, recordId, revision);
        return Result.ok();
    }
}
