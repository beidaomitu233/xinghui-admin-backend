package com.xinghuiTec.lowcode;

import com.xinghuiTec.utils.Result;
import org.springframework.web.bind.annotation.*;

/** The only anonymous surface is this exact read-schema/submit pair, gated by the share service. */
@RestController
@RequestMapping("/lowcode/fill/{token}")
public class LowcodeFillController {
    private final LowcodeSharingService sharing;
    public LowcodeFillController(LowcodeSharingService sharing) { this.sharing = sharing; }

    @GetMapping
    public Result<LowcodeSharingService.FillView> view(@PathVariable String token) {
        return Result.ok(sharing.view(token));
    }

    @PostMapping
    public Result<LowcodeSharingService.FillReceipt> submit(@PathVariable String token,
            @RequestBody LowcodeSharingService.FillSubmission input) {
        return Result.ok(sharing.submit(token, input));
    }
}
