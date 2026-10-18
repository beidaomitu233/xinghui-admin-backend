package com.xinghuiTec.ai.scene;

import com.xinghuiTec.ai.application.AiInvocationIdentity;
import com.xinghuiTec.ai.application.AiRuntimeBodyCodec;
import com.xinghuiTec.ai.application.AiRuntimeBoundaryFilter;
import com.xinghuiTec.utils.Result;
import jakarta.servlet.http.HttpServletRequest;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.*;

@RestController
@RequestMapping("/system/ai/settings/scene-routes")
public class SceneRoutingController {
    private final SceneManagementService scenes;
    private final AiInvocationIdentity identity;
    private final AiRuntimeBodyCodec codec;

    public SceneRoutingController(SceneManagementService scenes,AiInvocationIdentity identity,AiRuntimeBodyCodec codec) {
        this.scenes=scenes;this.identity=identity;this.codec=codec;
    }

    @GetMapping("/{sceneKey}")
    @PreAuthorize("@platformAccess.isPlatformIdentity() and hasAuthority('ai:config:query') and hasAuthority('ai:scene:query')")
    public Result<SceneRoutingDtos.View> get(@PathVariable String sceneKey) {
        return Result.ok(scenes.routing(sceneKey));
    }

    @PutMapping("/{sceneKey}")
    @PreAuthorize("@platformAccess.isPlatformIdentity() and hasAuthority('ai:config:edit') and hasAuthority('ai:scene:edit')")
    public Result<SceneRoutingDtos.Saved> save(@PathVariable String sceneKey,HttpServletRequest request) {
        var input=codec.readSceneConfiguration((byte[])request.getAttribute(AiRuntimeBoundaryFilter.BODY),SceneRoutingDtos.Change.class);
        return Result.ok(scenes.routing(sceneKey,input,identity.current()));
    }
}
