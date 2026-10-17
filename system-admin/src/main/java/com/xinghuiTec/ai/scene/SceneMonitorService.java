package com.xinghuiTec.ai.scene;

import com.xinghuiTec.ai.application.*;
import com.xinghuiTec.ai.config.AiConfigurationService;
import com.xinghuiTec.ai.contract.AiTypes.TrustedActor;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.stereotype.Service;

import java.time.Duration;
import java.time.Instant;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

import static com.xinghuiTec.ai.scene.SceneFailure.require;

@Service
public class SceneMonitorService {
    private final AiRequestLogService logs;
    private final ObjectProvider<AiConfigurationService> configurations;
    public SceneMonitorService(AiRequestLogService logs,ObjectProvider<AiConfigurationService> configurations){this.logs=logs;this.configurations=configurations;}
    public SceneDtos.Monitor summary(TrustedActor actor,boolean platform,String scope,String kind,Instant from,Instant to,String sceneKey,String modelRef,String result){
        AiRequestLogService.Scope chosen;try{chosen=scope==null?AiRequestLogService.Scope.OWN:AiRequestLogService.Scope.valueOf(scope);}catch(Exception invalid){throw new SceneFailure(400,"INVALID_INPUT");}
        if(chosen==AiRequestLogService.Scope.AUDIT)require(platform && actor.authorities().contains("ai:request:audit"),403,"FORBIDDEN");
        String requestKind=kind==null?"BUSINESS":kind;require(Set.of("BUSINESS","TEST").contains(requestKind),400,"INVALID_INPUT");
        Instant observed=Instant.now();Instant end=to==null?observed:to;Instant start=from==null?end.minus(Duration.ofHours(24)):from;
        require(start.isBefore(end) && Duration.between(start,end).compareTo(Duration.ofDays(31))<=0,400,"INVALID_INPUT");
        AiRequestStatus status=null;if(result!=null && !result.isBlank()){try{status=AiRequestStatus.valueOf(result);}catch(Exception invalid){throw new SceneFailure(400,"INVALID_INPUT");}}
        var filters=new AiRequestLogService.Filters(chosen,null,null,null,modelRef,status,null,null,start,end,sceneKey,requestKind);
        boolean canLogs=actor.authorities().contains("ai:request:list");
        SceneDtos.Calls calls=canLogs?logs.monitorCalls(actor,platform,filters):null;
        List<AiRequestLogService.KeyObservation> observations=canLogs?logs.keyObservations(actor,platform,filters):List.of();
        Long unknown=canLogs?observations.stream().filter(o->o.keyVersion()==null || o.keyVersion().isBlank()).mapToLong(AiRequestLogService.KeyObservation::attempts).sum():null;
        Long enabled=null,pairs=null,failed=null;String reason=null;
        boolean canConfig=platform && (actor.authorities().contains("ai:config:query") || actor.authorities().contains("ai:config:list"));
        if(canConfig){
            var service=configurations.getIfUnique();
            if(service==null)reason="CONFIGURATION_UNAVAILABLE";
            else {
                var active=new HashSet<String>();long pairCount=0;
                var page=service.page(1,100,null,null,null,"ACTIVE");
                if(page.total()>1000)reason="TOO_MANY_CONFIGURATIONS";
                else{
                    for(int p=1;p<=Math.max(1,(page.total()+99)/100);p++){
                        var rows=p==1?page.records():service.page(p,100,null,null,null,"ACTIVE").records();
                        for(var row:rows){var view=service.get(Long.parseLong(row.id()));if(!"ACTIVE".equals(view.status()) || view.activeVersion()==null)continue;
                            var snapshot=service.snapshot(Long.parseLong(row.id()),Long.parseLong(view.activeVersion()));
                            for(var pair:view.activePairs()){
                                boolean matches=snapshot.keys().stream().anyMatch(k->k.keyRef().equals(pair.keyRef()) && k.keyVersion().equals(pair.keyVersion()));
                                if(matches){pairCount++;active.add(pair.keyRef()+":"+pair.keyVersion());}
                            }
                        }
                    }
                    enabled=(long)active.size();pairs=pairCount;
                    if(canLogs && chosen==AiRequestLogService.Scope.AUDIT){
                        if(observations.size()>10000)reason="TOO_MANY_KEYS";
                        else failed=observations.stream().filter(o->o.failed()>0 && o.keyVersion()!=null && active.contains(o.keyRef()+":"+o.keyVersion())).count();
                    }else reason=canLogs?"AUDIT_REQUIRED":"LOG_LIST_REQUIRED";
                }
            }
        }else reason="CONFIG_QUERY_REQUIRED";
        return new SceneDtos.Monitor(observed,new SceneDtos.Window(start,end),chosen.name(),requestKind,new SceneDtos.KeyCards(enabled,failed,pairs,unknown,reason),calls);
    }
}
