package com.xinghuiTec.ai.scene;

import com.xinghuiTec.ai.application.*;
import com.xinghuiTec.ai.config.*;
import com.xinghuiTec.ai.contract.*;
import com.xinghuiTec.ai.contract.AiTypes.TrustedActor;
import org.junit.jupiter.api.*;
import org.springframework.transaction.support.TransactionTemplate;
import java.sql.Timestamp;
import java.time.Instant;
import java.util.*;
import static org.junit.jupiter.api.Assertions.*;

/** Actual H2 request ledger with simulated execution; deliberately varied historical metadata. */
class SceneMonitorTest {
    private SceneTestSupport f;private String key,boundary,test;
    private final Instant from=Instant.parse("2026-10-17T01:00:00Z"),to=Instant.parse("2026-10-17T02:00:00Z");
    @BeforeEach void setup()throws Exception{
        f=new SceneTestSupport();var scene=f.save(f.create(false),"Prompt",false);key=scene.detail().scene().sceneKey();
        row(key,from,"SUCCEEDED","17","19","3",f.actor,false);
        boundary=row(key,to,"FAILED","17","19","3",f.actor,false);
        row(key,from.minusMillis(1),"FAILED","17","19","3",f.actor,false);
        var other=f.save(f.create(false),"Other",false);row(other.detail().scene().sceneKey(),from,"FAILED","17","19","3",f.actor,false);
        row(key,from,"FAILED","17","19","3",new TrustedActor("102","tenant_b",Set.of("ai:scene:invoke"),null),false);
        test=row(key,from,"SUCCEEDED","17","19","3",f.actor,true);
        row(key,from,"FAILED","17","19","3",f.actor,false);
        row(key,from,"FAILED","17","19","2",f.actor,false);
        row(key,from,"FAILED","17","19",null,f.actor,false);
        row(key,from,"FAILED","18","20","5",f.actor,false);
    }
    @AfterEach void close(){if(f!=null)f.close();}
    private String row(String scene,Instant time,String status,String model,String keyRef,String keyVersion,TrustedActor actor,boolean isTest)throws Exception{
        var input=f.input(false,UUID.randomUUID().toString());
        var request=isTest?f.service.test(scene,new SceneDtos.TestInput(1,0,true,input),actor):f.service.submit(scene,input,actor);
        Instant limit=Instant.now().plusSeconds(5);while(!f.runtime.status(Long.parseLong(request.requestId()),actor).status().terminal()){
            if(Instant.now().isAfter(limit))fail("simulated worker timeout");Thread.sleep(5);
        }
        f.jdbc.update("UPDATE sys_ai_request SET create_time=?,status=? WHERE request_id=?",Timestamp.from(time),status,request.requestId());
        f.jdbc.update("UPDATE sys_ai_request_attempt SET model_ref=?,key_ref=?,key_version=?,status=? WHERE request_id=?",model,keyRef,keyVersion,status,request.requestId());
        return request.requestId();
    }
    private AiRequestLogService.Filters filters(AiRequestLogService.Scope scope,String kind,String model,AiRequestStatus status){
        return new AiRequestLogService.Filters(scope,null,null,null,model,status,null,null,from,to,key,kind);
    }
    @Test void sharedHalfOpenWindowAndSceneKindOwnerStatusFiltersApplyToEveryLogProjection(){
        var filter=filters(AiRequestLogService.Scope.OWN,"BUSINESS","17",null);
        assertEquals(4,f.logs.page(f.actor,true,filter,1,100).total());
        assertEquals(4,f.logs.export(f.actor,true,filter).size());
        assertEquals(4,f.logs.statistics(f.actor,true,filter).requests());
        assertEquals(4,f.logs.monitorCalls(f.actor,true,filter).requests());
        assertEquals(4,f.logs.monitorCalls(f.actor,true,filter).modelAttempts());
        assertEquals(4,f.logs.keyObservations(f.actor,true,filter).stream().mapToLong(AiRequestLogService.KeyObservation::attempts).sum());
        assertThrows(AiRuntimeException.class,()->f.logs.detail(f.actor,true,filter,Long.parseLong(boundary)));
        assertEquals(3,f.logs.page(f.actor,true,filters(AiRequestLogService.Scope.OWN,"BUSINESS","17",AiRequestStatus.FAILED),1,100).total());
        assertEquals(5,f.logs.page(f.actor,true,filters(AiRequestLogService.Scope.AUDIT,"BUSINESS","17",null),1,100).total());
        assertThrows(AiRuntimeException.class,()->f.logs.page(f.actor,false,filters(AiRequestLogService.Scope.AUDIT,"BUSINESS",null,null),1,100));
    }
    @Test void versionedTestsAreVisibleToTheirOwnerAndExcludedFromBusinessCards(){
        var filter=filters(AiRequestLogService.Scope.OWN,"TEST",null,null);
        assertEquals(1,f.logs.page(f.actor,false,filter,1,100).total());
        assertEquals(test,f.logs.detail(f.actor,false,filter,Long.parseLong(test)).request().request().requestId());
        assertEquals(1,f.logs.statistics(f.actor,false,filter).testRequests());
        assertEquals(1,f.logs.monitorCalls(f.actor,false,filter).testRequests());
    }
    @Test void activeKeyIntersectionExcludesRetiredVersionsAndUnknownAttributionAndMissingRightsStayNull()throws Exception{
        f.script(f.read("V20261016_180__ai_config_v04.sql").split("-- Existing configurations")[0]);
        f.jdbc.execute("CREATE TABLE sys_ai_model_config(model_id BIGINT,provider_code VARCHAR(64),model_code VARCHAR(128))");
        f.jdbc.update("INSERT INTO sys_ai_configuration(config_id,config_name,environment_name,provider_code,status,active_version_id) VALUES(8,'Fixture','test','SIMULATED','ACTIVE',8)");
        var snapshot=new AiConfigurationDtos.Snapshot("8","SIMULATED","https://fixture.invalid","OPENAI",10,
                List.of(new AiConfigurationDtos.ModelSpec("17","model-17"),new AiConfigurationDtos.ModelSpec("18","model-18")),
                List.of(new AiConfigurationDtos.KeySpec("19","3","A","ENV",null),new AiConfigurationDtos.KeySpec("20","5","B","ENV",null)));
        f.jdbc.update("INSERT INTO sys_ai_configuration_version(version_id,config_id,snapshot_json,admitted_pairs_json) VALUES(8,8,?,?)",f.mapper.writeValueAsString(snapshot),"[{\"modelRef\":\"17\",\"keyRef\":\"19\",\"keyVersion\":\"3\",\"proofHash\":\"fixture\"},{\"modelRef\":\"18\",\"keyRef\":\"20\",\"keyVersion\":\"5\",\"proofHash\":\"fixture\"}]");
        var configurations=new AiConfigurationService(f.jdbc,f.mapper,null,null,new TransactionTemplate(f.manager),f.beans.getBeanProvider(AiApplicationReferences.class),null,null);
        f.beans.addBean("configurations",configurations);var monitor=new SceneMonitorService(f.logs,f.beans.getBeanProvider(AiConfigurationService.class));
        var rights=new HashSet<>(f.actor.authorities());rights.add("ai:config:query");var admin=new TrustedActor("101","tenant_a",rights,null);
        var audit=monitor.summary(admin,true,"AUDIT","BUSINESS",from,to,key,"17",null);
        assertEquals(2L,audit.keyCards().enabledKeyVersions());assertEquals(2L,audit.keyCards().knownAdmittedPairs());
        assertEquals(1L,audit.keyCards().recentlyFailedKeyVersions());assertEquals(1L,audit.keyCards().unattributedAttempts());assertEquals(5,audit.calls().requests());
        var own=monitor.summary(admin,true,null,"BUSINESS",from,to,key,"17",null);assertNull(own.keyCards().recentlyFailedKeyVersions());assertEquals("AUDIT_REQUIRED",own.keyCards().unavailableReason());
        var noConfig=monitor.summary(f.actor,true,null,"BUSINESS",from,to,key,"17",null);assertNull(noConfig.keyCards().enabledKeyVersions());assertEquals("CONFIG_QUERY_REQUIRED",noConfig.keyCards().unavailableReason());
        var noLogs=monitor.summary(new TrustedActor("101","tenant_a",Set.of("ai:config:query"),null),true,null,"BUSINESS",from,to,key,null,null);assertNull(noLogs.calls());assertNull(noLogs.keyCards().recentlyFailedKeyVersions());assertEquals("LOG_LIST_REQUIRED",noLogs.keyCards().unavailableReason());
    }
}
