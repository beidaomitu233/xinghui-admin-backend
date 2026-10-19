package com.xinghuiTec.ai.application;

import com.xinghuiTec.ai.contract.AiRuntimeException;
import com.xinghuiTec.ai.contract.AiTypes.ErrorCode;
import com.xinghuiTec.ai.contract.AiTypes.TrustedActor;
import com.xinghuiTec.ai.modelhub.ModelHubTestService;
import com.xinghuiTec.ai.scene.SqlAssistanceService;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;

import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Timestamp;
import java.time.Duration;
import java.time.Instant;
import java.util.*;

/** One row per upstream interaction; bodies are fetched only after a separate current access check. */
@Service
public class AiCallLogService {
    public enum EntryType { REQUEST_ATTEMPT, CONFIGURATION_TEST, MODEL_DISCOVERY }
    public record Filters(AiRequestLogService.Scope scope,Instant from,Instant to,String requestId,String modelRef,
                          String providerCode,AiRequestStatus status,EntryType entryType,String tenantRef,String subjectRef,String appId,String sceneKey) {}
    public record Page(List<Map<String,Object>> records,long total,int pageNum,int pageSize) {}
    public record Detail(Map<String,Object> call,Response response) {}
    public record Response(String format,String body,Map<String,Object> headers,Map<String,Object> usage,Long receivedBytes,Long archivedBytes) {
        @Override public String toString(){return "Response[REDACTED]";}
    }
    public record Statistics(long calls,long succeeded,long failed,Long knownInputTokens,Long knownOutputTokens,long unknownInputCalls,long unknownOutputCalls) {}
    private record Where(String sql,List<Object> args,boolean empty) {}
    private final JdbcTemplate jdbc;
    private final AiCallLogStore store;
    private final AiRequestLogService requestLogs;
    private final AiRequestStore requests;
    private final AiRuntimeService runtime;
    private final SqlAssistanceService sql;
    private final ModelHubTestService modelTests;
    public AiCallLogService(JdbcTemplate jdbc,AiCallLogStore store,AiRequestLogService requestLogs,AiRequestStore requests,
                            AiRuntimeService runtime,SqlAssistanceService sql,ModelHubTestService modelTests){
        this.jdbc=jdbc;this.store=store;this.requestLogs=requestLogs;this.requests=requests;this.runtime=runtime;this.sql=sql;this.modelTests=modelTests;
    }
    public Page page(TrustedActor actor,boolean platform,Filters filters,int page,int size){
        if(page<1||page>100_000||size<1||size>100)throw failure(ErrorCode.INVALID_INPUT);
        var where=where(actor,platform,filters,"list",null);if(where.empty())return new Page(List.of(),0,page,size);
        var args=new ArrayList<>(where.args());args.add(size);args.add((page-1)*size);
        var rows=jdbc.query(select()+where.sql()+" ORDER BY c.started_at DESC,c.call_id DESC LIMIT ? OFFSET ?",this::row,args.toArray());
        return new Page(decorate(rows,actor,platform,filters.scope()),count(where),page,size);
    }
    public List<Map<String,Object>> export(TrustedActor actor,boolean platform,Filters filters){
        var where=where(actor,platform,filters,"export",null);if(where.empty())return List.of();
        if(count(where)>5000)throw failure(ErrorCode.INVALID_INPUT);
        return decorate(jdbc.query(select()+where.sql()+" ORDER BY c.started_at DESC,c.call_id DESC LIMIT 5000",this::row,where.args().toArray()),actor,platform,filters.scope());
    }
    public Statistics statistics(TrustedActor actor,boolean platform,Filters filters){
        var where=where(actor,platform,filters,"list",null);if(where.empty())return new Statistics(0,0,0,null,null,0,0);
        return jdbc.queryForObject("SELECT COUNT(*),SUM(CASE WHEN c.status='SUCCEEDED' THEN 1 ELSE 0 END),"
                        +"SUM(CASE WHEN c.status IN ('FAILED','TIMED_OUT','INCOMPLETE','CANCELLED') THEN 1 ELSE 0 END),"
                        +"SUM(c.input_tokens),SUM(c.output_tokens),SUM(CASE WHEN c.input_tokens IS NULL THEN 1 ELSE 0 END),"
                        +"SUM(CASE WHEN c.output_tokens IS NULL THEN 1 ELSE 0 END)"+from()+where.sql(),
                (rs,n)->new Statistics(rs.getLong(1),rs.getLong(2),rs.getLong(3),rs.getObject(4,Long.class),rs.getObject(5,Long.class),rs.getLong(6),rs.getLong(7)),where.args().toArray());
    }
    public Detail detail(TrustedActor actor,boolean platform,Filters filters,String id){
        try{if(!UUID.fromString(id).toString().equals(id))throw new IllegalArgumentException();}catch(RuntimeException invalid){throw failure(ErrorCode.INVALID_INPUT);}
        var where=where(actor,platform,filters,"query",id);if(where.empty())throw failure(ErrorCode.NOT_FOUND);
        var rows=jdbc.query(select()+where.sql(),this::row,where.args().toArray());if(rows.size()!=1)throw failure(ErrorCode.NOT_FOUND);
        var call=decorate(rows,actor,platform,filters.scope()).get(0);Response response=null;
        if(Boolean.TRUE.equals(call.get("responseAvailable"))){
            var body=jdbc.query("SELECT response_format,response_body,headers_json,usage_json,received_bytes,archived_bytes FROM sys_ai_call_response WHERE call_id=?",
                    (rs,n)->new Response(rs.getString(1),rs.getString(2),object(rs.getString(3)),object(rs.getString(4)),rs.getObject(5,Long.class),rs.getObject(6,Long.class)),id);
            if(body.size()==1)response=body.get(0);else call.put("responseAvailable",false);
        }
        return new Detail(call,response);
    }
    private Where where(TrustedActor actor,boolean platform,Filters f,String action,String id){
        if(actor==null||!actor.authorities().contains("ai:request:"+action))throw failure(ErrorCode.FORBIDDEN);
        if(f==null||f.scope()==null)throw failure(ErrorCode.INVALID_INPUT);
        if(f.scope()==AiRequestLogService.Scope.AUDIT&&(!platform||!actor.authorities().contains("ai:request:audit")))throw failure(ErrorCode.FORBIDDEN);
        var owner=AiRuntimeService.owner(actor);
        if(f.scope()==AiRequestLogService.Scope.OWN&&((present(f.tenantRef())&&!owner.tenantRef().equals(f.tenantRef()))
                ||(present(f.subjectRef())&&!owner.subjectRef().equals(f.subjectRef()))))throw failure(ErrorCode.FORBIDDEN);
        Instant to=f.to()==null?Instant.now():f.to(),start=f.from()==null?to.minus(Duration.ofDays(7)):f.from();
        if(!start.isBefore(to)||Duration.between(start,to).compareTo(Duration.ofDays(31))>0)throw failure(ErrorCode.INVALID_INPUT);
        var args=new ArrayList<Object>();var query=new StringBuilder(" WHERE c.started_at>=? AND c.started_at<?");
        args.add(Timestamp.from(start));args.add(Timestamp.from(to));
        if(id!=null){query.append(" AND c.call_id=?");args.add(id);}
        if(f.scope()==AiRequestLogService.Scope.OWN){query.append(" AND c.owner_scope=? AND c.tenant_ref=? AND c.subject_ref=?");args.add(owner.scope());args.add(owner.tenantRef());args.add(owner.subjectRef());}
        else{equal(query,args,"c.tenant_ref",f.tenantRef(),64);equal(query,args,"c.subject_ref",f.subjectRef(),128);}
        equal(query,args,"c.model_ref",f.modelRef(),128);equal(query,args,"c.provider_code",f.providerCode(),64);
        if(f.status()!=null){query.append(" AND c.status=?");args.add(f.status().name());}
        if(f.entryType()!=null){query.append(" AND c.entry_type=?");args.add(f.entryType().name());}
        if(present(f.requestId())){query.append(" AND c.request_id=?");args.add(AiApplicationService.positiveId(f.requestId()));}
        if(present(f.appId())){query.append(" AND r.app_id=?");args.add(AiApplicationService.positiveId(f.appId()));}
        equal(query,args,"r.scene_key",f.sceneKey(),128);
        boolean management=management(actor,platform);
        if(f.entryType()!=null&&f.entryType()!=EntryType.REQUEST_ATTEMPT&&!management)throw failure(ErrorCode.FORBIDDEN);
        if(f.scope()==AiRequestLogService.Scope.AUDIT){if(!management)query.append(" AND c.entry_type='REQUEST_ATTEMPT'");return new Where(query.toString(),args,false);}
        // Preserve the existing application-version SQL boundary without enumerating requests before pagination.
        // Current resource/schema permission is checked independently before making a response available.
        var base=f.entryType()!=null&&f.entryType()!=EntryType.REQUEST_ATTEMPT?null:requestLogs.where(actor,platform,
                new AiRequestLogService.Filters(f.scope(),f.appId(),f.requestId(),null,null,null,
                        f.tenantRef(),f.subjectRef(),start,to,f.sceneKey(),null),action);
        if(base==null||base.empty()){
            if(!management)return new Where(query.toString(),args,true);
            query.append(" AND c.entry_type IN ('CONFIGURATION_TEST','MODEL_DISCOVERY')");
        }else{
            query.append(" AND (c.request_id IN (SELECT r.request_id FROM sys_ai_request r").append(base.sql()).append(")");args.addAll(base.args());
            if(management)query.append(" OR c.entry_type IN ('CONFIGURATION_TEST','MODEL_DISCOVERY')");query.append(")");
        }
        return new Where(query.toString(),args,false);
    }
    private boolean canReadRequest(long requestId,TrustedActor actor){
        try{
            var request=requests.owned(requestId,AiRuntimeService.owner(actor));
            if(AiRequestStore.SQL_ASSISTANCE_TYPE.equals(request.businessType()))sql.authorizeCallLog(requestId,actor);
            else if(AiRequestStore.MODEL_HUB_TEST_TYPE.equals(request.businessType()))modelTests.authorizeCallLog(Long.toString(requestId),actor);
            else {runtime.status(requestId,actor);runtime.authorizeEvents(request,actor);}
            return true;
        }catch(RuntimeException denied){return false;}
    }
    private List<Map<String,Object>> decorate(List<Map<String,Object>> rows,TrustedActor actor,boolean platform,AiRequestLogService.Scope scope){
        var permissions=new HashMap<Long,Boolean>();var owner=AiRuntimeService.owner(actor);
        for(var row:rows){
            boolean allowed=false;
            if(actor.authorities().contains("ai:request:query")&&Boolean.TRUE.equals(row.get("responseAvailable"))
                    &&owner.tenantRef().equals(row.get("tenantRef"))&&owner.subjectRef().equals(row.get("subjectRef"))){
                if(row.get("requestId")!=null){long request=Long.parseLong((String)row.get("requestId"));allowed=permissions.computeIfAbsent(request,k->canReadRequest(k,actor));}
                else allowed=management(actor,platform);
            }
            if(!allowed&&Boolean.TRUE.equals(row.get("responseAvailable"))&&row.get("requestId")!=null
                    &&scope==AiRequestLogService.Scope.AUDIT&&platform&&actor.authorities().containsAll(Set.of("ai:request:query","ai:request:audit")))
                allowed=auditableConversation(Long.parseLong((String)row.get("requestId")));
            row.put("responseAvailable",allowed&&Boolean.TRUE.equals(row.get("responseAvailable")));
        }return List.copyOf(rows);
    }
    private boolean auditableConversation(long requestId){
        // Conversation AUDIT already allows platform query/audit access to ordinary conversation messages.
        // SQL-assist and model-hub tests never inherit that body grant, even if a turn is accidentally associated.
        Long matches=jdbc.queryForObject("SELECT COUNT(*) FROM sys_ai_conversation_turn t JOIN sys_ai_request r ON r.request_id=t.request_id"
                +" WHERE t.request_id=? AND r.business_type NOT IN (?,?)",Long.class,requestId,AiRequestStore.SQL_ASSISTANCE_TYPE,AiRequestStore.MODEL_HUB_TEST_TYPE);
        return matches!=null&&matches==1;
    }
    private static boolean management(TrustedActor actor,boolean platform){return platform&&actor.servicePrincipalRef()==null
            &&actor.authorities().stream().anyMatch(Set.of("ai:config:query","ai:config:test","ai:model:query","ai:model:test")::contains);}
    private Map<String,Object> row(ResultSet rs,int index)throws SQLException{
        var result=store.decode(rs.getString("metadata_json"));
        for(String[] field:new String[][]{{"callId","call_id"},{"requestId","request_id"},{"attemptId","attempt_id"},
                {"configurationTestId","configuration_test_id"},{"configurationPairId","configuration_pair_id"},{"entryType","entry_type"},
                {"tenantRef","tenant_ref"},{"subjectRef","subject_ref"},{"modelRef","model_ref"},{"providerCode","provider_code"},{"status","status"},{"captureStatus","capture_status"}})result.put(field[0],rs.getString(field[1]));
        result.put("startedAt",instant(rs,"started_at"));result.put("finishedAt",instant(rs,"finished_at"));
        result.put("inputTokens",rs.getObject("input_tokens",Long.class));result.put("outputTokens",rs.getObject("output_tokens",Long.class));
        result.put("responseAvailable",rs.getBoolean("response_available"));return result;
    }
    private long count(Where where){return Objects.requireNonNull(jdbc.queryForObject("SELECT COUNT(*)"+from()+where.sql(),Long.class,where.args().toArray()));}
    private String select(){return "SELECT c.*"+from();}
    private String from(){return " FROM sys_ai_call_log c LEFT JOIN sys_ai_request r ON r.request_id=c.request_id";}
    private Map<String,Object> object(String value){return AiCallLogArchiveJson.object(value);}
    private static String instant(ResultSet rs,String column)throws SQLException{var time=rs.getTimestamp(column);return time==null?null:time.toInstant().toString();}
    private static boolean present(String value){return value!=null&&!value.isBlank();}
    private static void equal(StringBuilder sql,List<Object> args,String column,String value,int max){if(present(value)){
        if(value.length()>max||value.codePoints().anyMatch(Character::isISOControl))throw failure(ErrorCode.INVALID_INPUT);sql.append(" AND ").append(column).append("=?");args.add(value);
    }}
    private static AiRuntimeException failure(ErrorCode code){return new AiRuntimeException(code);}
}
