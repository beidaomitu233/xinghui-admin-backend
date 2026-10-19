package com.xinghuiTec.ai.application;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.xinghuiTec.ai.contract.*;
import com.xinghuiTec.ai.contract.AiProviderRuntime.*;
import com.xinghuiTec.ai.contract.AiTypes.*;
import com.xinghuiTec.ai.modelhub.ModelHubTestService;
import com.xinghuiTec.ai.scene.SqlAssistanceService;
import org.junit.jupiter.api.*;
import org.springframework.beans.factory.support.StaticListableBeanFactory;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DataSourceTransactionManager;
import org.springframework.core.io.ClassPathResource;
import java.math.BigDecimal;
import java.nio.charset.StandardCharsets;
import java.sql.Timestamp;
import java.time.*;
import java.util.*;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

/** Actual H2 migration/store/query, controlled telemetry only; no provider or native MySQL claims. */
class AiCallLogServiceTest {
    private JdbcTemplate jdbc,queries;
    private AiCallLogStore store;
    private AiCallLogService logs;
    private AiRequestStore requests;
    private AiRequestJournal journal;
    private AiRuntimeService runtime;
    private SqlAssistanceService sql;
    private ModelHubTestService tests;
    private AiRequestLogService requestLogs;
    private final Set<String> actions=Set.of("business:read","ai:request:list","ai:request:query","ai:request:export");
    private final TrustedActor own=new TrustedActor("101","tenant-a",actions,null);
    private final TrustedActor audit=new TrustedActor("900","000000",Set.of("ai:request:list","ai:request:query","ai:request:export","ai:request:audit","ai:config:query"),null);
    @BeforeEach void setup()throws Exception{
        var source=AiApplicationTestDatabase.create();jdbc=new JdbcTemplate(source);
        script(read("V20261016_180__ai_config_v04.sql").split("-- Existing configurations")[0]);
        jdbc.execute("ALTER TABLE sys_ai_request ADD COLUMN scene_key VARCHAR(128) NULL");
        jdbc.execute("ALTER TABLE sys_ai_request ADD COLUMN scene_version_id VARCHAR(32) NULL");
        script(read("V20261019_250__ai_call_logs.sql"));
        jdbc.update("INSERT INTO sys_ai_configuration(config_id,config_name,environment_name,provider_code,status,revision) VALUES(1,'fixture channel','test','SIMULATED','DRAFT',0)");
        jdbc.update("INSERT INTO sys_ai_configuration_version(version_id,config_id,snapshot_json) VALUES(2001,1,'{}')");
        jdbc.execute("CREATE TABLE sys_ai_conversation_turn(request_id BIGINT PRIMARY KEY,conversation_id BIGINT)");
        var manager=new DataSourceTransactionManager(source);var json=new ObjectMapper().findAndRegisterModules();
        store=new AiCallLogStore(jdbc,manager,json);requests=new AiRequestStore(jdbc);journal=new AiRequestJournal(jdbc,requests,manager);journal.callLogs(store);
        var apps=new AiApplicationStore(jdbc,json);var beans=new StaticListableBeanFactory();
        var versions=new AiApplicationService(jdbc,json,apps,new JdbcAiApplicationReferences(jdbc,manager),beans.getBeanProvider(AiProviderRuntime.class),beans.getBeanProvider(AiTemplateRuntime.class));
        var policy=new AiApplicationPolicy(AiApplicationPolicy.ModelMode.FIXED,List.of(new ModelBinding(new ModelVersionRef("1001","2001"),KeyMode.AUTO,Set.of(),null,List.of())),false,
                new TemplateVersionRef("1","2"),Set.of("user_request"),new Requirements(OutputFormat.TEXT,true,false),AiApplicationPolicy.Scope.FRONTEND,Set.of(),Set.of("business:read"),"1",30,2,2,128);
        apps.create(new AiApplicationStore.Draft("fixture app","controlled fixture","lowcode.record.summary/1",policy),101);
        jdbc.update("INSERT INTO sys_ai_application_version(app_version_id,app_id,version_number,draft_revision,snapshot_json,tested_request_id,created_by) VALUES(11,1,1,1,?,1,101)",json.writeValueAsString(new AiApplicationService.Snapshot(policy,List.of(),List.of())));
        jdbc.update("UPDATE sys_ai_application SET published_version_id=11,enabled=TRUE WHERE app_id=1");
        runtime=mock(AiRuntimeService.class);sql=mock(SqlAssistanceService.class);tests=mock(ModelHubTestService.class);queries=spy(jdbc);
        requestLogs=new AiRequestLogService(jdbc,apps,versions);
        logs=new AiCallLogService(queries,store,requestLogs,requests,runtime,sql,tests);
    }
    @Test void exactTelemetryPersistsOnceAndListStatisticsExportNeverLoadBodies(){
        long request=request(own,"lowcode.record");var attempt=attempt(request,own);var outcome=success(telemetry());
        assertTrue(journal.complete(request,attempt.attemptId(),outcome));
        assertFalse(journal.complete(request,attempt.attemptId(),new ExecutionOutcome(ExecutionStatus.FAILED,ErrorCode.INTERNAL_FAILURE,false,false,null,null,0)));
        var filter=filter(AiRequestLogService.Scope.OWN);
        assertEquals(1,logs.page(own,false,filter,1,20).total());assertEquals(1,logs.export(own,false,filter).size());
        var stats=logs.statistics(own,false,filter);assertEquals(1,stats.calls());assertEquals(1,stats.succeeded());assertEquals(0L,stats.knownInputTokens());assertEquals(3L,stats.knownOutputTokens());
        for(var invocation:mockingDetails(queries).getInvocations())if(invocation.getArguments().length>0&&invocation.getArgument(0) instanceof String statement)
            assertFalse(statement.contains("sys_ai_call_response"));
        String id=id(request);var detail=logs.detail(own,false,filter,id);
        assertEquals("0.000000000123456789",detail.call().get("reportedCost"));assertEquals(0L,detail.call().get("inputTokens"));
        assertEquals(4L,detail.call().get("firstOutputMs"));assertEquals("SSE",detail.response().format());assertTrue(detail.response().body().contains("unknown_extension"));
        assertEquals("fixture channel",detail.call().get("configName"));assertEquals("fixture-model",detail.call().get("modelCode"));assertEquals("fixture-returned",detail.call().get("returnedModel"));
        assertEquals(1,jdbc.queryForObject("SELECT COUNT(*) FROM sys_ai_call_response",Integer.class));
        assertEquals(1,jdbc.queryForObject("SELECT COUNT(*) FROM sys_ai_request_attempt",Integer.class));
    }
    @Test void tenantAndSubjectCannotSpoofScopeAndAuditRequiresPlatformPlusAction(){
        long request=request(own,"lowcode.record");journal.complete(request,attempt(request,own).attemptId(),success(telemetry()));
        var other=new TrustedActor("102","tenant-a",actions,null);var otherTenant=new TrustedActor("101","tenant-b",actions,null);
        for(var actor:List.of(other,otherTenant)){
            assertEquals(0,logs.page(actor,false,filter(AiRequestLogService.Scope.OWN),1,20).total());
            assertThrows(AiRuntimeException.class,()->logs.detail(actor,false,filter(AiRequestLogService.Scope.OWN),id(request)));
        }
        assertThrows(AiRuntimeException.class,()->logs.page(audit,false,filter(AiRequestLogService.Scope.AUDIT),1,20));
        assertThrows(AiRuntimeException.class,()->logs.statistics(own,true,filter(AiRequestLogService.Scope.AUDIT)));
        var spoof=new AiCallLogService.Filters(AiRequestLogService.Scope.OWN,null,null,null,null,null,null,null,"tenant-b",null,null,null);
        assertEquals(ErrorCode.FORBIDDEN,assertThrows(AiRuntimeException.class,()->logs.export(own,false,spoof)).code());
    }
    @Test void auditCanReadOrdinaryConversationButNeverInheritsPrivateBodyGrant(){
        long ordinary=request(own,"lowcode.record"),privateSql=request(own,AiRequestStore.SQL_ASSISTANCE_TYPE),privateModel=request(own,AiRequestStore.MODEL_HUB_TEST_TYPE);
        for(long request:new long[]{ordinary,privateSql,privateModel}){journal.complete(request,attempt(request,own).attemptId(),success(telemetry()));jdbc.update("INSERT INTO sys_ai_conversation_turn VALUES(?,1)",request);}
        var auditFilter=filter(AiRequestLogService.Scope.AUDIT);
        assertNotNull(logs.detail(audit,true,auditFilter,id(ordinary)).response());
        assertNull(logs.detail(audit,true,auditFilter,id(privateSql)).response());assertNull(logs.detail(audit,true,auditFilter,id(privateModel)).response());
        assertNotNull(logs.detail(own,false,filter(AiRequestLogService.Scope.OWN),id(privateSql)).response());
        doThrow(new AiRuntimeException(ErrorCode.FORBIDDEN)).when(sql).authorizeCallLog(privateSql,own);
        assertFalse((Boolean)logs.detail(own,false,filter(AiRequestLogService.Scope.OWN),id(privateSql)).call().get("responseAvailable"));
        assertNull(logs.detail(own,false,filter(AiRequestLogService.Scope.OWN),id(privateSql)).response());
        doThrow(new AiRuntimeException(ErrorCode.FORBIDDEN)).when(runtime).authorizeEvents(any(),eq(own));
        assertNull(logs.detail(own,false,filter(AiRequestLogService.Scope.OWN),id(ordinary)).response());
    }
    @Test void moreThanFiveHundredCallsAndThirtyOneDayRangeRemainQueryable(){
        long request=request(own,"lowcode.record");journal.complete(request,attempt(request,own).attemptId(),success(telemetry()));
        Instant old=Instant.now().minus(Duration.ofDays(20));jdbc.update("UPDATE sys_ai_request SET create_time=? WHERE request_id=?",Timestamp.from(old),request);
        jdbc.update("UPDATE sys_ai_call_log SET started_at=? WHERE request_id=?",Timestamp.from(old),request);
        // Additional explicitly synthetic observations exercise SQL pagination, not provider execution.
        for(int i=0;i<510;i++)jdbc.update("INSERT INTO sys_ai_call_log(call_id,request_id,entry_type,owner_scope,tenant_ref,subject_ref,status,started_at,metadata_json) VALUES(?,?,'REQUEST_ATTEMPT',?,? ,?,'SUCCEEDED',?,'{}')",
                UUID.randomUUID().toString(),request,AiRuntimeService.owner(own).scope(),own.tenantId(),own.subjectId(),Timestamp.from(old));
        var wide=new AiCallLogService.Filters(AiRequestLogService.Scope.OWN,Instant.now().minus(Duration.ofDays(30)),Instant.now(),null,null,null,null,null,null,null,null,null);
        assertEquals(511,logs.page(own,false,wide,2,100).total());assertEquals(100,logs.page(own,false,wide,2,100).records().size());
        assertEquals(511,logs.statistics(own,false,wide).calls());assertEquals(511,logs.export(own,false,wide).size());
        assertNotNull(logs.detail(own,false,wide,id(request)).response());assertEquals(0,logs.statistics(own,false,filter(AiRequestLogService.Scope.OWN)).calls());
    }
    @Test void archiveFailureIsObservableAndDoesNotChangeOrReplayTheAttempt(){
        long request=request(own,"lowcode.record");var attempt=attempt(request,own);jdbc.execute("DROP TABLE sys_ai_call_response");
        assertDoesNotThrow(()->journal.complete(request,attempt.attemptId(),success(telemetry())));
        assertEquals(1,store.archiveFailures());assertEquals("SUCCEEDED",jdbc.queryForObject("SELECT status FROM sys_ai_request_attempt WHERE request_id=?",String.class,request));
        var detail=logs.detail(own,false,filter(AiRequestLogService.Scope.OWN),id(request));assertEquals("FAILED",detail.call().get("captureStatus"));assertNull(detail.response());
        assertEquals(1,jdbc.queryForObject("SELECT COUNT(*) FROM sys_ai_call_log",Integer.class));
    }
    @Test void unknownUsageStaysNullAndDiscoveryIsPlatformOnlyWithoutFabricatedAssociations(){
        long request=request(own,"lowcode.record");journal.complete(request,attempt(request,own).attemptId(),success(null));
        var unknown=logs.statistics(own,false,filter(AiRequestLogService.Scope.OWN));assertNull(unknown.knownInputTokens());assertEquals(1,unknown.unknownInputCalls());
        String scan=store.beginDiscovery(audit,"openrouter");store.completeDiscovery(scan,true,0);store.completeDiscovery(scan,false,9);
        var scanDetail=logs.detail(audit,true,filter(AiRequestLogService.Scope.OWN),scan);assertNull(scanDetail.call().get("requestId"));assertNull(scanDetail.call().get("appId"));
        assertNull(scanDetail.call().get("streaming"));assertNull(scanDetail.call().get("inputTokens"));assertEquals(0L,scanDetail.call().get("elapsedMs"));
        assertEquals("NOT_CAPTURED",scanDetail.call().get("captureStatus"));assertEquals("DISCOVERY_METADATA_ONLY",scanDetail.call().get("captureReason"));
        assertEquals("SUCCEEDED",scanDetail.call().get("status"));
        assertEquals(1,logs.page(own,false,filter(AiRequestLogService.Scope.OWN),1,20).total());
        assertThrows(AiRuntimeException.class,()->logs.detail(own,false,filter(AiRequestLogService.Scope.OWN),scan));
        var listOnly=new TrustedActor(own.subjectId(),own.tenantId(),Set.of("business:read","ai:request:list"),null);
        assertFalse((Boolean)logs.page(listOnly,false,filter(AiRequestLogService.Scope.OWN),1,20).records().get(0).get("responseAvailable"));
    }
    @Test void configurationPairUsesPersistedInitiatorAndUniqueRealPair(){
        jdbc.update("INSERT INTO sys_ai_configuration_test(test_id,config_id,version_id,status,initiator_subject_ref,initiator_tenant_ref) VALUES(1,1,2001,'RUNNING',?,?)",audit.subjectId(),audit.tenantId());
        jdbc.update("INSERT INTO sys_ai_configuration_test_pair(pair_id,test_id,model_ref,key_ref,key_version,proof_hash) VALUES(7,1,1,1,1,'fixture')");
        String id=store.beginConfigurationPair(1,7,"1","fixture-model","openrouter","fixture masked label");
        assertEquals(id,store.beginConfigurationPair(1,7,"1","fixture-model","openrouter","fixture masked label"));
        store.completeConfigurationPair(id,success(telemetry()));var detail=logs.detail(audit,true,filter(AiRequestLogService.Scope.OWN),id);
        assertEquals("1",detail.call().get("configurationTestId"));assertEquals("7",detail.call().get("configurationPairId"));assertNull(detail.call().get("requestId"));
        assertEquals(audit.subjectId(),detail.call().get("subjectRef"));assertNotNull(detail.response());assertEquals("fixture channel",detail.call().get("configName"));
        assertEquals(0,logs.page(own,false,filter(AiRequestLogService.Scope.OWN),1,20).total());
        assertThrows(AiRuntimeException.class,()->logs.detail(own,false,filter(AiRequestLogService.Scope.AUDIT),id));
    }
    @Test void invalidWindowsFiltersAndPaginationAreRejectedBeforeBodyAccess(){
        var wide=new AiCallLogService.Filters(AiRequestLogService.Scope.OWN,Instant.now().minus(Duration.ofDays(32)),Instant.now(),null,null,null,null,null,null,null,null,null);
        assertThrows(AiRuntimeException.class,()->logs.statistics(own,false,wide));assertThrows(AiRuntimeException.class,()->logs.page(own,false,filter(AiRequestLogService.Scope.OWN),0,20));
        assertThrows(AiRuntimeException.class,()->logs.page(own,false,filter(AiRequestLogService.Scope.OWN),1,101));
    }
    @Test void unknownExtensionsPreserveNumbersBeyondLongAndHighPrecisionDecimals(){
        long request=request(own,"lowcode.record");journal.complete(request,attempt(request,own).attemptId(),success(telemetry()));
        jdbc.update("UPDATE sys_ai_call_response SET usage_json=? WHERE call_id=?",
                "{\"vendor_big_counter\":9223372036854775808,\"precise\":0.12345678901234567890123456789,\"huge_exponent\":1e999999,\"nested\":[0,9223372036854775807]}",id(request));
        var usage=logs.detail(own,false,filter(AiRequestLogService.Scope.OWN),id(request)).response().usage();
        assertEquals("9223372036854775808",usage.get("vendor_big_counter"));assertEquals("0.12345678901234567890123456789",usage.get("precise"));
        assertEquals("1E+999999",usage.get("huge_exponent"));assertEquals(List.of(0L,Long.MAX_VALUE),usage.get("nested"));
        assertEquals(3L,logs.statistics(own,false,filter(AiRequestLogService.Scope.OWN)).knownOutputTokens());
    }
    @Test void extremeReportedCostIsNotExpandedOrInvented(){
        long request=request(own,"lowcode.record");var t=telemetry();
        var extreme=new AiCallTelemetry(t.schemaVersion(),t.streaming(),t.startedAt(),t.finishedAt(),t.httpStatus(),t.elapsedMs(),t.responseHeadersMs(),t.firstByteMs(),t.firstOutputMs(),t.firstTextMs(),t.lastOutputMs(),
                t.firstOutputKind(),t.returnedModel(),t.finishReason(),t.inputTokens(),t.outputTokens(),t.totalTokens(),t.cacheReadTokens(),t.cacheWriteTokens(),t.reasoningTokens(),
                new BigDecimal("1E+2147483647"),t.currency(),t.usageJson(),t.responseHeadersJson(),t.responseBody(),t.responseFormat(),t.captureStatus(),t.captureReason(),t.receivedBytes(),t.archivedBytes(),t.redacted(),t.truncated());
        journal.complete(request,attempt(request,own).attemptId(),success(extreme));
        var detail=logs.detail(own,false,filter(AiRequestLogService.Scope.OWN),id(request));assertNull(detail.call().get("reportedCost"));
        assertEquals("PARTIAL",detail.call().get("captureStatus"));assertEquals("UNSUPPORTED_COST",detail.call().get("captureReason"));
    }
    @Test void realDruidStatWallAcceptsAllFourQueryShapesIncludingEmptyOwnAndAudit()throws Exception{
        long request=request(own,"lowcode.record");journal.complete(request,attempt(request,own).attemptId(),success(telemetry()));
        jdbc.update("INSERT INTO sys_ai_conversation_turn VALUES(?,1)",request);
        try(var pool=new com.alibaba.druid.pool.DruidDataSource()){
            pool.setUrl(((org.springframework.jdbc.datasource.DriverManagerDataSource)jdbc.getDataSource()).getUrl());pool.setUsername("sa");pool.setPassword("");
            pool.setDbType("mysql");pool.setFilters("stat,wall");pool.setInitialSize(0);pool.setMinIdle(0);pool.setMaxActive(2);pool.setValidationQuery("SELECT 1");
            var guarded=new AiCallLogService(new JdbcTemplate(pool),store,requestLogs,requests,runtime,sql,tests);
            for(var scope:List.of(AiRequestLogService.Scope.OWN,AiRequestLogService.Scope.AUDIT)){
                var actor=scope==AiRequestLogService.Scope.OWN?own:audit;
                assertEquals(1,guarded.page(actor,true,filter(scope),1,20).total());assertEquals(1,guarded.statistics(actor,true,filter(scope)).calls());
                assertEquals(1,guarded.export(actor,true,filter(scope)).size());assertNotNull(guarded.detail(actor,true,filter(scope),id(request)).response());
            }
            var empty=new TrustedActor("799","tenant-a",actions,null);assertEquals(0,guarded.page(empty,false,filter(AiRequestLogService.Scope.OWN),1,20).total());
            assertTrue(pool.getProxyFilters().stream().anyMatch(com.alibaba.druid.wall.WallFilter.class::isInstance));
        }
    }
    private long request(TrustedActor actor,String type){
        Instant now=Instant.now();var admitted=requests.accept(new AiRequestStore.Admission(1,11L,null,AiRequestStore.Kind.BUSINESS,AiRuntimeService.owner(actor),type,"1:7",UUID.randomUUID().toString(),"a".repeat(64),now.plusSeconds(30)),now);
        long id=Long.parseLong(admitted.request().requestId());requests.start(id,now);return id;
    }
    private AiRequestJournal.Attempt attempt(long request,TrustedActor actor){return journal.reserve(request,AiRuntimeService.owner(actor),new Target(new ModelVersionRef("1001","2001"),new KeyRef("3001","4001"),"SIMULATED","fixture-model","masked fixture",new Capabilities(Set.of(OutputFormat.TEXT),true,false)),2,AiRequestJournal.SwitchReason.FIRST,64);}
    private String id(long request){return jdbc.queryForObject("SELECT call_id FROM sys_ai_call_log WHERE request_id=? AND attempt_id IS NOT NULL",String.class,request);}
    private AiCallLogService.Filters filter(AiRequestLogService.Scope scope){return new AiCallLogService.Filters(scope,null,null,null,null,null,null,null,null,null,null,null);}
    static AiCallTelemetry telemetry(){
        String body="data: {\"unknown_extension\":{\"kept\":true}}\n\n",headers="{\"content-type\":\"text/event-stream\"}",usage="{\"prompt_tokens\":0,\"completion_tokens\":3}";
        long bytes=(body+headers+usage).getBytes(StandardCharsets.UTF_8).length;
        return new AiCallTelemetry(1,true,Instant.now().minusMillis(27),Instant.now(),200,27L,2L,3L,4L,5L,26L,"TEXT","fixture-returned","stop",0L,3L,3L,0L,null,2L,
                new BigDecimal("0.000000000123456789"),"USD",usage,headers,body,"SSE","COMPLETE",null,bytes,bytes,false,false);
    }
    private ExecutionOutcome success(AiCallTelemetry telemetry){return new ExecutionOutcome(ExecutionStatus.SUCCEEDED,null,false,true,null,"fixture-id",27,telemetry);}
    private String read(String name)throws Exception{try(var in=new ClassPathResource("db/migration/"+name).getInputStream()){return new String(in.readAllBytes(),StandardCharsets.UTF_8);}}
    private void script(String sql){for(String part:sql.replaceAll("(?m)^--.*$","").replace(" COLLATE utf8mb4_bin","").replace(" ENGINE=InnoDB DEFAULT CHARSET=utf8mb4","").split(";"))if(!part.isBlank())jdbc.execute(part);}
}
