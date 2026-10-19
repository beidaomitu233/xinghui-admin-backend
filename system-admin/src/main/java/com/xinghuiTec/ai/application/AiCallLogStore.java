package com.xinghuiTec.ai.application;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.DeserializationFeature;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.xinghuiTec.ai.contract.AiCallTelemetry;
import com.xinghuiTec.ai.contract.AiProviderRuntime.ExecutionOutcome;
import com.xinghuiTec.ai.contract.AiTypes.TrustedActor;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

import java.sql.Timestamp;
import java.time.Instant;
import java.util.*;
import java.util.concurrent.atomic.LongAdder;

/** Internal server-only observations. Logging failures must never replay a provider call. */
@Repository
public class AiCallLogStore {
    private static final Logger LOG=LoggerFactory.getLogger(AiCallLogStore.class);
    private final JdbcTemplate jdbc;
    private final TransactionTemplate transaction;
    private final ObjectMapper json;
    private final LongAdder failures=new LongAdder();
    public AiCallLogStore(JdbcTemplate jdbc,PlatformTransactionManager manager,ObjectMapper json){
        this.jdbc=jdbc;this.transaction=new TransactionTemplate(manager);
        this.json=json.copy().enable(DeserializationFeature.USE_LONG_FOR_INTS);
    }
    public long archiveFailures(){return failures.sum();}
    private void safe(Runnable operation){try{operation.run();}catch(RuntimeException unavailable){
        failures.increment();LOG.warn("AI_CALL_ARCHIVE_FAILED count={}",failures.sum());
    }}
    public void beginAttempt(long requestId,String attemptId){safe(()->attempt(requestId,attemptId));}
    private String attempt(long requestId,String attemptId){
        var rows=jdbc.query("SELECT r.tenant_ref,r.subject_ref,r.owner_scope,r.request_kind,r.scene_key,r.app_id,"
                +"a.name AS app_name,c.config_name,t.* FROM sys_ai_request_attempt t JOIN sys_ai_request r ON r.request_id=t.request_id"
                +" JOIN sys_ai_application a ON a.app_id=r.app_id LEFT JOIN sys_ai_configuration_version v ON v.version_id=t.config_version"
                +" LEFT JOIN sys_ai_configuration c ON c.config_id=v.config_id WHERE t.attempt_id=? AND r.request_id=?",
                (rs,n)->{var row=new org.springframework.jdbc.core.ColumnMapRowMapper().mapRow(rs,n);row.put("create_time",rs.getTimestamp("create_time"));return row;},Long.parseLong(attemptId),requestId);
        if(rows.size()!=1)throw new IllegalStateException("AI_CALL_ASSOCIATION_UNAVAILABLE");
        var r=rows.get(0);var info=blank();
        info.put("requestKind",r.get("request_kind"));info.put("sceneKey",r.get("scene_key"));
        info.put("appId",r.get("app_id").toString());info.put("appName",r.get("app_name"));
        info.put("modelCode",r.get("model_code"));info.put("maskedKeyLabel",r.get("masked_key_label"));info.put("configName",r.get("config_name"));
        info.put("attemptNumber",r.get("attempt_number"));info.put("switchReason",r.get("switch_reason"));
        var owner=new AiRequestStore.Owner((String)r.get("subject_ref"),(String)r.get("tenant_ref"));
        String id=insert(attemptId,Long.toString(requestId),null,null,"REQUEST_ATTEMPT",owner,(String)r.get("model_ref"),
                (String)r.get("provider_code"),((Timestamp)r.get("create_time")).toInstant(),info);
        return id;
    }
    public void completeAttempt(long requestId,String attemptId,ExecutionOutcome outcome){safe(()->{
        String id=attempt(requestId,attemptId);complete(id,outcome);
    });}
    public String beginConfigurationPair(long testId,long pairId,String modelRef,String modelCode,String provider,String label){
        final String[] id={null};safe(()->{
            Long matches=jdbc.queryForObject("SELECT COUNT(*) FROM sys_ai_configuration_test_pair WHERE pair_id=? AND test_id=? AND model_ref=?",Long.class,pairId,testId,Long.parseLong(modelRef));
            if(matches==null||matches!=1)throw new IllegalStateException("AI_CALL_ASSOCIATION_UNAVAILABLE");
            var actor=jdbc.queryForMap("SELECT t.initiator_subject_ref,t.initiator_tenant_ref,c.config_name FROM sys_ai_configuration_test t JOIN sys_ai_configuration c ON c.config_id=t.config_id WHERE t.test_id=?",testId);
            // Pre-migration test runs have no trusted initiator and are never attributed to a worker identity.
            if(actor.get("initiator_subject_ref")==null||actor.get("initiator_tenant_ref")==null)return;
            var owner=new AiRequestStore.Owner((String)actor.get("initiator_subject_ref"),(String)actor.get("initiator_tenant_ref"));
            var info=blank();info.put("modelCode",modelCode);info.put("maskedKeyLabel",label);info.put("configName",actor.get("config_name"));
            id[0]=insert(null,null,Long.toString(testId),Long.toString(pairId),"CONFIGURATION_TEST",owner,modelRef,provider,Instant.now(),info);
        });return id[0];
    }
    public void completeConfigurationPair(String callId,ExecutionOutcome outcome){if(callId!=null)safe(()->complete(callId,outcome));}
    public String beginDiscovery(TrustedActor actor,String provider){
        if(actor==null)return null;final String[] id={null};safe(()->id[0]=insert(null,null,null,null,"MODEL_DISCOVERY",
                AiRuntimeService.owner(actor),null,provider,Instant.now(),blank()));return id[0];
    }
    public void completeDiscovery(String callId,boolean success,long elapsedMs){if(callId!=null)safe(()->{
        var info=metadata(callId);info.put("elapsedMs",elapsedMs);info.put("captureReason","DISCOVERY_METADATA_ONLY");
        jdbc.update("UPDATE sys_ai_call_log SET status=?,finished_at=?,metadata_json=? WHERE call_id=? AND finished_at IS NULL",
                success?"SUCCEEDED":"FAILED",Timestamp.from(Instant.now()),encode(info),callId);
    });}
    private String insert(String attempt,String request,String test,String pair,String entry,AiRequestStore.Owner owner,
                          String model,String provider,Instant started,Map<String,Object> info){
        String id=UUID.randomUUID().toString();
        try{jdbc.update("INSERT INTO sys_ai_call_log(call_id,attempt_id,request_id,configuration_test_id,configuration_pair_id,"
                        +"entry_type,owner_scope,tenant_ref,subject_ref,model_ref,provider_code,status,started_at,metadata_json)"
                        +" VALUES(?,?,?,?,?,?,?,?,?,?,?,'RUNNING',?,?)",id,number(attempt),number(request),number(test),number(pair),entry,
                owner.scope(),owner.tenantRef(),owner.subjectRef(),model,provider,Timestamp.from(started),encode(info));}
        catch(org.springframework.dao.DuplicateKeyException duplicate){
            if(attempt!=null)return jdbc.queryForObject("SELECT call_id FROM sys_ai_call_log WHERE attempt_id=?",String.class,number(attempt));
            if(pair!=null)return jdbc.queryForObject("SELECT call_id FROM sys_ai_call_log WHERE configuration_pair_id=?",String.class,number(pair));
            throw duplicate;
        }return id;
    }
    private void complete(String id,ExecutionOutcome outcome){
        try{transaction.executeWithoutResult(tx->{
            var rows=jdbc.queryForList("SELECT finished_at FROM sys_ai_call_log WHERE call_id=? FOR UPDATE",id);
            if(rows.size()!=1||rows.get(0).get("finished_at")!=null)return;
            var info=metadata(id);AiCallTelemetry t=outcome.telemetry();boolean response=false;
            String capture="NOT_CAPTURED";Instant finished=Instant.now();Long input=null,output=null;
            info.put("errorCode",outcome.errorCode()==null?null:outcome.errorCode().name());
            info.put("providerRequestId",safeId(outcome.providerRequestId()));
            if(t!=null){
                info.put("streaming",t.streaming());info.put("elapsedMs",t.elapsedMs());info.put("responseHeadersMs",t.responseHeadersMs());
                info.put("firstByteMs",t.firstByteMs());info.put("firstOutputMs",t.firstOutputMs());info.put("firstTextMs",t.firstTextMs());
                info.put("lastOutputMs",t.lastOutputMs());info.put("firstOutputKind",t.firstOutputKind());info.put("finishReason",t.finishReason());
                info.put("returnedModel",t.returnedModel());info.put("httpStatus",t.httpStatus());
                info.put("totalTokens",t.totalTokens());info.put("cacheReadTokens",t.cacheReadTokens());
                info.put("cacheWriteTokens",t.cacheWriteTokens());info.put("reasoningTokens",t.reasoningTokens());
                boolean costSupported=supportedCost(t.reportedCost());
                info.put("reportedCost",costSupported&&t.reportedCost()!=null?t.reportedCost().toPlainString():null);info.put("currency",t.currency());
                info.put("captureReason",t.captureReason());info.put("redacted",t.redacted());info.put("truncated",t.truncated());
                capture=t.captureStatus();finished=t.finishedAt()==null?finished:t.finishedAt();input=t.inputTokens();output=t.outputTokens();
                if(!costSupported){info.put("captureReason","UNSUPPORTED_COST");if("COMPLETE".equals(capture))capture="PARTIAL";}
                response=t.responseFormat()!=null&&(t.responseBody()!=null||t.usageJson()!=null||t.responseHeadersJson()!=null);
                if(response){
                    // DTO construction enforces a 256KiB total. Only already-scrubbed transport data enters this table.
                    jdbc.update("INSERT INTO sys_ai_call_response(call_id,response_format,response_body,headers_json,usage_json,received_bytes,archived_bytes) VALUES(?,?,?,?,?,?,?)",
                            id,t.responseFormat(),t.responseBody(),t.responseHeadersJson(),t.usageJson(),t.receivedBytes(),t.archivedBytes());
                }
                if(t.startedAt()!=null)jdbc.update("UPDATE sys_ai_call_log SET started_at=? WHERE call_id=?",Timestamp.from(t.startedAt()),id);
            }else{
                info.put("captureReason","TELEMETRY_UNAVAILABLE");
                if(outcome.usage()!=null){input=outcome.usage().inputTokens();output=outcome.usage().outputTokens();info.put("totalTokens",outcome.usage().totalTokens());}
            }
            jdbc.update("UPDATE sys_ai_call_log SET status=?,finished_at=?,input_tokens=?,output_tokens=?,capture_status=?,response_available=?,metadata_json=? WHERE call_id=?",
                    outcome.status().name(),Timestamp.from(finished),input,output,capture,response,encode(info),id);
        });}catch(RuntimeException archiveFailure){
            // Best-effort persistent failure classification plus a fixed, body-free operational metric.
            safe(()->{var info=metadata(id);info.put("captureReason","ARCHIVE_FAILED");info.put("errorCode",outcome.errorCode()==null?null:outcome.errorCode().name());
                jdbc.update("UPDATE sys_ai_call_log SET status=?,finished_at=?,capture_status='FAILED',response_available=FALSE,metadata_json=? WHERE call_id=? AND finished_at IS NULL",
                    outcome.status().name(),Timestamp.from(Instant.now()),encode(info),id);});throw archiveFailure;
        }
    }
    Map<String,Object> metadata(String id){return decode(jdbc.queryForObject("SELECT metadata_json FROM sys_ai_call_log WHERE call_id=?",String.class,id));}
    Map<String,Object> decode(String value){try{return json.readValue(value,new TypeReference<LinkedHashMap<String,Object>>(){});}catch(Exception invalid){throw new IllegalStateException("AI_CALL_METADATA_INVALID");}}
    private String encode(Object value){try{return json.writeValueAsString(value);}catch(Exception invalid){throw new IllegalStateException("AI_CALL_METADATA_INVALID");}}
    private static Long number(String value){return value==null?null:Long.valueOf(value);}
    private static String safeId(String value){return value!=null&&value.matches("[A-Za-z0-9][A-Za-z0-9_.:/-]{0,127}")?value:null;}
    private static boolean supportedCost(java.math.BigDecimal cost){return cost==null||(cost.precision()<=128&&cost.scale()>=-128&&cost.scale()<=128&&(long)cost.precision()-cost.scale()<=128);}
    private static Map<String,Object> blank(){
        var row=new LinkedHashMap<String,Object>();
        for(String key:("requestKind sceneKey appId appName modelCode returnedModel configName maskedKeyLabel attemptNumber switchReason errorCode httpStatus streaming "
                +"elapsedMs responseHeadersMs firstByteMs firstOutputMs firstTextMs lastOutputMs firstOutputKind finishReason providerRequestId totalTokens cacheReadTokens cacheWriteTokens reasoningTokens reportedCost currency captureReason").split(" "))row.put(key,null);
        row.put("redacted",false);row.put("truncated",false);return row;
    }
}
