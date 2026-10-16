package com.xinghuiTec.ai.config;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.xinghuiTec.ai.contract.*;
import com.xinghuiTec.ai.model.*;
import com.xinghuiTec.config.SecretCipher;
import org.junit.jupiter.api.*;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.*;
import org.springframework.transaction.support.TransactionTemplate;

import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.util.*;
import java.util.function.Supplier;

import static com.xinghuiTec.ai.config.AiConfigurationDtos.*;
import static com.xinghuiTec.ai.contract.AiProviderRuntime.*;
import static com.xinghuiTec.ai.contract.AiTypes.*;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

/** H2 + controlled transport evidence, not real vendor validation. */
class AiConfigurationServiceTest {
    JdbcTemplate jdbc; AiConfigurationService service; AiConfigurationTests tests;
    AiApplicationReferences refs; OpenRouterTransport transport; ObjectMapper json;
    AiConfiguredProviderRuntime runtime; ObjectProvider<AiApplicationReferences> provider;
    final InvocationControl control=new InvocationControl() {
        public Instant deadline() { return Instant.now().plusSeconds(10); } public boolean cancelled() { return false; }
    };
    @BeforeEach void setup() throws Exception {
        var source=new DriverManagerDataSource("jdbc:h2:mem:aiv04_"+UUID.randomUUID()+";MODE=MySQL;DB_CLOSE_DELAY=-1","sa","");
        jdbc=new JdbcTemplate(source);
        String sql;
        try(var migration=getClass().getResourceAsStream("/db/migration/V20261016_180__ai_config_v04.sql")) {
            sql=new String(Objects.requireNonNull(migration).readAllBytes(),java.nio.charset.StandardCharsets.UTF_8);
        }
        sql=sql.substring(0,sql.indexOf("-- Existing configurations"));
        sql=sql.replaceAll("(?m)^--.*$","").replace(" COLLATE utf8mb4_bin","").replace(" ENGINE=InnoDB DEFAULT CHARSET=utf8mb4","");
        for(String statement:sql.split(";")) if(!statement.isBlank()) jdbc.execute(statement);
        var tx=new TransactionTemplate(new DataSourceTransactionManager(source));
        refs=mock(AiApplicationReferences.class); provider=mock(ObjectProvider.class);
        when(provider.getIfAvailable()).thenReturn(refs);
        when(refs.withReferenceGuard(any())).thenAnswer(call->tx.execute(status->((Supplier<?>)call.getArgument(0)).get()));
        when(refs.modelReferences(any())).thenReturn(List.of());
        json=new ObjectMapper().findAndRegisterModules();
        var cipher=new SecretCipher(()->Base64.getEncoder().encodeToString(new byte[32]));
        service=new AiConfigurationService(jdbc,json,cipher,new AiModelConfigRules(),tx,provider,mock(AiModelConfigService.class),mock(AiModelCredentialService.class));
        transport=mock(OpenRouterTransport.class);
        when(transport.call(anyString(),anyInt(),anyString(),anyList(),any(),anyInt(),any(),any())).thenAnswer(call->{
            boolean rejected="unit/model-b".equals(call.getArgument(0))&&"controlled-test-key-b".equals(call.getArgument(2));
            return new ExecutionOutcome(rejected?ExecutionStatus.FAILED:ExecutionStatus.SUCCEEDED,rejected?ErrorCode.AUTH_FAILED:null,false,false,new Usage(null,null,null),null,1);
        });
        tests=new AiConfigurationTests(service,transport,tx); runtime=new AiConfiguredProviderRuntime(service,transport);
    }
    @AfterEach void close() { if(tests!=null) tests.close(); }
    DraftChange fresh() {
        return new DraftChange(0,"受控测试","test","openrouter",OpenRouterTransport.ENDPOINT,"OPENAI_CHAT",10,
                List.of(new ModelInput(null,"unit/model-a"),new ModelInput(null,"unit/model-b")),
                List.of(new KeyInput(null,"Key A","DB",null,"controlled-test-key-a"),new KeyInput(null,"Key B","DB",null,"controlled-test-key-b")));
    }
    DraftChange unchanged(View view,int timeout,List<ModelInput> models,List<KeyInput> keys) {
        return new DraftChange(view.revision(),view.name(),view.environment(),view.providerCode(),view.draft().endpointUrl(),view.draft().protocol(),timeout,models,keys);
    }
    List<ModelInput> models(View v) { return v.draft().models().stream().map(m->new ModelInput(m.modelRef(),m.modelCode(),m.jsonObject())).toList(); }
    List<KeyInput> keys(View v) { return v.draft().keys().stream().map(k->new KeyInput(k.keyRef(),k.label(),k.mode(),k.envName(),"")).toList(); }
    TestRun completed(View view) throws Exception {
        long id=Long.parseLong(view.id());
        var run=tests.start(id,new TestStart(view.revision(),view.draft().configVersion(),true));
        long limit=System.nanoTime()+5_000_000_000L;
        while(List.of("RUNNING","QUEUED").contains(run.status())&&System.nanoTime()<limit) { Thread.sleep(10); run=tests.get(id,Long.parseLong(run.id())); }
        assertEquals("COMPLETED",run.status()); return run;
    }
    View active() throws Exception {
        var view=service.create(new Create(fresh(),null)); completed(view);
        return service.activate(Long.parseLong(view.id()),new Activate(view.revision(),view.draft().configVersion(),true));
    }
    @Test void twoByTwoNeedsExplicitPartialActivationAndPublishesOnlyThreeVerifiedPairs() throws Exception {
        var view=service.create(new Create(fresh(),null));
        verifyNoInteractions(transport);
        assertTrue(runtime.availableModels(new Requirements(OutputFormat.TEXT,true,false)).isEmpty());
        assertEquals(4,completed(view).total());
        assertThrows(AiConfigurationException.class,()->service.activate(Long.parseLong(view.id()),new Activate(view.revision(),view.draft().configVersion(),false)));
        var enabled=service.activate(Long.parseLong(view.id()),new Activate(view.revision(),view.draft().configVersion(),true));
        assertEquals(3,enabled.activePairs().size());
        var available=runtime.availableModels(new Requirements(OutputFormat.TEXT,true,false));
        assertEquals(List.of(2,1),available.stream().map(AvailableModel::usableKeyCount).toList());
        assertFalse(json.writeValueAsString(enabled).contains("controlled-test-key"));
        assertFalse(json.writeValueAsString(enabled).contains("proofHash"));
        verify(transport,times(4)).call(anyString(),anyInt(),anyString(),anyList(),any(),anyInt(),any(),any());
    }
    @Test void draftChangesKeepLiveSnapshotAndUnchangedProofCanBeReusedWhileChangedTimeoutCannot() throws Exception {
        var live=active(); long id=Long.parseLong(live.id());
        String pinned=live.activeVersion();
        var same=service.save(id,unchanged(live,10,models(live),keys(live)));
        assertNotEquals(pinned,same.draft().configVersion()); assertEquals(pinned,same.activeVersion());
        var next=service.activate(id,new Activate(same.revision(),same.draft().configVersion(),true));
        assertNotEquals(pinned,next.activeVersion());
        // In-flight requests can still resolve the immutable previous snapshot.
        var binding=new ModelBinding(new ModelVersionRef(live.draft().models().get(0).modelRef(),pinned),KeyMode.AUTO,Set.of(),null,List.of());
        assertEquals(2,runtime.resolveTargets(binding,Set.of(),control).size());
        assertFalse(runtime.validateBindings(List.of(binding),new Requirements(OutputFormat.TEXT,true,false)).valid());
        var changed=service.save(id,unchanged(next,11,models(next),keys(next)));
        assertThrows(AiConfigurationException.class,()->service.activate(id,new Activate(changed.revision(),changed.draft().configVersion(),true)));
        assertEquals(next.activeVersion(),service.get(id).activeVersion());
    }
    @Test void replacementPreservesOldMaterialAndRemovalIsProtectedByOnlineReferences() throws Exception {
        var live=active(); long id=Long.parseLong(live.id());
        var old=live.draft().keys().get(0);
        when(refs.modelReferences(any())).thenReturn(List.of(new AiApplicationReferences.ApplicationReference("app","version","摘要")));
        assertThrows(AiConfigurationException.class,()->service.save(id,unchanged(live,10,models(live),keys(live).subList(1,2))));
        assertEquals(live.revision(),service.get(id).revision());
        when(refs.modelReferences(any())).thenReturn(List.of());
        var changedKeys=new ArrayList<>(keys(live)); changedKeys.set(0,new KeyInput(old.keyRef(),old.label(),"DB",null,"controlled-replacement"));
        var changed=service.save(id,unchanged(live,10,models(live),changedKeys));
        assertNotEquals(old.keyVersion(),changed.draft().keys().get(0).keyVersion());
        assertEquals("controlled-test-key-a",service.resolveSecret(old));
        assertEquals("controlled-replacement",service.resolveSecret(changed.draft().keys().get(0)));
        assertEquals(live.activeVersion(),changed.activeVersion());
    }
    @Test void revisionConflictsRollbackAndMissingReferenceImplementationFailsClosed() {
        var view=service.create(new Create(fresh(),null)); long id=Long.parseLong(view.id());
        var draft=unchanged(view,10,models(view),keys(view));
        service.save(id,draft);
        assertEquals(409,assertThrows(AiConfigurationException.class,()->service.save(id,draft)).status());
        when(provider.getIfAvailable()).thenReturn(null);
        assertEquals(503,assertThrows(AiConfigurationException.class,()->service.archive(id,service.get(id).revision())).status());
    }
    @Test void fixedKeysAndEmergencyDisableAreEnforcedBeforeAnyNewAttempt() throws Exception {
        var live=active(); var model=live.draft().models().get(1); var keys=live.draft().keys();
        var invalid=new ModelBinding(new ModelVersionRef(model.modelRef(),live.activeVersion()),KeyMode.FIXED,Set.of(),new KeyRef(keys.get(1).keyRef(),keys.get(1).keyVersion()),List.of());
        assertTrue(runtime.resolveTargets(invalid,Set.of(),control).isEmpty());
        var auto=new ModelBinding(invalid.model(),KeyMode.AUTO,Set.of(),null,List.of());
        var target=runtime.resolveTargets(auto,Set.of(),control).get(0);
        assertTrue(runtime.resolveTargets(auto,Set.of(target.key()),control).isEmpty());
        service.disable(Long.parseLong(live.id()),live.revision()); clearInvocations(transport);
        var invocation=new Invocation(new TrustedActor("user","tenant",Set.of(),null),"app","v1",new BusinessRef("lowcode-record","1"),"request");
        assertThrows(AiRuntimeException.class,()->runtime.execute(new ExecutionRequest(invocation,"attempt",target,List.of(new AiMessage(MessageRole.USER,"summary")),new Requirements(OutputFormat.TEXT,true,false),128),mock(StreamObserver.class),control));
        verifyNoInteractions(transport);
    }
    @Test void duplicateTestStartIsRejectedAndCancellationDoesNotStartRemainingPairs() throws Exception {
        var entered=new java.util.concurrent.CountDownLatch(1);
        var release=new java.util.concurrent.CountDownLatch(1);
        when(transport.call(anyString(),anyInt(),anyString(),anyList(),any(),anyInt(),any(),any())).thenAnswer(call->{
            entered.countDown(); release.await(3,java.util.concurrent.TimeUnit.SECONDS);
            return new ExecutionOutcome(ExecutionStatus.CANCELLED,ErrorCode.CANCELLED,false,false,null,null,1);
        });
        var view=service.create(new Create(fresh(),null)); long configId=Long.parseLong(view.id());
        var request=new TestStart(view.revision(),view.draft().configVersion(),true);
        var run=tests.start(configId,request);
        try {
            assertTrue(entered.await(3,java.util.concurrent.TimeUnit.SECONDS));
            assertEquals(409,assertThrows(AiConfigurationException.class,()->tests.start(configId,request)).status());
            tests.cancel(configId,Long.parseLong(run.id())); release.countDown();
            long limit=System.nanoTime()+3_000_000_000L;
            while(List.of("RUNNING","QUEUED").contains(run.status())&&System.nanoTime()<limit) { Thread.sleep(10); run=tests.get(configId,Long.parseLong(run.id())); }
            assertEquals("CANCELLED",run.status()); assertEquals(1,run.finished());
            verify(transport,times(1)).call(anyString(),anyInt(),anyString(),anyList(),any(),anyInt(),any(),any());
        } finally { release.countDown(); }
    }
    @Test void legacyImportKeepsOneOriginalCredentialReferenceAndNeverCopiesCiphertextOrEnabledEvidence() {
        var old=mock(AiModelConfigService.class); var credentials=mock(AiModelCredentialService.class);
        long originalId=9007199254740993001L;
        when(old.get(originalId)).thenReturn(new AiModelConfigService.View(originalId,"openrouter","unit/legacy","https://openrouter.ai/api/v1",null,30,true,2,null,null,"DB",true));
        when(credentials.resolve(originalId)).thenReturn("controlled-legacy-key");
        var tx=new TransactionTemplate(new DataSourceTransactionManager(Objects.requireNonNull(jdbc.getDataSource())));
        var legacyService=new AiConfigurationService(jdbc,json,new SecretCipher(()->Base64.getEncoder().encodeToString(new byte[32])),new AiModelConfigRules(),tx,provider,old,credentials);
        var imported=legacyService.create(new Create(null,Long.toString(originalId)));
        assertEquals("DRAFT",imported.status()); assertNull(imported.activeVersion()); assertTrue(imported.activePairs().isEmpty());
        assertEquals(Long.toString(originalId),imported.legacyModelId());
        assertEquals(0,jdbc.queryForObject("SELECT COUNT(*) FROM sys_ai_configuration_key_material WHERE credential_ciphertext IS NOT NULL",Integer.class));
        assertEquals(1,jdbc.queryForObject("SELECT COUNT(*) FROM sys_ai_configuration_key_material",Integer.class));
        assertEquals("controlled-legacy-key",legacyService.resolveSecret(imported.draft().keys().get(0)));
        assertEquals(imported.id(),legacyService.create(new Create(null,Long.toString(originalId))).id());
        when(old.get(originalId)).thenReturn(new AiModelConfigService.View(originalId,"openrouter","unit/legacy","https://openrouter.ai/api/v1",null,30,true,3,null,null,"DB",true));
        assertThrows(AiRuntimeException.class,()->legacyService.resolveSecret(imported.draft().keys().get(0)));
    }
    @Test void expiredDiagnosticBatchesBecomeInterruptedWithoutReplayingOrInterruptingAnotherLiveOwner() {
        var view=service.create(new Create(fresh(),null)); long configId=Long.parseLong(view.id()); long version=Long.parseLong(view.draft().configVersion());
        long expired=service.insert("INSERT INTO sys_ai_configuration_test(config_id,version_id,status,update_time) VALUES(?,?,'RUNNING',?)",configId,version,java.sql.Timestamp.from(Instant.now().minusSeconds(120)));
        long live=service.insert("INSERT INTO sys_ai_configuration_test(config_id,version_id,status) VALUES(?,?,'RUNNING')",configId,version);
        jdbc.update("UPDATE sys_ai_configuration SET running_test_id=? WHERE config_id=?",live,configId);
        tests.recover();
        assertEquals("INTERRUPTED",tests.get(configId,expired).status());
        assertEquals("RUNNING",tests.get(configId,live).status());
        assertEquals(live,jdbc.queryForObject("SELECT running_test_id FROM sys_ai_configuration WHERE config_id=?",Long.class,configId));
        verifyNoInteractions(transport);
    }
    @Test void jsonCapabilityIsOptInAndChangesRequireNewPairProof() throws Exception {
        var initial=fresh();
        var view=service.create(new Create(new DraftChange(0,initial.name(),initial.environment(),initial.providerCode(),initial.endpointUrl(),initial.protocol(),initial.timeoutSeconds(),
                List.of(new ModelInput(null,"openai/gpt-4o-mini")),initial.keys().subList(0,1)),null));
        completed(view);
        var live=service.activate(Long.parseLong(view.id()),new Activate(view.revision(),view.draft().configVersion(),false));
        var jsonRequirements=new Requirements(OutputFormat.JSON_OBJECT,true,false);
        assertTrue(runtime.availableModels(jsonRequirements).isEmpty());
        var oldBinding=new ModelBinding(new ModelVersionRef(live.draft().models().get(0).modelRef(),live.activeVersion()),KeyMode.AUTO,Set.of(),null,List.of());
        var oldTarget=runtime.resolveTargets(oldBinding,Set.of(),control).get(0);
        var forged=new Target(oldTarget.model(),oldTarget.key(),oldTarget.providerCode(),oldTarget.modelCode(),oldTarget.maskedKeyLabel(),new Capabilities(Set.of(OutputFormat.TEXT,OutputFormat.JSON_OBJECT),true,false));
        var invocation=new Invocation(new TrustedActor("user","tenant",Set.of(),null),"1","draft:1",new BusinessRef("lowcode.record","1:1"),"request");
        clearInvocations(transport);
        assertEquals(ErrorCode.CAPABILITY_UNSUPPORTED,assertThrows(AiRuntimeException.class,()->runtime.execute(new ExecutionRequest(invocation,"attempt",forged,List.of(new AiMessage(MessageRole.USER,"JSON test")),jsonRequirements,128),mock(StreamObserver.class),control)).code());
        verifyNoInteractions(transport);
        var changed=service.save(Long.parseLong(live.id()),unchanged(live,live.draft().timeoutSeconds(),List.of(new ModelInput(live.draft().models().get(0).modelRef(),"openai/gpt-4o-mini",true)),keys(live)));
        assertTrue(changed.draft().models().get(0).jsonObject());
        assertEquals(OpenRouterCapabilities.EVIDENCE,changed.draft().models().get(0).capabilityEvidence());
        assertThrows(AiConfigurationException.class,()->service.activate(Long.parseLong(changed.id()),new Activate(changed.revision(),changed.draft().configVersion(),false)));
        completed(changed);
        verify(transport).call(eq("openai/gpt-4o-mini"),anyInt(),anyString(),anyList(),eq(new Requirements(OutputFormat.JSON_OBJECT,false,false)),anyInt(),any(),any());
        var enabled=service.activate(Long.parseLong(changed.id()),new Activate(changed.revision(),changed.draft().configVersion(),false));
        var available=runtime.availableModels(jsonRequirements); assertEquals(1,available.size()); assertTrue(available.get(0).capabilities().supports(jsonRequirements));
        var binding=new ModelBinding(available.get(0).model(),KeyMode.AUTO,Set.of(),null,List.of());
        assertTrue(runtime.validateBindings(List.of(binding),jsonRequirements).valid());
        assertTrue(runtime.resolveTargets(binding,Set.of(),control).get(0).capabilities().outputFormats().contains(OutputFormat.JSON_OBJECT));
        var old=service.versions(Long.parseLong(enabled.id())).stream().filter(v->v.configVersion().equals(live.activeVersion())).findFirst().orElseThrow();
        assertFalse(old.snapshot().models().get(0).jsonObject());
    }
    @Test void rejectsUnconfirmedJsonMetadataAndReadsOldSnapshotsAsText() throws Exception {
        var model=json.readValue("{\"modelRef\":\"1\",\"modelCode\":\"openai/gpt-4o-mini\"}",ModelSpec.class);
        assertFalse(OpenRouterCapabilities.forModel(model).outputFormats().contains(OutputFormat.JSON_OBJECT));
        assertFalse(OpenRouterCapabilities.forModel(new ModelSpec("1","unknown/model",true,OpenRouterCapabilities.EVIDENCE)).outputFormats().contains(OutputFormat.JSON_OBJECT));
        var d=fresh();
        assertThrows(AiConfigurationException.class,()->service.create(new Create(new DraftChange(0,d.name(),d.environment(),d.providerCode(),d.endpointUrl(),d.protocol(),d.timeoutSeconds(),List.of(new ModelInput(null,"unit/model-a",true)),d.keys()),null)));
        verifyNoInteractions(transport);
    }
}
