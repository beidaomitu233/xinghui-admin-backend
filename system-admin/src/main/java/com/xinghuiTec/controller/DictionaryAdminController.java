package com.xinghuiTec.controller;

import com.xinghuiTec.annotation.Log;
import com.xinghuiTec.emues.BusinessType;
import com.xinghuiTec.service.DictionaryService;
import com.xinghuiTec.service.DictionaryService.*;
import com.xinghuiTec.utils.Result;
import jakarta.validation.Valid;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.*;

import java.util.List;

import static com.xinghuiTec.emues.ResultCodeEnum.ADMIN_ACCESS_FORBIDDEN;

/** Configuration APIs are guarded by both platform identity and operation permission. */
@RestController
@RequestMapping("/system/dict")
public class DictionaryAdminController {
    private final DictionaryService dictionary;

    public DictionaryAdminController(DictionaryService dictionary) {
        this.dictionary = dictionary;
    }

    @ExceptionHandler(AccessDeniedException.class)
    public ResponseEntity<Result<Void>> denied() {
        return ResponseEntity.status(HttpStatus.FORBIDDEN).body(Result.fail(ADMIN_ACCESS_FORBIDDEN));
    }

    @GetMapping("/types")
    @PreAuthorize("@platformAccess.isPlatformIdentity() and hasAuthority('system:dict:list')")
    public Result<List<TypeView>> types() {
        return Result.ok(dictionary.listTypes());
    }

    @GetMapping("/types/{id}/items")
    @PreAuthorize("@platformAccess.isPlatformIdentity() and hasAuthority('system:dict:list')")
    public Result<List<ItemView>> items(@PathVariable long id) {
        return Result.ok(dictionary.listItems(id));
    }

    @PostMapping("/types")
    @PreAuthorize("@platformAccess.isPlatformIdentity() and hasAuthority('system:dict:type:add')")
    @Log(title = "字典类型", businessType = BusinessType.INSERT)
    public Result<Long> addType(@Valid @RequestBody TypeCreate request) {
        return Result.ok(dictionary.addType(request));
    }

    @PutMapping("/types/{id}")
    @PreAuthorize("@platformAccess.isPlatformIdentity() and hasAuthority('system:dict:type:edit')")
    @Log(title = "字典类型", businessType = BusinessType.UPDATE)
    public Result<Void> editType(@PathVariable long id, @Valid @RequestBody TypeEdit request) {
        dictionary.editType(id, request);
        return Result.ok();
    }

    @PostMapping("/types/{id}/items")
    @PreAuthorize("@platformAccess.isPlatformIdentity() and hasAuthority('system:dict:item:add')")
    @Log(title = "字典条目", businessType = BusinessType.INSERT)
    public Result<Long> addItem(@PathVariable long id, @Valid @RequestBody ItemCreate request) {
        return Result.ok(dictionary.addItem(id, request));
    }

    @PutMapping("/items/{id}")
    @PreAuthorize("@platformAccess.isPlatformIdentity() and hasAuthority('system:dict:item:edit')")
    @Log(title = "字典条目", businessType = BusinessType.UPDATE)
    public Result<Void> editItem(@PathVariable long id, @Valid @RequestBody ItemEdit request) {
        dictionary.editItem(id, request);
        return Result.ok();
    }
}
