package com.xinghuiTec.ai.hub;

import com.fasterxml.jackson.databind.*;
import com.fasterxml.jackson.databind.node.*;
import com.xinghuiTec.agent.api.*;
import com.xinghuiTec.agent.persistence.JdbcAgentRepository;
import com.xinghuiTec.ai.agent.*;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.ColumnMapRowMapper;
import org.springframework.jdbc.core.RowMapper;
import org.springframework.stereotype.Service;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;
import org.springframework.transaction.support.TransactionSynchronizationManager;
import java.sql.Timestamp;
import java.time.Instant;
import java.util.*;

/** Confirmation is a host-side business transaction, never a model tool or a client-supplied flag. */
@Service
public class AgentChanges {
    // Connector/J exposes DATETIME as LocalDateTime through getObject; use the JDBC
    // timestamp accessor so connection time-zone handling stays with the driver.
    private static final RowMapper<Map<String,Object>> EXPIRY_ROW=(rs,n)->{
        var row=new ColumnMapRowMapper().mapRow(rs,n);
        row.put("expires_at",rs.getTimestamp("expires_at"));
        return row;
    };
    public record View(String id,String runId,String resource,String status,JsonNode preview,JsonNode result,Instant expiresAt) {}
    private final JdbcTemplate jdbc;private final TransactionTemplate tx;private final AgentPayloadCipher cipher;
    private final AgentResourceRegistry resources;private final AgentAccess access;private final JdbcAgentRepository repository;private final ObjectMapper json;
    @org.springframework.beans.factory.annotation.Autowired(required=false) private AgentOperationLogs operationLogs;
    public AgentChanges(JdbcTemplate jdbc,PlatformTransactionManager transactions,AgentPayloadCipher cipher,AgentResourceRegistry resources,AgentAccess access,JdbcAgentRepository repository,ObjectMapper json){
        this.jdbc=jdbc;this.tx=new TransactionTemplate(transactions);this.cipher=cipher;this.resources=resources;this.access=access;this.repository=repository;this.json=json;
    }
    public JsonNode preview(AgentCall call,String toolId,String resource,JsonNode arguments){
        call.check();if(toolId==null||toolId.length()>160||!arguments.isObject()||arguments.toString().length()>65536)throw new AgentProblem("INVALID_ARGUMENTS","变更参数无效");
        return access.as(call.actor(),current->{
            var definition=resources.describe(call,resource);
            if(!arguments.path("operation").isTextual()||arguments.path("operation").asText().isBlank())
                throw new AgentProblem("INVALID_ARGUMENTS","arguments.operation必填；请按describe返回的mutation结构填写其他参数，再生成具体变更预览。");
            String operation=arguments.path("operation").asText();
            if(!definition.path("operations").isArray()||java.util.stream.StreamSupport.stream(definition.path("operations").spliterator(),false).noneMatch(v->v.asText().equals(operation)))throw new AgentProblem("FORBIDDEN","当前账号不能执行此操作");
            var preview=json.createObjectNode().put("name",definition.path("name").asText(resource)).put("resource",resource).put("operation",operation);
            preview.set("changes",redact(arguments));
            preview.set("definition",redact(definition));
            var before=resources.changeSnapshot(call,resource,arguments);
            if(before!=null&&!before.isNull())preview.set("before",redact(before));
            String id=UUID.randomUUID().toString();Instant now=Instant.now();
            return tx.execute(status->{
                repository.guard(call.actor(),call.runId(),false);
                var prior=jdbc.queryForList("SELECT change_id,arguments_payload FROM sys_agent_change WHERE run_id=? AND tool_call_id=?",call.runId(),toolId);
                if(!prior.isEmpty()){
                    String found=(String)prior.get(0).get("change_id");
                    if(!cipher.open(purpose(call.actor(),found,"arguments"),(String)prior.get(0).get("arguments_payload")).equals(arguments.toString()))throw new AgentProblem("IDEMPOTENCY_CONFLICT","同一次操作不能替换变更内容");
                    return json.createObjectNode().put("changeId",found).put("status","AWAITING_CONFIRMATION");
                }
                var waiting=jdbc.queryForList("SELECT change_id,resource_id,arguments_payload FROM sys_agent_change WHERE run_id=? AND status='PENDING' ORDER BY created_at LIMIT 1",call.runId());
                if(!waiting.isEmpty()){
                    var pending=waiting.get(0);String found=(String)pending.get("change_id");
                    if(resource.equals(pending.get("resource_id"))&&read(cipher.open(purpose(call.actor(),found,"arguments"),(String)pending.get("arguments_payload"))).equals(arguments))
                        return json.createObjectNode().put("changeId",found).put("status","AWAITING_CONFIRMATION");
                    throw new AgentProblem("CHANGE_PENDING","已有具体变更等待用户在页面确认，请等待处理后再提交其他变更。");
                }
                jdbc.update("INSERT INTO sys_agent_change(change_id,tenant_id,user_id,session_id,run_id,tool_call_id,resource_id,arguments_payload,preview_payload,status,created_at,expires_at) VALUES(?,?,?,?,?,?,?,?,?,?,?,?)",
                    id,call.actor().tenantId(),call.actor().userId(),call.sessionId(),call.runId(),toolId,resource,cipher.seal(purpose(call.actor(),id,"arguments"),arguments.toString()),cipher.seal(purpose(call.actor(),id,"preview"),preview.toString()),"PENDING",Timestamp.from(now),Timestamp.from(call.deadline().isBefore(now.plusSeconds(900))?call.deadline():now.plusSeconds(900)));
                repository.reference(call.actor(),call.sessionId(),resource);
                repository.append(call.actor(),call.runId(),new AgentEventView("CHANGE_PREVIEW",Map.of("changeId",id,"resource",resource,"preview",preview)));
                return json.createObjectNode().put("changeId",id).put("status","AWAITING_CONFIRMATION").set("preview",preview);
            });
        });
    }
    /** A pending tool pauses outside a transaction; the agent continues with the actual business receipt. */
    public JsonNode awaitDecision(AgentCall call,String id){
        if(TransactionSynchronizationManager.isActualTransactionActive())throw new IllegalStateException("Never wait for approval inside a transaction");
        for(;;){
            call.check();
            JsonNode receipt=access.as(call.actor(),current->{
                if(!repository.running(call.actor(),call.runId()))throw new AgentProblem("RUN_STOPPED","任务已停止");
                var rows=jdbc.query("SELECT resource_id,status,result_payload,expires_at FROM sys_agent_change WHERE change_id=? AND tenant_id=? AND user_id=? AND run_id=?",EXPIRY_ROW,id,call.actor().tenantId(),call.actor().userId(),call.runId());
                if(rows.size()!=1)throw new AgentProblem("FORBIDDEN","无权访问此变更");
                var row=rows.get(0);resources.requireReadable(call.actor(),Set.of((String)row.get("resource_id")));
                String status=(String)row.get("status");
                if(!"PENDING".equals(status)){
                    var result=json.createObjectNode().put("changeId",id).put("status",status);
                    if(row.get("result_payload")!=null)result.set("result",read(cipher.open(purpose(call.actor(),id,"result"),(String)row.get("result_payload"))));
                    return result;
                }
                if(!Instant.now().isBefore(((Timestamp)row.get("expires_at")).toInstant()))throw new AgentProblem("CHANGE_EXPIRED","变更已过期，请重新生成预览");
                return null;
            });
            if(receipt!=null)return receipt;
            try{Thread.sleep(300);}catch(InterruptedException interrupted){Thread.currentThread().interrupt();throw new AgentProblem("RUN_STOPPED","任务已停止");}
        }
    }
    public List<View> list(AgentActor actor,String session){
        repository.session(actor,session);
        return jdbc.query("SELECT c.*,r.status run_status FROM sys_agent_change c JOIN sys_agent_run r ON r.run_id=c.run_id WHERE c.tenant_id=? AND c.user_id=? AND c.session_id=? ORDER BY c.created_at DESC LIMIT 100",(rs,n)->{
            String id=rs.getString("change_id"),status=rs.getString("status");Instant expires=rs.getTimestamp("expires_at").toInstant();
            if("PENDING".equals(status)){if(!Instant.now().isBefore(expires))status="EXPIRED";else if(!"RUNNING".equals(rs.getString("run_status")))status="CANCELLED";}
            return new View(id,rs.getString("run_id"),rs.getString("resource_id"),status,read(cipher.open(purpose(actor,id,"preview"),rs.getString("preview_payload"))),rs.getString("result_payload")==null?null:read(cipher.open(purpose(actor,id,"result"),rs.getString("result_payload"))),expires);
        },actor.tenantId(),actor.userId(),session);
    }
    public JsonNode decide(AgentActor actor,String id,boolean approve){
        return access.as(actor,current->{
            var owner=jdbc.queryForList("SELECT run_id,session_id,resource_id,arguments_payload FROM sys_agent_change WHERE change_id=? AND tenant_id=? AND user_id=?",id,actor.tenantId(),actor.userId());
            if(owner.size()!=1)throw new AgentProblem("FORBIDDEN","无权确认此变更");
            var target=owner.get(0);String run=(String)target.get("run_id"),session=(String)target.get("session_id"),resource=(String)target.get("resource_id");
            var args=read(cipher.open(purpose(actor,id,"arguments"),(String)target.get("arguments_payload")));
            var call=new AgentCall(actor,session,run,"confirm:"+id,Instant.now().plusSeconds(60),()->false);long started=System.nanoTime();
            try{return tx.execute(status->{
            // Same lock order as cancellation: session, run, change. No locks are held during user wait.
            repository.guard(actor,run,false);
            var rows=jdbc.query("SELECT * FROM sys_agent_change WHERE change_id=? AND tenant_id=? AND user_id=? FOR UPDATE",EXPIRY_ROW,id,actor.tenantId(),actor.userId());
            if(rows.size()!=1)throw new AgentProblem("FORBIDDEN","无权确认此变更");var row=rows.get(0);
            if(!"PENDING".equals(row.get("status")))return json.createObjectNode().put("status",String.valueOf(row.get("status")));
            if(((Timestamp)row.get("expires_at")).toInstant().isBefore(Instant.now()))throw new AgentProblem("CHANGE_EXPIRED","变更已过期，请重新生成预览");
            JsonNode result=json.createObjectNode().put("status","REJECTED");String next="REJECTED";
            if(approve){
                var preview=read(cipher.open(purpose(actor,id,"preview"),(String)row.get("preview_payload")));
                var before=resources.changeSnapshot(call,resource,args);
                if(preview.has("before")&&!preview.get("before").equals(redact(before)))throw new AgentProblem("VERSION_CONFLICT","记录已变化，请重新查询并生成变更预览");
                // Original services recheck permissions, target ownership, revision and all business constraints.
                result=resources.mutate(call,call.callId(),resource,args);next="EXECUTED";
            }
            jdbc.update("UPDATE sys_agent_change SET status=?,result_payload=?,decided_at=? WHERE change_id=? AND status='PENDING'",next,cipher.seal(purpose(actor,id,"result"),redact(result).toString()),Timestamp.from(Instant.now()),id);
            audit(call,approve?"confirm_execute":"confirm_reject",resource,args,next,id,elapsed(started),result,null);
            return json.createObjectNode().put("status",next).set("result",redact(result));
            });}catch(AgentProblem failure){
                if(!Set.of("BUSINESS_VALIDATION","VERSION_CONFLICT","INVALID_ARGUMENTS").contains(failure.code()))throw failure;
                // The failed business transaction rolled back. Persist a receipt separately so the waiting agent can replan.
                return tx.execute(status->{
                    repository.guard(actor,run,false);
                    var rows=jdbc.queryForList("SELECT status FROM sys_agent_change WHERE change_id=? FOR UPDATE",id);
                    if(!"PENDING".equals(rows.get(0).get("status")))return json.createObjectNode().put("status",String.valueOf(rows.get(0).get("status")));
                    var result=json.createObjectNode().put("code",failure.code()).put("message",failure.getMessage());
                    jdbc.update("UPDATE sys_agent_change SET status='FAILED',result_payload=?,decided_at=? WHERE change_id=? AND status='PENDING'",cipher.seal(purpose(actor,id,"result"),result.toString()),Timestamp.from(Instant.now()),id);
                    audit(call,"confirm_execute",resource,args,"FAILED",id,elapsed(started),null,failure.code());
                    return json.createObjectNode().put("status","FAILED").set("result",result);
                });
            }
        });
    }
    public void audit(AgentCall call,String operation,String resource,JsonNode arguments,String status,String change){
        audit(call,operation,resource,arguments,status,change,0,null,null);
    }
    public void audit(AgentCall call,String operation,String resource,JsonNode arguments,String status,String change,long duration,JsonNode result,String error){
        try {String digest=HexFormat.of().formatHex(java.security.MessageDigest.getInstance("SHA-256").digest(arguments.toString().getBytes(java.nio.charset.StandardCharsets.UTF_8)));
            var targets=new LinkedHashSet<String>();targetIds(arguments,targets);if(result!=null){targetIds(result,targets);if(result.isIntegralNumber())targetId(result,targets);}
            jdbc.update("INSERT INTO sys_agent_tool_audit(tenant_id,user_id,session_id,run_id,tool_call_id,operation,resource_id,arguments_hash,status,change_id,created_at,target_ids_json,duration_ms,error_code) VALUES(?,?,?,?,?,?,?,?,?,?,?,?,?,?)",call.actor().tenantId(),call.actor().userId(),call.sessionId(),call.runId(),call.callId(),operation,resource,digest,status,change,Timestamp.from(Instant.now()),json.valueToTree(targets).toString(),duration,error);
            if(operationLogs!=null&&"confirm_execute".equals(operation))operationLogs.record(call,resource,arguments.path("operation").asText(),status,targets,duration,error);
        }catch(java.security.NoSuchAlgorithmException impossible){throw new IllegalStateException(impossible);}
    }
    private JsonNode redact(JsonNode value){
        if(value==null)return NullNode.instance;
        if(value.isObject()){var copy=json.createObjectNode();value.fields().forEachRemaining(e->copy.set(e.getKey(),e.getKey().toLowerCase(Locale.ROOT).matches(".*(password|secret|token|credential).*")?TextNode.valueOf("已隐藏"):redact(e.getValue())));return copy;}
        if(value.isArray()){var copy=json.createArrayNode();value.forEach(v->copy.add(redact(v)));return copy;}return value.deepCopy();
    }
    private void targetIds(JsonNode value,Set<String> ids){
        if(ids.size()>=100)return;
        if(value.isObject())value.fields().forEachRemaining(e->{
            if(Set.of("id","ids","configId","userId","roleId","noticeId","jobId","typeId").contains(e.getKey())){if(e.getValue().isArray())e.getValue().forEach(v->targetId(v,ids));else targetId(e.getValue(),ids);}
            else if(Set.of("records","items","user","role","notice").contains(e.getKey()))targetIds(e.getValue(),ids);
        });else if(value.isArray())value.forEach(v->targetIds(v,ids));
    }
    private void targetId(JsonNode value,Set<String> ids){if(ids.size()<100&&value.isValueNode()&&value.asText().matches("[A-Za-z0-9_.:-]{1,80}"))ids.add(value.asText());}
    private static long elapsed(long started){return Math.max(0,(System.nanoTime()-started)/1_000_000);}
    private JsonNode read(String value){try{return json.readTree(value);}catch(Exception invalid){throw new IllegalStateException("Invalid change state",invalid);}}
    private static String purpose(AgentActor actor,String id,String field){return "hub-change:"+actor.stateUserId()+":"+id+":"+field;}
}
