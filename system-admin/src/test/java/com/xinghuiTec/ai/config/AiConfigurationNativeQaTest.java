package com.xinghuiTec.ai.config;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.xinghuiTec.ai.contract.*;
import com.xinghuiTec.ai.model.*;
import com.xinghuiTec.config.SecretCipher;
import org.flywaydb.core.Flyway;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.*;
import org.springframework.transaction.support.TransactionTemplate;

import java.util.*;
import java.util.function.Supplier;

import static com.xinghuiTec.ai.config.AiConfigurationDtos.*;
import static com.xinghuiTec.ai.contract.AiProviderRuntime.*;
import static com.xinghuiTec.ai.contract.AiTypes.*;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

/** A's dedicated schema only. Transport/references are explicit test fixtures, never production beans. */
@EnabledIfEnvironmentVariable(named="AI_V04_A_NATIVE",matches="true")
class AiConfigurationNativeQaTest {
    @Test void applyBaselineAndV180ThenPersistControlledThreeOfFourPairEvidence() throws Exception {
        var source=new DriverManagerDataSource("jdbc:mysql://127.0.0.1:13319/aiv04_a_20261016?useSSL=false&allowPublicKeyRetrieval=true&serverTimezone=Asia/Shanghai","ai_v04_a",System.getenv("AI_V04_A_DB_PASSWORD"));
        var jdbc=new JdbcTemplate(source);
        assertEquals("aiv04_a_20261016",jdbc.queryForObject("SELECT DATABASE()",String.class));
        var flyway=Flyway.configure().dataSource(source).locations("classpath:db/migration").target("20261016.180").load();
        flyway.migrate(); flyway.validate();
        assertEquals(4,jdbc.queryForObject("SELECT COUNT(*) FROM sys_menu WHERE parent_id=0 AND menu_type='M' AND is_deleted=0",Integer.class));
        assertEquals(List.of("/ai/configurations","/ai/applications","/ai/templates","/ai/requests"),jdbc.query("SELECT path FROM sys_menu WHERE parent_id=202610160180000001 ORDER BY order_num",(rs,n)->rs.getString(1)));
        var tx=new TransactionTemplate(new DataSourceTransactionManager(source));
        var provider=mock(ObjectProvider.class); var refs=mock(AiApplicationReferences.class);
        when(provider.getIfAvailable()).thenReturn(refs);
        when(refs.withReferenceGuard(any())).thenAnswer(call->tx.execute(status->((Supplier<?>)call.getArgument(0)).get()));
        when(refs.modelReferences(any())).thenReturn(List.of());
        var json=new ObjectMapper().findAndRegisterModules();
        var cipher=new SecretCipher(()->Base64.getEncoder().encodeToString(new byte[32]));
        var legacy=new AiModelConfigService(jdbc,new AiModelConfigRules());
        var service=new AiConfigurationService(jdbc,json,cipher,new AiModelConfigRules(),tx,provider,legacy,new AiModelCredentialService(jdbc,cipher,legacy));
        var transport=mock(OpenRouterTransport.class);
        when(transport.call(anyString(),anyInt(),anyString(),anyList(),any(),anyInt(),any(),any())).thenAnswer(call->{
            boolean failed="controlled/model-b".equals(call.getArgument(0))&&"controlled-fixture-b".equals(call.getArgument(2));
            return new ExecutionOutcome(failed?ExecutionStatus.FAILED:ExecutionStatus.SUCCEEDED,failed?ErrorCode.AUTH_FAILED:null,false,false,new Usage(null,null,null),null,1);
        });
        var view=service.create(new Create(new DraftChange(0,"A-NATIVE-CONTROLLED-FIXTURE","qa","openrouter",OpenRouterTransport.ENDPOINT,"OPENAI_CHAT",5,
                List.of(new ModelInput(null,"controlled/model-a"),new ModelInput(null,"controlled/model-b")),
                List.of(new KeyInput(null,"测试Key A","DB",null,"controlled-fixture-a"),new KeyInput(null,"测试Key B","DB",null,"controlled-fixture-b"))),null));
        long configId=Long.parseLong(view.id());
        try {
            var tests=new AiConfigurationTests(service,transport,tx);
            try {
                var run=tests.start(configId,new TestStart(view.revision(),view.draft().configVersion(),true));
                long limit=System.nanoTime()+10_000_000_000L;
                while(List.of("RUNNING","QUEUED").contains(run.status())&&System.nanoTime()<limit) { Thread.sleep(20); run=tests.get(configId,Long.parseLong(run.id())); }
                assertEquals("COMPLETED",run.status());
                assertEquals(3,run.pairs().stream().filter(pair->"SUCCESS".equals(pair.status())).count());
                assertThrows(AiConfigurationException.class,()->service.activate(configId,new Activate(view.revision(),view.draft().configVersion(),false)));
                var active=service.activate(configId,new Activate(view.revision(),view.draft().configVersion(),true));
                assertEquals(3,active.activePairs().size());
                var reloaded=new AiConfigurationService(jdbc,json,cipher,new AiModelConfigRules(),tx,provider,legacy,new AiModelCredentialService(jdbc,cipher,legacy));
                assertEquals(active,reloaded.get(configId));
                String safe=json.writeValueAsString(reloaded.get(configId));
                assertFalse(safe.contains("controlled-fixture-a")); assertFalse(safe.contains("credential_ciphertext"));
                assertEquals(1,service.page(1,10,"A-NATIVE-CONTROLLED-FIXTURE",null,null,null).total());
                var runtime=new AiConfiguredProviderRuntime(service,transport);
                var model=active.draft().models().get(0);
                var binding=new ModelBinding(new ModelVersionRef(model.modelRef(),active.activeVersion()),KeyMode.AUTO,Set.of(),null,List.of());
                var control=new InvocationControl() {
                    public java.time.Instant deadline() { return java.time.Instant.now().plusSeconds(5); }
                    public boolean cancelled() { return false; }
                };
                assertEquals(2,runtime.resolveTargets(binding,Set.of(),control).size());
                tx.setIsolationLevel(org.springframework.transaction.TransactionDefinition.ISOLATION_REPEATABLE_READ);
                tx.execute(status->{
                    assertEquals("ACTIVE",jdbc.queryForObject("SELECT status FROM sys_ai_configuration WHERE config_id=?",String.class,configId));
                    var independent=new TransactionTemplate(new DataSourceTransactionManager(source));
                    independent.setPropagationBehavior(org.springframework.transaction.TransactionDefinition.PROPAGATION_REQUIRES_NEW);
                    independent.execute(inner->service.disable(configId,active.revision()));
                    assertEquals("ACTIVE",jdbc.queryForObject("SELECT status FROM sys_ai_configuration WHERE config_id=?",String.class,configId));
                    assertThrows(AiRuntimeException.class,()->runtime.resolveTargets(binding,Set.of(),control));
                    return null;
                });
                System.out.println("AI_V04_A_NATIVE migrations="+flyway.info().applied().length+" menus=4 pairs=3/4 persistence=PASS vendor=MOCK references=TEST_ONLY");
                System.out.println("AI_V04_A_NATIVE staleRR=REPRODUCED emergencyCurrentRead=PASS");
            } finally { tests.close(); }
        } finally {
            // Delete only this test's exact aggregate, respecting FK order. No user rows or broad cleanup.
            jdbc.update("DELETE FROM sys_ai_configuration_test_pair WHERE test_id IN (SELECT test_id FROM sys_ai_configuration_test WHERE config_id=?)",configId);
            jdbc.update("DELETE FROM sys_ai_configuration_test WHERE config_id=?",configId);
            jdbc.update("DELETE FROM sys_ai_configuration_version WHERE config_id=?",configId);
            jdbc.update("DELETE FROM sys_ai_configuration_key_material WHERE key_ref IN (SELECT key_ref FROM sys_ai_configuration_key WHERE config_id=?)",configId);
            jdbc.update("DELETE FROM sys_ai_configuration_key WHERE config_id=?",configId);
            jdbc.update("DELETE FROM sys_ai_configuration_model WHERE config_id=?",configId);
            jdbc.update("DELETE FROM sys_ai_configuration WHERE config_id=?",configId);
        }
    }
}
