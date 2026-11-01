package com.xinghuiTec.ai.application;

import com.xinghuiTec.ai.contract.AiRuntimeException;
import com.xinghuiTec.ai.contract.AiTypes.ErrorCode;
import com.xinghuiTec.ai.contract.AiTypes.TrustedActor;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;

import java.util.*;

/** Aggregates observed provider calls using exactly the request log's current access scope. */
@Service
public class AiStatisticsService {
    private final JdbcTemplate jdbc;
    private final AiCallLogService logs;
    public AiStatisticsService(JdbcTemplate jdbc,AiCallLogService logs){this.jdbc=jdbc;this.logs=logs;}
    private static String text(String key){return "NULLIF(JSON_UNQUOTE(JSON_EXTRACT(c.metadata_json,'$."+key+"')),'null')";}
    private static String number(String key){return "CAST("+text(key)+" AS DECIMAL(65,20))";}
    private static final String ELAPSED=number("elapsedMs");
    private static final String TTFT=number("firstOutputMs");
    private static final String COST=number("reportedCost");
    private static final String CURRENCY="COALESCE("+text("currency")+",'UNKNOWN')";
    private static final String AGGREGATES="COUNT(*) AS calls,"
            +"COUNT(DISTINCT COALESCE(CONCAT('request:',c.request_id),CONCAT('run:',c.agent_run_id),CONCAT('call:',c.call_id))) AS requests,"
            +"SUM(CASE WHEN c.status='SUCCEEDED' THEN 1 ELSE 0 END) AS succeeded,"
            +"SUM(CASE WHEN c.status IN ('FAILED','TIMED_OUT','INCOMPLETE','CANCELLED') THEN 1 ELSE 0 END) AS failed,"
            +"SUM(c.input_tokens) AS inputTokens,SUM(c.output_tokens) AS outputTokens,"
            +"SUM("+number("totalTokens")+") AS totalTokens,"
            +"SUM("+number("cacheReadTokens")+") AS cacheReadTokens,SUM("+number("cacheWriteTokens")+") AS cacheWriteTokens,"
            +"SUM("+number("reasoningTokens")+") AS reasoningTokens,"
            +"COUNT(c.input_tokens) AS inputSamples,COUNT("+number("cacheReadTokens")+") AS cacheSamples,"
            +"COUNT("+number("totalTokens")+") AS tokenSamples,"
            +"SUM(CASE WHEN "+number("cacheReadTokens")+" IS NOT NULL THEN c.input_tokens END) AS cacheInputTokens,"
            +"AVG("+ELAPSED+") AS elapsedMs,COUNT("+ELAPSED+") AS elapsedSamples,"
            +"AVG("+TTFT+") AS firstOutputMs,COUNT("+TTFT+") AS firstOutputSamples,"
            +"AVG(CASE WHEN "+ELAPSED+">"+TTFT+" THEN c.output_tokens*1000.0/("+ELAPSED+"-"+TTFT+") END) AS outputTokensPerSecond,"
            +"SUM(f.rating_count) AS ratings,SUM(f.score_sum)/NULLIF(SUM(f.rating_count),0) AS qualityScore,"
            +"SUM(f.positive_count)/NULLIF(SUM(f.rating_count),0) AS positiveRate";
    private String from(){return logs.from()+" LEFT JOIN (SELECT call_id,COUNT(*) rating_count,SUM(score) score_sum,"
            +"SUM(CASE WHEN score>=4 THEN 1 ELSE 0 END) positive_count FROM sys_ai_call_feedback GROUP BY call_id) f ON f.call_id=c.call_id";}
    public Map<String,Object> summary(TrustedActor actor,boolean platform,AiCallLogService.Filters filters){
        var scope=logs.analyticsScope(actor,platform,filters,"list",null);
        String where=scope.empty()?" WHERE 1=0":scope.sql();
        Object[] args=scope.empty()?new Object[0]:scope.args().toArray();
        String base=from()+where;
        var totals=new LinkedHashMap<>(jdbc.queryForMap("SELECT "+AGGREGATES+base,args));
        totals.put("elapsedP50Ms",percentile(base,args,ELAPSED,totals.get("elapsedSamples"),0.50));
        totals.put("elapsedP95Ms",percentile(base,args,ELAPSED,totals.get("elapsedSamples"),0.95));
        totals.put("firstOutputP95Ms",percentile(base,args,TTFT,totals.get("firstOutputSamples"),0.95));
        var models=jdbc.queryForList("SELECT c.model_ref AS modelRef,c.provider_code AS providerCode,MAX("+text("modelCode")+") AS modelCode,"
                +AGGREGATES+base+" GROUP BY c.model_ref,c.provider_code ORDER BY calls DESC",args);
        var providers=jdbc.queryForList("SELECT c.provider_code AS providerCode,"+AGGREGATES+base+" GROUP BY c.provider_code ORDER BY calls DESC",args);
        var scenes=jdbc.queryForList("SELECT COALESCE(r.scene_key,c.agent_scene_key,"+text("sceneKey")+") AS sceneKey,"
                +AGGREGATES+base+" GROUP BY COALESCE(r.scene_key,c.agent_scene_key,"+text("sceneKey")+") ORDER BY calls DESC",args);
        var costs=jdbc.queryForList("SELECT "+CURRENCY+" AS currency,SUM("+COST+") AS amount,COUNT("+COST+") AS observedCalls"
                +base+" GROUP BY "+CURRENCY,args);
        var trend=jdbc.queryForList("SELECT FLOOR(UNIX_TIMESTAMP(c.started_at)/3600)*3600 AS bucket,"+CURRENCY+" AS currency,"
                +"COUNT(*) AS calls,SUM(c.input_tokens) AS inputTokens,SUM(c.output_tokens) AS outputTokens,SUM("+number("totalTokens")+") AS totalTokens,"
                +"SUM("+COST+") AS amount"+base+" GROUP BY FLOOR(UNIX_TIMESTAMP(c.started_at)/3600)*3600,"+CURRENCY+" ORDER BY bucket",args);
        var modelCosts=jdbc.queryForList("SELECT c.model_ref AS modelRef,c.provider_code AS providerCode,"+CURRENCY+" AS currency,"
                +"SUM("+COST+") AS amount,COUNT("+COST+") AS observedCalls"+base+" GROUP BY c.model_ref,c.provider_code,"+CURRENCY,args);
        var errors=jdbc.queryForList("SELECT "+text("errorCode")+" AS code,COUNT(*) AS calls"+base
                +" AND c.status IN ('FAILED','TIMED_OUT','INCOMPLETE') GROUP BY "+text("errorCode")+" ORDER BY calls DESC LIMIT 20",args);
        return Map.of("totals",totals,"models",models,"providers",providers,"scenes",scenes,"costs",costs,
                "modelCosts",modelCosts,"trend",trend,"errors",errors);
    }
    private Object percentile(String base,Object[] args,String metric,Object count,double fraction){
        long size=count instanceof Number n?n.longValue():0;
        if(size==0)return null;
        var values=new ArrayList<>(Arrays.asList(args));values.add(Math.max(0L,(long)Math.ceil(size*fraction)-1));
        return jdbc.queryForObject("SELECT "+metric+base+" AND "+metric+" IS NOT NULL ORDER BY "+metric+" LIMIT 1 OFFSET ?",Object.class,values.toArray());
    }
    public Map<String,Object> feedback(TrustedActor actor,boolean platform,AiCallLogService.Filters filters,String callId){
        authorize(actor,platform,filters,callId);
        var rows=jdbc.queryForList("SELECT score,comment,updated_at AS updatedAt FROM sys_ai_call_feedback WHERE call_id=? AND user_scope=?",callId,AiRuntimeService.owner(actor).scope());
        return rows.isEmpty()?Map.of():rows.get(0);
    }
    public void rate(TrustedActor actor,boolean platform,AiCallLogService.Filters filters,String callId,int score,String comment){
        authorize(actor,platform,filters,callId);
        if(score<1||score>5||comment!=null&&comment.length()>1000)throw new AiRuntimeException(ErrorCode.INVALID_INPUT);
        jdbc.update("INSERT INTO sys_ai_call_feedback(call_id,user_scope,score,comment,created_at,updated_at) VALUES(?,?,?,?,CURRENT_TIMESTAMP,CURRENT_TIMESTAMP) "
                +"ON DUPLICATE KEY UPDATE score=VALUES(score),comment=VALUES(comment),updated_at=CURRENT_TIMESTAMP",callId,AiRuntimeService.owner(actor).scope(),score,comment);
    }
    private void authorize(TrustedActor actor,boolean platform,AiCallLogService.Filters filters,String id){
        try{if(!UUID.fromString(id).toString().equals(id))throw new IllegalArgumentException();}catch(RuntimeException invalid){throw new AiRuntimeException(ErrorCode.INVALID_INPUT);}
        var scope=logs.analyticsScope(actor,platform,filters,"query",id);
        if(scope.empty()||!Long.valueOf(1).equals(jdbc.queryForObject("SELECT COUNT(*)"+logs.from()+scope.sql(),Long.class,scope.args().toArray())))
            throw new AiRuntimeException(ErrorCode.NOT_FOUND);
    }
}
