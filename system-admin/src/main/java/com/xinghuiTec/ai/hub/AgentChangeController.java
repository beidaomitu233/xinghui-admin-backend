package com.xinghuiTec.ai.hub;

import com.xinghuiTec.agent.api.*;
import com.xinghuiTec.ai.agent.AgentAccess;
import com.xinghuiTec.ai.application.AiInvocationIdentity;
import com.xinghuiTec.utils.Result;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.*;
import org.springframework.http.ResponseEntity;
import java.util.*;

@RestController
@RequestMapping("/system/ai/hub")
@PreAuthorize("hasAuthority('ai:agent:invoke')")
public class AgentChangeController {
    private final AiInvocationIdentity identity;private final AgentAccess access;private final AgentChanges changes;
    public AgentChangeController(AiInvocationIdentity identity,AgentAccess access,AgentChanges changes){this.identity=identity;this.access=access;this.changes=changes;}
    private AgentActor actor(){var value=identity.current();return new AgentActor(value.tenantId(),value.subjectId());}
    @GetMapping("/sessions/{session}/changes") public Result<List<AgentChanges.View>> list(@PathVariable String session){var actor=actor();return access.as(actor,current->Result.ok(changes.list(actor,session)));}
    public record Decision(boolean approve) {}
    @PostMapping("/changes/{id}/decision") public Result<com.fasterxml.jackson.databind.JsonNode> decide(@PathVariable String id,@RequestBody Decision decision){var actor=actor();return access.as(actor,current->Result.ok(changes.decide(actor,id,decision.approve())));}
    @ExceptionHandler(AgentProblem.class) public ResponseEntity<Result<Void>> failed(AgentProblem error){return ResponseEntity.status(error.code().equals("FORBIDDEN")?403:409).body(Result.fail(409,error.getMessage()));}
    @ModelAttribute public void headers(jakarta.servlet.http.HttpServletResponse response){response.setHeader("Cache-Control","no-store");}
}
