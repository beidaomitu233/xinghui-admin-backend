package com.xinghuiTec.controller;

import com.xinghuiTec.annotation.Log;
import com.xinghuiTec.emues.BusinessType;
import com.xinghuiTec.notification.NotificationTemplateService;
import com.xinghuiTec.notification.NotificationTemplateService.Create;
import com.xinghuiTec.notification.NotificationTemplateService.Edit;
import com.xinghuiTec.notification.NotificationTemplateService.View;
import com.xinghuiTec.utils.Result;
import jakarta.validation.Valid;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.*;

import java.util.List;

import static com.xinghuiTec.emues.ResultCodeEnum.ADMIN_ACCESS_FORBIDDEN;

/** Global template configuration; no public event-send endpoint is exposed. */
@RestController
@RequestMapping("/system/notification/templates")
public class NotificationTemplateAdminController {
    private final NotificationTemplateService templates;

    public NotificationTemplateAdminController(NotificationTemplateService templates) {
        this.templates = templates;
    }

    @ExceptionHandler(AccessDeniedException.class)
    public ResponseEntity<Result<Void>> denied() {
        return ResponseEntity.status(HttpStatus.FORBIDDEN).body(Result.fail(ADMIN_ACCESS_FORBIDDEN));
    }

    @GetMapping
    @PreAuthorize("@platformAccess.isPlatformIdentity() and hasAuthority('system:notificationTemplate:list')")
    public Result<List<View>> list() {
        return Result.ok(templates.list());
    }

    @PostMapping
    @PreAuthorize("@platformAccess.isPlatformIdentity() and hasAuthority('system:notificationTemplate:add')")
    @Log(title = "通知模板", businessType = BusinessType.INSERT)
    public Result<Long> create(@Valid @RequestBody Create request) {
        return Result.ok(templates.create(request));
    }

    @PutMapping("/{id}")
    @PreAuthorize("@platformAccess.isPlatformIdentity() and hasAuthority('system:notificationTemplate:edit')")
    @Log(title = "通知模板", businessType = BusinessType.UPDATE)
    public Result<Void> edit(@PathVariable long id, @Valid @RequestBody Edit request) {
        templates.edit(id, request);
        return Result.ok();
    }
}
