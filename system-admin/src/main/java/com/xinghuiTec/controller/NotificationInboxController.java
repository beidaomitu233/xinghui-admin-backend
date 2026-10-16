package com.xinghuiTec.controller;

import com.xinghuiTec.notification.NotificationInboxService;
import com.xinghuiTec.notification.NotificationInboxService.Item;
import com.xinghuiTec.notification.NotificationInboxService.Page;
import com.xinghuiTec.utils.Result;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotEmpty;
import jakarta.validation.constraints.Positive;
import jakarta.validation.constraints.Size;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.*;

import java.util.List;

import static com.xinghuiTec.emues.ResultCodeEnum.ADMIN_ACCESS_FORBIDDEN;

/** Personal inbox: every operation scopes itself to the authenticated owner and tenant. */
@RestController
@RequestMapping("/notifications")
@PreAuthorize("isAuthenticated()")
public class NotificationInboxController {
    private final NotificationInboxService inbox;

    public NotificationInboxController(NotificationInboxService inbox) {
        this.inbox = inbox;
    }

    @ExceptionHandler(AccessDeniedException.class)
    public ResponseEntity<Result<Void>> denied() {
        return ResponseEntity.status(HttpStatus.FORBIDDEN).body(Result.fail(ADMIN_ACCESS_FORBIDDEN));
    }

    @GetMapping
    public Result<Page> page(@RequestParam(defaultValue = "1") int pageNum,
                             @RequestParam(defaultValue = "20") int pageSize,
                             @RequestParam(defaultValue = "false") boolean unreadOnly) {
        return Result.ok(inbox.page(pageNum, pageSize, unreadOnly));
    }

    @GetMapping("/unread-count")
    public Result<Long> unreadCount() {
        return Result.ok(inbox.unreadCount());
    }

    @GetMapping("/{id}")
    public Result<Item> detail(@PathVariable long id) {
        return Result.ok(inbox.detail(id));
    }

    @PutMapping("/{id}/read")
    public Result<Void> markRead(@PathVariable long id) {
        inbox.markRead(id);
        return Result.ok();
    }

    public record ReadBatch(@NotEmpty @Size(max = 100) List<@Positive Long> ids) {}

    @PutMapping("/read")
    public Result<Integer> markReadBatch(@Valid @RequestBody ReadBatch request) {
        return Result.ok(inbox.markReadBatch(request.ids()));
    }
}
