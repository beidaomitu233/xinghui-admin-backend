package com.xinghuiTec.ai.scene;

import com.xinghuiTec.ai.application.*;
import com.xinghuiTec.ai.config.*;
import com.xinghuiTec.ai.contract.*;
import com.xinghuiTec.ai.contract.AiTypes.*;
import com.xinghuiTec.ai.model.*;
import com.xinghuiTec.ai.modelhub.*;
import com.xinghuiTec.config.SecretCipher;
import org.junit.jupiter.api.*;
import org.springframework.transaction.support.TransactionTemplate;
import org.springframework.context.annotation.*;
import org.springframework.security.config.annotation.method.configuration.EnableMethodSecurity;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import com.xinghuiTec.domain.entity.*;
import com.xinghuiTec.security.PlatformAccessService;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import java.time.Instant;
import java.util.*;
import java.util.concurrent.TimeUnit;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

/** Real configured provider/executor/ledger; HTTP alone is controlled. Not native DB or live vendor evidence. */
public class ModelHubRuntimeTest {
    private SceneTestSupport f;
    private AiConfigurationService configs;
    private AiConfigurationTests probes;
    private SqlAssistanceHttpFixture http;
    private AiConfiguredProviderRuntime provider;
    private ModelHubService hub;
    private ModelHubTestService tests;
    private TrustedActor actor;
    private String channelA,channelB;
    @BeforeEach void setup()throws Exception{
        f=new SceneTestSupport();f.script(f.read("V20261016_180__ai_config_v04.sql").split("-- Existing configurations")[0]);f.script(f.read("V20261018_245__ai_key_health.sql"));f.script(f.read("V20261018_248__business_model_hub.sql"));
        // H2-only equivalents for production MySQL JSON metadata filtering; not native MySQL evidence.
        f.jdbc.execute("CREATE ALIAS IF NOT EXISTS JSON_EXTRACT FOR 'com.xinghuiTec.ai.scene.ModelHubRuntimeTest.extractName'");f.jdbc.execute("CREATE ALIAS IF NOT EXISTS JSON_UNQUOTE FOR 'com.xinghuiTec.ai.scene.ModelHubRuntimeTest.unquote'");
        var rights=new HashSet<>(f.actor.authorities());rights.addAll(Set.of("ai:config:add","ai:config:edit","ai:config:enable","ai:config:test","ai:config:query"));actor=new TrustedActor("101","tenant_a",rights,null);
        var cipher=new SecretCipher(()->Base64.getEncoder().encodeToString(new byte[32]));
        configs=new AiConfigurationService(f.jdbc,f.mapper,cipher,new AiModelConfigRules(),new TransactionTemplate(f.manager),f.beans.getBeanProvider(AiApplicationReferences.class),mock(AiModelConfigService.class),mock(AiModelCredentialService.class));
        http=new SqlAssistanceHttpFixture(f.mapper);probes=new AiConfigurationTests(configs,http.transport,new TransactionTemplate(f.manager));
        channelA=channel("Primary","unit/model-a","controlled-primary");channelB=channel("Backup","unit/model-b","controlled-secondary");
        provider=spy(new AiConfiguredProviderRuntime(configs,http.transport));f.beans.addBean("provider",provider);
        hub=new ModelHubService(f.jdbc,f.mapper,configs,f.beans.getBeanProvider(AiProviderRuntime.class),f.references,cipher);f.management.modelHub(hub);f.runtime.modelHub(hub);
        tests=new ModelHubTestService(hub,f.references,f.applications,f.requests,f.lifecycle,f.runtime,f.jdbc,f.mapper);
    }
    public static String extractName(String value,String path)throws Exception{if(!"$.name".equals(path))throw new IllegalArgumentException();return new com.fasterxml.jackson.databind.ObjectMapper().readTree(value).path("name").toString();}
    public static String unquote(String value)throws Exception{return new com.fasterxml.jackson.databind.ObjectMapper().readTree(value).asText();}
    private String channel(String name,String model,String key)throws Exception{
        var created=configs.create(new AiConfigurationDtos.Create(new AiConfigurationDtos.DraftChange(0,name,"qa","openrouter",OpenRouterTransport.ENDPOINT,"OPENAI_CHAT",120,List.of(new AiConfigurationDtos.ModelInput(null,model)),List.of(new AiConfigurationDtos.KeyInput(null,name,"DB",null,key))),null));
        long id=Long.parseLong(created.id());var probe=probes.start(id,new AiConfigurationDtos.TestStart(created.revision(),created.draft().configVersion(),true));Instant deadline=Instant.now().plusSeconds(5);
        while((Set.of("QUEUED","RUNNING").contains(probes.get(id,Long.parseLong(probe.id())).status())||f.jdbc.queryForObject("SELECT running_test_id FROM sys_ai_configuration WHERE config_id=?",Long.class,id)!=null)&&Instant.now().isBefore(deadline))Thread.sleep(5);
        configs.activate(id,new AiConfigurationDtos.Activate(created.revision(),created.draft().configVersion(),false));return created.id();
    }
    @AfterEach void close(){SecurityContextHolder.clearContext();if(http!=null)http.release();if(probes!=null)probes.close();if(f!=null)f.close();}
    private List<ModelHubDtos.Route> routes(){return List.of(new ModelHubDtos.Route(null,channelA,"unit/model-a",true),new ModelHubDtos.Route(null,channelB,"unit/model-b",true));}
    private ModelHubDtos.Change change(Integer revision,boolean enabled,ModelHubDtos.Strategy strategy,boolean fallback,List<ModelHubDtos.Route> routes){return new ModelHubDtos.Change(revision,UUID.randomUUID().toString(),"Independent model","business-chat","对话 / 推理","128K",enabled,strategy,fallback,routes);}
    private ModelHubDtos.Model model(ModelHubDtos.Strategy strategy,boolean fallback){return hub.save(null,change(null,true,strategy,fallback,routes()),actor);}
    private ModelHubDtos.TestView test(ModelHubDtos.Model m){return tests.start(m.modelId(),new ModelHubDtos.Test(m.revision(),UUID.randomUUID().toString(),true),actor);}
    private ModelHubDtos.TestView finished(ModelHubDtos.Model m,String request)throws Exception{
        Instant end=Instant.now().plusSeconds(5);while(Instant.now().isBefore(end)){var r=tests.get(m.modelId(),request,actor);if(!Set.of("QUEUED","RUNNING").contains(r.status()))return r;Thread.sleep(5);}throw new AssertionError("controlled model hub test timed out");
    }
    @Test void independentModelPersistsWithoutBindingAndHealthDoesNotPretendNetworkSuccess(){
        var input=change(null,true,ModelHubDtos.Strategy.PRIORITY,true,List.of());var model=hub.save(null,input,actor);
        assertEquals("Independent model",model.name());assertEquals("business-chat",model.slug());assertFalse(model.health().ready());assertTrue(model.routes().isEmpty());assertEquals(model.modelId(),hub.bySlug("business-chat").modelId());assertEquals(0,http.generations.get());
        assertEquals(model,hub.save(null,input,actor));assertEquals("1",hub.page(1,20,"independent").total());
    }
    @Test void databaseSearchPagesHaveAccurateTotalsEscapedWildcardsAndBoundedWholeSummary(){
        model(ModelHubDtos.Strategy.PRIORITY,true);
        for(int i=0;i<6;i++)hub.save(null,new ModelHubDtos.Change(null,UUID.randomUUID().toString(),i==5?"Literal_100%":"Group Alpha "+i,"group-"+i,"对话","128K",false,ModelHubDtos.Strategy.PRIORITY,true,List.of()),actor);
        clearInvocations(provider);var second=hub.page(2,2,"gRoUp aLpHa");verify(provider,times(1)).availableModels(any());
        assertEquals("5",second.total());assertEquals(List.of("group-2","group-3"),second.records().stream().map(ModelHubDtos.Model::slug).toList());
        assertEquals(1,hub.page(3,2,"Group Alpha").records().size());assertTrue(hub.page(4,2,"Group Alpha").records().isEmpty());
        assertEquals("1",hub.page(1,2,"100%").total());assertEquals("1",hub.page(1,2,"Literal_").total());
        assertEquals("7",hub.page(1,2,null).total());assertEquals(2,hub.page(1,2,null).records().size());
        clearInvocations(provider);var summary=hub.summary();verify(provider,times(1)).availableModels(any());
        assertEquals(7,summary.totalModels());assertEquals(1,summary.enabledModels());assertEquals(1,summary.readyModels());assertEquals(6,summary.disabledModels());assertEquals(0,summary.unconfiguredModels());assertEquals(0,summary.multiProviderModels());assertEquals(1,summary.assuranceNeededModels());assertEquals(512,summary.modelLimit());assertEquals(0,http.generations.get());
    }
    @Test void configurationLimitRejectsCreationAndSummaryNeverTruncatesItsTotal(){
        var batch=new ArrayList<Object[]>();for(int i=0;i<ModelHubService.MODEL_LIMIT;i++){
            var d=new ModelHubDtos.Change(null,null,"Bounded "+i,"bounded-"+i,"对话","128K",false,ModelHubDtos.Strategy.PRIORITY,true,List.of());
            try{batch.add(new Object[]{d.slug(),f.mapper.writeValueAsString(d)});}catch(Exception invalid){throw new AssertionError(invalid);}
        }
        f.jdbc.batchUpdate("INSERT INTO sys_ai_business_model(slug,definition_json,enabled) VALUES(?,?,FALSE)",batch);
        assertEquals("MODEL_LIMIT_EXCEEDED",assertThrows(SceneFailure.class,()->hub.save(null,change(null,true,ModelHubDtos.Strategy.PRIORITY,true,List.of()),actor)).getMessage());
        clearInvocations(provider);assertEquals(512,hub.summary().totalModels());verify(provider,times(1)).availableModels(any());assertEquals("512",hub.page(1,2,null).total());
    }
    @Test void revisionStableSlugEnablePermissionAndActualChannelAssociationAreEnforced(){
        var m=model(ModelHubDtos.Strategy.PRIORITY,true);var noEnable=new TrustedActor(actor.subjectId(),actor.tenantId(),Set.of("ai:config:edit"),null);
        assertEquals(403,assertThrows(SceneFailure.class,()->hub.save(m.modelId(),change(m.revision(),false,ModelHubDtos.Strategy.PRIORITY,true,routes()),noEnable)).status());
        var disabled=hub.enabled(m.modelId(),new ModelHubDtos.Enabled(m.revision(),UUID.randomUUID().toString(),false),actor);assertFalse(disabled.health().ready());
        assertEquals(409,assertThrows(SceneFailure.class,()->hub.save(m.modelId(),change(m.revision(),true,ModelHubDtos.Strategy.PRIORITY,true,routes()),actor)).status());
        var rename=new ModelHubDtos.Change(disabled.revision(),UUID.randomUUID().toString(),m.name(),"different-slug",m.capabilities(),m.contextWindow(),false,m.strategy(),m.fallback(),List.of());
        assertEquals("MODEL_SLUG_IMMUTABLE",assertThrows(SceneFailure.class,()->hub.save(m.modelId(),rename,actor)).getMessage());
        var forged=change(disabled.revision(),false,ModelHubDtos.Strategy.PRIORITY,true,List.of(new ModelHubDtos.Route(null,channelA,"arbitrary/not-in-channel",true)));
        assertEquals("UPSTREAM_NOT_CONFIGURED",assertThrows(SceneFailure.class,()->hub.save(m.modelId(),forged,actor)).getMessage());
    }
    @Test void configuredPriorityFallbackRunsRealProviderAttemptsAndLogsWithoutPublishingOutput()throws Exception{
        var m=model(ModelHubDtos.Strategy.PRIORITY,true);assertTrue(m.health().ready());assertEquals(1,m.health().distinctProviders());assertFalse(m.health().multiProviderProtected());
        http.primaryStatus=503;var accepted=test(m);var done=finished(m,accepted.requestId());
        assertEquals("SUCCEEDED",done.status());assertEquals(2,done.attemptCount());assertEquals(List.of("unit/model-a","unit/model-b"),http.generationModels);
        assertEquals(List.of("FAILED","SUCCEEDED"),done.attempts().stream().map(ModelHubDtos.TestAttempt::status).toList());assertEquals(List.of("unit/model-a","unit/model-b"),done.attempts().stream().map(ModelHubDtos.TestAttempt::upstreamModel).toList());assertEquals(List.of(1,2),done.attempts().stream().map(ModelHubDtos.TestAttempt::number).toList());assertTrue(done.attempts().stream().allMatch(a->a.elapsedMs()!=null));
        assertNotNull(http.systemMessage);assertEquals("Reply with OK.",http.systemMessage);
        assertEquals(2,f.jdbc.queryForObject("SELECT COUNT(*) FROM sys_ai_request_attempt WHERE request_id=?",Integer.class,done.requestId()));
        assertEquals(ErrorCode.NOT_FOUND,assertThrows(AiRuntimeException.class,()->f.runtime.status(Long.parseLong(done.requestId()),actor)).code());
        var replay=tests.start(m.modelId(),new ModelHubDtos.Test(m.revision(),f.jdbc.queryForObject("SELECT test_mutation FROM sys_ai_business_model_request WHERE request_id=?",String.class,done.requestId()),true),actor);assertEquals(done.requestId(),replay.requestId());assertEquals(2,http.generations.get());
    }
    @Test void noFallbackAndAllProvidersFailureNeverProduceFakeSuccess()throws Exception{
        http.primaryStatus=503;var m=model(ModelHubDtos.Strategy.PRIORITY,false);var first=finished(m,test(m).requestId());assertEquals("FAILED",first.status());assertEquals(1,first.attemptCount());
        var changed=hub.save(m.modelId(),change(m.revision(),true,ModelHubDtos.Strategy.PRIORITY,true,routes()),actor);http.secondaryStatus=503;var second=finished(changed,test(changed).requestId());assertEquals("FAILED",second.status());assertEquals(2,second.attemptCount());
    }
    @Test void roundRobinUsesPersistentBusinessCursorAcrossSeparateRequests()throws Exception{
        var m=model(ModelHubDtos.Strategy.ROUND_ROBIN,false);assertEquals("SUCCEEDED",finished(m,test(m).requestId()).status());assertEquals("SUCCEEDED",finished(m,test(m).requestId()).status());
        assertEquals(List.of("unit/model-a","unit/model-b"),http.generationModels);assertEquals(2L,f.jdbc.queryForObject("SELECT next_index FROM sys_ai_business_model WHERE model_id=?",Long.class,m.modelId()));
    }
    @Test void inflightRouteRemainsPinnedWhileNextRequestUsesChangedModelConfiguration()throws Exception{
        var m=model(ModelHubDtos.Strategy.PRIORITY,true);http.hold=true;var pending=test(m);assertTrue(http.entered.await(2,TimeUnit.SECONDS));
        var next=hub.save(m.modelId(),change(m.revision(),true,ModelHubDtos.Strategy.PRIORITY,true,List.of(new ModelHubDtos.Route(null,channelB,"unit/model-b",true))),actor);
        http.hold=false;http.release();assertEquals("SUCCEEDED",finished(m,pending.requestId()).status());assertEquals("SUCCEEDED",finished(next,test(next).requestId()).status());
        assertEquals(List.of("unit/model-a","unit/model-b"),http.generationModels);assertEquals(m.revision(),f.jdbc.queryForObject("SELECT model_revision FROM sys_ai_business_model_request WHERE request_id=?",Integer.class,pending.requestId()));
    }
    @Test void newSceneUsesStableModelAndPromptSavePreservesChoiceWhileDeletionIsBlocked()throws Exception{
        var m=model(ModelHubDtos.Strategy.PRIORITY,false);var created=f.management.create(new SceneDtos.Create("Logical scene","",SceneDtos.Kind.GENERAL,null,UUID.randomUUID().toString()),actor);
        var definition=new SceneDtos.Definition("Logical scene","","{{user_request}}","",List.of(),null,List.of(),null,List.of(),m.slug());
        var saved=f.management.save(created.scene().sceneKey(),new SceneDtos.Save(0,UUID.randomUUID().toString(),definition),actor);
        assertEquals(m.slug(),saved.detail().definition().businessModel());assertEquals("SCENE_MODEL_IN_USE",assertThrows(SceneFailure.class,()->hub.delete(m.modelId(),m.revision())).getMessage());
        var missingChoice=new SceneDtos.Definition("Logical scene","","Answer {{user_request}}","",List.of(),null);
        var edited=f.management.save(created.scene().sceneKey(),new SceneDtos.Save(saved.detail().scene().revision(),UUID.randomUUID().toString(),missingChoice),actor);assertEquals(m.slug(),edited.detail().definition().businessModel());
        var request=f.service.submit(created.scene().sceneKey(),new SceneDtos.Submission(null,Map.of("user_request","Hello"),UUID.randomUUID().toString()),actor);
        Instant end=Instant.now().plusSeconds(5);while(!f.requests.owned(Long.parseLong(request.requestId()),new AiRequestStore.Owner(actor.subjectId(),actor.tenantId())).status().terminal()&&Instant.now().isBefore(end))Thread.sleep(5);
        assertEquals(AiRequestStatus.SUCCEEDED,f.requests.owned(Long.parseLong(request.requestId()),new AiRequestStore.Owner(actor.subjectId(),actor.tenantId())).status());assertEquals(List.of("unit/model-a"),http.generationModels);
        assertTrue(f.jdbc.queryForObject("SELECT route_json FROM sys_ai_business_model_request WHERE request_id=?",String.class,request.requestId()).contains("modelRef"));
    }
    @Test void ownerTenantAndCostGatesApplyToPrivateModelTests()throws Exception{
        var m=model(ModelHubDtos.Strategy.PRIORITY,true);assertEquals(400,assertThrows(SceneFailure.class,()->tests.start(m.modelId(),new ModelHubDtos.Test(m.revision(),UUID.randomUUID().toString(),false),actor)).status());
        var done=finished(m,test(m).requestId());var other=new TrustedActor("102","tenant_b",actor.authorities(),null);assertEquals(404,assertThrows(SceneFailure.class,()->tests.get(m.modelId(),done.requestId(),other)).status());
        hub.delete(m.modelId(),m.revision());assertEquals(404,assertThrows(SceneFailure.class,()->hub.get(m.modelId())).status());
    }
    @Test void privateCancellationStopsSameRequestAndIsOwnerScopedAndTerminalIdempotent()throws Exception{
        var unknown=hub.save(null,new ModelHubDtos.Change(null,UUID.randomUUID().toString(),"Unknown metadata","unknown-metadata",null,null,false,ModelHubDtos.Strategy.PRIORITY,true,List.of()),actor);
        assertEquals("",unknown.capabilities());assertEquals("",unknown.contextWindow());assertFalse(unknown.health().ready());
        var m=model(ModelHubDtos.Strategy.PRIORITY,true);http.hold=true;var pending=test(m);assertTrue(http.entered.await(2,TimeUnit.SECONDS));
        for(var other:List.of(new TrustedActor("102","tenant_a",actor.authorities(),null),new TrustedActor("101","tenant_b",actor.authorities(),null)))assertEquals(404,assertThrows(SceneFailure.class,()->tests.cancel(m.modelId(),pending.requestId(),other)).status());
        var cancelled=tests.cancel(m.modelId(),pending.requestId(),actor);assertEquals(pending.requestId(),cancelled.requestId());assertEquals("CANCELLED",cancelled.status());
        Instant end=Instant.now().plusSeconds(2);while(!http.pending.isCancelled()&&Instant.now().isBefore(end))Thread.sleep(5);assertTrue(http.pending.isCancelled());
        assertEquals("CANCELLED",tests.cancel(m.modelId(),pending.requestId(),actor).status());assertEquals(1,http.generations.get());
        assertEquals(ErrorCode.NOT_FOUND,assertThrows(AiRuntimeException.class,()->f.runtime.cancel(Long.parseLong(pending.requestId()),actor)).code());
        http.hold=false;var done=finished(m,test(m).requestId());assertEquals("SUCCEEDED",done.status());assertEquals("SUCCEEDED",tests.cancel(m.modelId(),done.requestId(),actor).status());assertEquals(2,http.generations.get());
    }
    @Configuration @EnableMethodSecurity static class Security {}
    private void login(String tenant,Set<String> rights){var user=new SysUser();user.setUserId(101L);user.setTenantId(tenant);var login=new LoginUser(user,new ArrayList<>(rights));login.setTenantId(tenant);SecurityContextHolder.getContext().setAuthentication(new UsernamePasswordAuthenticationToken(login,null,login.getAuthorities()));}
    @Test void actualHttpEnforcesPlatformActionPermissionsStrictBodyAndDedicatedTestAccess()throws Exception{
        try(var context=new AnnotationConfigApplicationContext()){
            context.register(Security.class);context.registerBean("platformAccess",PlatformAccessService.class,()->new PlatformAccessService(true));context.registerBean(ModelHubController.class,()->new ModelHubController(hub,tests,new AiInvocationIdentity(),new AiRuntimeBodyCodec()));context.refresh();
            var mvc=MockMvcBuilders.standaloneSetup(context.getBean(ModelHubController.class)).setControllerAdvice(new SceneHttpErrors()).addFilters(new AiRuntimeBoundaryFilter(f.mapper)).build();String base="/system/ai/settings/model-hub/models";
            login("tenant_a",actor.authorities());mvc.perform(get(base)).andExpect(status().isForbidden());
            login("000000",Set.of("ai:config:query"));mvc.perform(get(base)).andExpect(status().isOk());mvc.perform(post(base).contentType("application/json").content(f.mapper.writeValueAsString(change(null,false,ModelHubDtos.Strategy.PRIORITY,true,List.of())))).andExpect(status().isForbidden());
            login("000000",Set.of("ai:config:add"));mvc.perform(post(base).contentType("application/json").content(f.mapper.writeValueAsString(change(null,true,ModelHubDtos.Strategy.PRIORITY,true,List.of())))).andExpect(status().isForbidden());
            String valid=f.mapper.writeValueAsString(change(null,false,ModelHubDtos.Strategy.PRIORITY,true,List.of()));mvc.perform(post(base).contentType("application/json").content(valid.substring(0,valid.length()-1)+",\"actor\":\"forged\"}")).andExpect(status().isBadRequest());
            mvc.perform(post(base).contentType("application/json").content(valid)).andExpect(status().isOk());
            var saved=hub.bySlug("business-chat");saved=hub.enabled(saved.modelId(),new ModelHubDtos.Enabled(saved.revision(),UUID.randomUUID().toString(),true),actor);saved=hub.save(saved.modelId(),change(saved.revision(),true,ModelHubDtos.Strategy.PRIORITY,true,routes()),actor);
            login("000000",Set.of("ai:config:test"));var accepted=mvc.perform(post(base+"/"+saved.modelId()+"/tests").contentType("application/json").content(f.mapper.writeValueAsString(new ModelHubDtos.Test(saved.revision(),UUID.randomUUID().toString(),true)))).andExpect(status().isAccepted()).andReturn();
            String request=f.mapper.readTree(accepted.getResponse().getContentAsString()).at("/data/requestId").asText();
            Instant end=Instant.now().plusSeconds(5);while(!f.requests.owned(Long.parseLong(request),new AiRequestStore.Owner("101","000000")).status().terminal()&&Instant.now().isBefore(end))Thread.sleep(5);
            mvc.perform(get(base+"/"+saved.modelId()+"/tests/"+request)).andExpect(status().isOk()).andExpect(jsonPath("$.data.attempts[0].upstreamModel").value("unit/model-a"));
        }
    }
}
