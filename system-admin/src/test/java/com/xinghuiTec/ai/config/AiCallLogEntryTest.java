package com.xinghuiTec.ai.config;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.xinghuiTec.ai.application.AiCallLogStore;
import com.xinghuiTec.ai.contract.AiTypes.TrustedActor;
import com.xinghuiTec.controller.AiConfigurationController;
import com.xinghuiTec.domain.entity.*;
import org.junit.jupiter.api.*;
import org.springframework.jdbc.datasource.DataSourceTransactionManager;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.context.SecurityContextHolder;
import java.nio.charset.StandardCharsets;
import java.util.*;
import static com.xinghuiTec.ai.config.AiConfigurationDtos.*;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

/** Real configuration pair worker + controlled transport; identity is captured before leaving the HTTP thread. */
class AiCallLogEntryTest {
    private AiConfigurationServiceTest fixture;
    private AiCallLogStore logs;
    @BeforeEach void setup()throws Exception{
        fixture=new AiConfigurationServiceTest();fixture.setup();
        String sql;try(var input=getClass().getResourceAsStream("/db/migration/V20261019_250__ai_call_logs.sql")){
            sql=new String(Objects.requireNonNull(input).readAllBytes(),StandardCharsets.UTF_8);
        }
        for(String statement:sql.replaceAll("(?m)^--.*$","").replace(" ENGINE=InnoDB DEFAULT CHARSET=utf8mb4","").split(";"))if(!statement.isBlank())fixture.jdbc.execute(statement);
        logs=new AiCallLogStore(fixture.jdbc,new DataSourceTransactionManager(fixture.jdbc.getDataSource()),new ObjectMapper().findAndRegisterModules());fixture.tests.callLogs(logs);
    }
    @AfterEach void close(){SecurityContextHolder.clearContext();if(fixture!=null)fixture.close();}
    @Test void controllerCapturesInitiatorBeforeAsyncAndLogsEveryRealPairWithoutFakeRequests()throws Exception{
        var view=fixture.service.create(new Create(fixture.fresh(),null));long id=Long.parseLong(view.id());
        assertThrows(AiConfigurationException.class,()->fixture.tests.start(id,new TestStart(view.revision(),view.draft().configVersion(),true)));
        verifyNoInteractions(fixture.transport);
        var user=new SysUser();user.setUserId(101L);user.setTenantId("000000");user.setStatus(1);
        var login=new LoginUser(user,List.of("ai:config:test"));login.setTenantId("000000");
        SecurityContextHolder.getContext().setAuthentication(new UsernamePasswordAuthenticationToken(login,null,login.getAuthorities()));
        var run=new AiConfigurationController(fixture.service,fixture.tests).test(view.id(),new TestStart(view.revision(),view.draft().configVersion(),true)).getData();
        SecurityContextHolder.clearContext();waitFor(id,run.id());
        var origin=fixture.jdbc.queryForMap("SELECT initiator_subject_ref,initiator_tenant_ref FROM sys_ai_configuration_test WHERE test_id=?",Long.parseLong(run.id()));
        assertEquals("101",origin.get("initiator_subject_ref"));assertEquals("000000",origin.get("initiator_tenant_ref"));
        assertEquals(4,fixture.jdbc.queryForObject("SELECT COUNT(*) FROM sys_ai_call_log WHERE configuration_test_id=? AND subject_ref='101' AND tenant_ref='000000' AND request_id IS NULL AND attempt_id IS NULL",Integer.class,Long.parseLong(run.id())));
        assertEquals(4,fixture.jdbc.queryForObject("SELECT COUNT(*) FROM sys_ai_call_log l JOIN sys_ai_configuration_test_pair p ON p.pair_id=l.configuration_pair_id WHERE p.test_id=? AND l.finished_at IS NOT NULL",Integer.class,Long.parseLong(run.id())));
        assertEquals(0,logs.archiveFailures());
    }
    @Test void settingsStartPersistsItsExplicitActorAndNeverReadsWorkerThreadLocal()throws Exception{
        var view=fixture.service.create(new Create(fixture.fresh(),null));long id=Long.parseLong(view.id());
        var actor=new TrustedActor("202","000000",Set.of("ai:config:test"),null);
        var run=fixture.tests.startSettings(id,new TestStart(view.revision(),view.draft().configVersion(),true),actor);waitFor(id,run.id());
        assertEquals(4,fixture.jdbc.queryForObject("SELECT COUNT(*) FROM sys_ai_call_log WHERE subject_ref='202' AND tenant_ref='000000' AND configuration_test_id=?",Integer.class,Long.parseLong(run.id())));
        assertEquals(0,logs.archiveFailures());
    }
    @Test void directoryFetchesAreLoggedWhileSavedCatalogReadsNeverRepeatNetwork()throws Exception{
        var catalog=new AiModelCatalogPersistenceTest();catalog.setup();
        try{
            catalog.apply("V20261019_250__ai_call_logs.sql",false);
            var observations=new AiCallLogStore(catalog.jdbc,catalog.manager,new ObjectMapper().findAndRegisterModules());catalog.catalogs.callLogs(observations);
            var receipt=catalog.catalogs.draftScan(new AiModelCatalogDtos.DraftScan("openrouter",AiModelCatalogTest.KEY,null,1,20),catalog.actor);
            assertEquals(1,catalog.upstream.calls.get());assertEquals(1,catalog.jdbc.queryForObject("SELECT COUNT(*) FROM sys_ai_call_log WHERE entry_type='MODEL_DISCOVERY' AND status='SUCCEEDED' AND input_tokens IS NULL AND output_tokens IS NULL AND request_id IS NULL AND configuration_test_id IS NULL",Integer.class));
            catalog.catalogs.savedScan(receipt.catalogScanId(),catalog.actor,"",1,20);
            assertEquals(1,catalog.upstream.calls.get());assertEquals(1,catalog.jdbc.queryForObject("SELECT COUNT(*) FROM sys_ai_call_log",Integer.class));
            catalog.upstream.body="invalid JSON";
            assertThrows(AiModelCatalogException.class,()->catalog.catalogs.catalog(null,1,20,catalog.actor));
            assertEquals(2,catalog.upstream.calls.get());assertEquals(1,catalog.jdbc.queryForObject("SELECT COUNT(*) FROM sys_ai_call_log WHERE status='FAILED' AND capture_status='NOT_CAPTURED' AND response_available=FALSE",Integer.class));
            assertEquals(0,observations.archiveFailures());
        }finally{catalog.close();}
    }
    private void waitFor(long config,String run)throws Exception{
        long deadline=System.nanoTime()+5_000_000_000L;TestRun state;
        do{state=fixture.tests.get(config,Long.parseLong(run));if(!List.of("QUEUED","RUNNING").contains(state.status()))break;Thread.sleep(10);}while(System.nanoTime()<deadline);
        assertEquals("COMPLETED",state.status());
    }
}
