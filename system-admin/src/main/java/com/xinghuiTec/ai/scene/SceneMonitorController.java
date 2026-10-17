package com.xinghuiTec.ai.scene;

import com.xinghuiTec.ai.application.AiInvocationIdentity;
import com.xinghuiTec.security.PlatformAccessService;
import com.xinghuiTec.utils.Result;
import jakarta.servlet.http.HttpServletResponse;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.*;
import java.time.Instant;

@RestController
@RequestMapping("/system/ai/monitor")
public class SceneMonitorController {
    private final SceneMonitorService service;private final AiInvocationIdentity identity;private final PlatformAccessService platform;
    public SceneMonitorController(SceneMonitorService service,AiInvocationIdentity identity,PlatformAccessService platform){this.service=service;this.identity=identity;this.platform=platform;}
    @GetMapping("/summary") @PreAuthorize("@aiWorkspaceAccess.canEnterMonitor()")
    public Result<SceneDtos.Monitor> summary(@RequestParam(defaultValue="OWN")String scope,@RequestParam(defaultValue="BUSINESS")String requestKind,
            @RequestParam(required=false)String from,@RequestParam(required=false)String to,@RequestParam(required=false)String sceneKey,
            @RequestParam(required=false)String modelRef,@RequestParam(required=false)String result){
        return Result.ok(service.summary(identity.current(),platform.isPlatformIdentity(),scope,requestKind,from==null?null:Instant.parse(from),to==null?null:Instant.parse(to),sceneKey,modelRef,result));
    }
    @ModelAttribute public void noCache(HttpServletResponse response){response.setHeader("Cache-Control","no-store, private");response.setHeader("X-Content-Type-Options","nosniff");}
}
