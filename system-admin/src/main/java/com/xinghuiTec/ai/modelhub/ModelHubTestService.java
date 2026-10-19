package com.xinghuiTec.ai.modelhub;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.xinghuiTec.ai.application.*;
import com.xinghuiTec.ai.contract.*;
import com.xinghuiTec.ai.contract.AiTypes.*;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.support.GeneratedKeyHolder;
import org.springframework.stereotype.Service;
import java.time.Instant;
import java.util.*;
import static com.xinghuiTec.ai.modelhub.ModelHubService.*;

/** Explicit paid test uses the same provider, retries, budgets, durable attempts and private output path. */
@Service
public class ModelHubTestService {
    private final ModelHubService models;
    private final AiApplicationReferences guard;
    private final AiApplicationStore applications;
    private final AiRequestStore requests;
    private final AiRequestLifecycle lifecycle;
    private final AiRuntimeService runtime;
    private final JdbcTemplate jdbc;
    private final ObjectMapper json;
    public ModelHubTestService(ModelHubService models,AiApplicationReferences guard,AiApplicationStore applications,AiRequestStore requests,AiRequestLifecycle lifecycle,AiRuntimeService runtime,JdbcTemplate jdbc,ObjectMapper json){this.models=models;this.guard=guard;this.applications=applications;this.requests=requests;this.lifecycle=lifecycle;this.runtime=runtime;this.jdbc=jdbc;this.json=json;}
    private record Admission(AiRequestStore.Request request,AiApplicationPolicy policy,boolean created) {}
    public ModelHubDtos.TestView start(String modelId,ModelHubDtos.Test input,TrustedActor actor){
        check(actor!=null&&actor.servicePrincipalRef()==null&&actor.authorities().contains("ai:config:test"),403,"FORBIDDEN");
        check(input!=null&&input.confirmedCosts()&&input.clientMutationId()!=null&&input.clientMutationId().matches("[a-fA-F0-9-]{36}"),400,"COST_CONFIRMATION_REQUIRED");id(modelId);
        var owner=new AiRequestStore.Owner(actor.subjectId(),actor.tenantId());String scope=actor.subjectId()+"@"+actor.tenantId();
        var admitted=guard.withReferenceGuard(()->{
            var prior=jdbc.queryForList("SELECT request_id,model_id,model_revision FROM sys_ai_business_model_request WHERE owner_scope=? AND test_mutation=?",scope,input.clientMutationId());
            if(!prior.isEmpty()){var p=prior.get(0);check(modelId.equals(p.get("model_id").toString())&&input.expectedRevision()==((Number)p.get("model_revision")).intValue(),409,"IDEMPOTENCY_CONFLICT");return new Admission(requests.owned(((Number)p.get("request_id")).longValue(),owner),null,false);}
            var model=models.get(modelId);check(model.revision()==input.expectedRevision(),409,"VERSION_CONFLICT");
            var base=new AiApplicationPolicy(AiApplicationPolicy.ModelMode.FIXED,List.of(),false,null,Set.of(),new Requirements(OutputFormat.TEXT,true,false),AiApplicationPolicy.Scope.FRONTEND,Set.of(actor.subjectId()),Set.of(),null,120,8,2,256,model.slug());
            var frozen=models.freeze(base);var policy=frozen.policy();
            String app=applications.create(new AiApplicationStore.Draft("业务模型测试","明确确认费用的真实调用","internal.modelhub/"+UUID.randomUUID(),policy),Long.parseLong(actor.subjectId()));
            final String snapshot;try{snapshot=json.writeValueAsString(new AiApplicationService.Snapshot(policy,List.of(),List.of()));}catch(Exception invalid){throw new com.xinghuiTec.ai.scene.SceneFailure(503,"STATE_UNAVAILABLE");}
            var keys=new GeneratedKeyHolder();jdbc.update(c->{var s=c.prepareStatement("INSERT INTO sys_ai_application_version(app_id,version_number,draft_revision,snapshot_json,created_by,snapshot_source) VALUES(?,1,1,?,?,'MODEL_HUB_TEST')",new String[]{"app_version_id"});s.setLong(1,Long.parseLong(app));s.setString(2,snapshot);s.setLong(3,Long.parseLong(actor.subjectId()));return s;},keys);
            long version=Objects.requireNonNull(keys.getKey()).longValue();jdbc.update("INSERT INTO sys_ai_application_cursor(app_version_id,next_index) VALUES(?,0)",version);
            final String fingerprint;try{fingerprint=HexFormat.of().formatHex(java.security.MessageDigest.getInstance("SHA-256").digest((input.expectedRevision()+":"+modelId).getBytes(java.nio.charset.StandardCharsets.UTF_8)));}catch(java.security.NoSuchAlgorithmException impossible){throw new IllegalStateException(impossible);}
            var now=Instant.now();var accepted=requests.accept(new AiRequestStore.Admission(Long.parseLong(app),version,null,AiRequestStore.Kind.TEST,owner,AiRequestStore.MODEL_HUB_TEST_TYPE,modelId,input.clientMutationId(),fingerprint,now.plusSeconds(120)),now);
            models.pin(Long.parseLong(accepted.request().requestId()),frozen,actor,true);jdbc.update("UPDATE sys_ai_business_model_request SET test_mutation=? WHERE request_id=?",input.clientMutationId(),accepted.request().requestId());
            return new Admission(accepted.request(),policy,true);
        });
        if(admitted.created()){
            long requestId=Long.parseLong(admitted.request().requestId());var invocation=new Invocation(actor,admitted.request().appId(),admitted.request().appVersionId(),new BusinessRef(AiRequestStore.MODEL_HUB_TEST_TYPE,modelId),admitted.request().requestId());
            lifecycle.accepted(requestId,owner);
            try{runtime.dispatchPrivate(new AiRequestExecutor.Work(invocation,admitted.policy(),List.of(new AiMessage(MessageRole.USER,"Reply with OK.")),List.of(),Set.of(),true,()->{},ignored->{}),()->{});}
            catch(AiRuntimeException failure){lifecycle.finish(requestId,owner,AiRequestStatus.FAILED,failure.code(),null);throw failure;}
        }
        return get(modelId,admitted.request().requestId(),actor);
    }
    public ModelHubDtos.TestView cancel(String modelId,String requestId,TrustedActor actor){
        get(modelId,requestId,actor); // Reuse the exact model, owner, tenant and current action permission gate.
        lifecycle.finish(id(requestId),new AiRequestStore.Owner(actor.subjectId(),actor.tenantId()),AiRequestStatus.CANCELLED,ErrorCode.CANCELLED,null);
        return get(modelId,requestId,actor); // Terminal rows are immutable: duplicate cancel and completed tests remain stable.
    }
    public ModelHubDtos.TestView get(String modelId,String requestId,TrustedActor actor){
        id(modelId);id(requestId);check(actor!=null&&actor.servicePrincipalRef()==null&&actor.authorities().contains("ai:config:test"),403,"FORBIDDEN");
        Long matches=jdbc.queryForObject("SELECT COUNT(*) FROM sys_ai_business_model_request WHERE request_id=? AND model_id=? AND owner_scope=? AND test_request=TRUE",Long.class,id(requestId),id(modelId),actor.subjectId()+"@"+actor.tenantId());check(matches!=null&&matches==1L,404,"NOT_FOUND");
        var r=requests.owned(id(requestId),new AiRequestStore.Owner(actor.subjectId(),actor.tenantId()));
        var attempts=jdbc.query("SELECT attempt_number,provider_code,model_code,status,error_code,switch_reason,create_time,finish_time FROM sys_ai_request_attempt WHERE request_id=? ORDER BY attempt_number",(row,n)->{
            var start=row.getTimestamp("create_time");var finish=row.getTimestamp("finish_time");Long elapsed=start==null||finish==null?null:Math.max(0,finish.getTime()-start.getTime());
            return new ModelHubDtos.TestAttempt(row.getInt("attempt_number"),row.getString("provider_code"),row.getString("model_code"),row.getString("status"),row.getString("error_code"),row.getString("switch_reason"),elapsed);
        },id(requestId));
        return new ModelHubDtos.TestView(r.requestId(),r.status().name(),r.errorCode(),r.attemptCount(),r.deadline(),attempts);
    }

    /** Same owner/model/action gate as the private test view, without loading any response. */
    public void authorizeCallLog(String requestId,TrustedActor actor){
        id(requestId);check(actor!=null&&actor.servicePrincipalRef()==null&&actor.authorities().contains("ai:config:test"),403,"FORBIDDEN");
        Long matches=jdbc.queryForObject("SELECT COUNT(*) FROM sys_ai_business_model_request WHERE request_id=? AND owner_scope=? AND test_request=TRUE",Long.class,id(requestId),actor.subjectId()+"@"+actor.tenantId());
        check(matches!=null&&matches==1L,404,"NOT_FOUND");requests.owned(id(requestId),new AiRequestStore.Owner(actor.subjectId(),actor.tenantId()));
    }
}
