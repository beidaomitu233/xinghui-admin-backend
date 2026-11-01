package com.xinghuiTec.ai.agent;

import com.xinghuiTec.ai.application.AiInvocationIdentity;
import com.xinghuiTec.utils.Result;
import jakarta.servlet.http.HttpServletResponse;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.*;
import java.util.*;

@RestController
@RequestMapping("/system/ai/extensions")
public class AgentExtensionController {
    private final AgentExtensionService service;private final AiInvocationIdentity identity;
    public AgentExtensionController(AgentExtensionService service,AiInvocationIdentity identity){this.service=service;this.identity=identity;}
    @GetMapping @PreAuthorize("hasAnyAuthority('ai:config:query','ai:config:list')")
    public Result<List<AgentExtensionService.Entry>> list(){var rows=new ArrayList<>(service.builtins());rows.addAll(service.list(identity.current().tenantId()));return Result.ok(rows);}
    @PostMapping @PreAuthorize("hasAuthority('ai:config:edit')")
    public Result<AgentExtensionService.Entry> save(@RequestBody AgentExtensionService.Draft draft){return Result.ok(service.save(identity.current(),draft));}
    @PostMapping("/{id}/tools") @PreAuthorize("hasAuthority('ai:config:query')")
    public Result<List<Map<String,Object>>> discover(@PathVariable String id){return Result.ok(service.discover(identity.current(),id));}
    @GetMapping("/catalog") @PreAuthorize("hasAuthority('ai:agent:invoke')")
    public Result<List<Map<String,Object>>> catalog(){var actor=identity.current();return Result.ok(service.list(actor.tenantId()).stream().filter(row->service.available(actor,row)).map(row->Map.<String,Object>of("id",row.id(),"kind",row.kind(),"name",row.name(),"description",row.description(),"code",row.code())).toList());}
    @ModelAttribute public void noCache(HttpServletResponse response){response.setHeader("Cache-Control","no-store, private");}
}
