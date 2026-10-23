package com.xinghuiTec.lowcode;

import com.xinghuiTec.utils.Result;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.*;

@RestController
@RequestMapping("/lowcode/forms/{formId}/sharing")
public class LowcodeSharingController {
    private final LowcodeSharingService sharing;
    public LowcodeSharingController(LowcodeSharingService sharing) { this.sharing = sharing; }

    @GetMapping
    @PreAuthorize("@platformAccess.isPlatformIdentity() and hasAuthority('lowcode:form:publish')")
    public Result<LowcodeSharingService.ShareView> settings(@PathVariable long formId) {
        return Result.ok(sharing.settings(formId));
    }

    @PutMapping
    @PreAuthorize("@platformAccess.isPlatformIdentity() and hasAuthority('lowcode:form:publish')")
    public Result<LowcodeSharingService.ShareView> configure(@PathVariable long formId,
            @RequestBody LowcodeSharingService.ShareChange input) {
        return Result.ok(sharing.configure(formId, input));
    }
}
