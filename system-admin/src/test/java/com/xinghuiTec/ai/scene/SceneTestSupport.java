package com.xinghuiTec.ai.scene;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.xinghuiTec.ai.application.*;
import com.xinghuiTec.ai.contract.*;
import com.xinghuiTec.ai.contract.AiProviderRuntime.*;
import com.xinghuiTec.ai.contract.AiTypes.*;
import com.xinghuiTec.ai.scenedata.SceneDataService;
import com.xinghuiTec.ai.scenedata.LegacySceneTemplates;
import com.xinghuiTec.config.SecretCipher;
import com.xinghuiTec.lowcode.*;
import org.springframework.beans.factory.support.StaticListableBeanFactory;
import org.springframework.core.io.ByteArrayResource;
import org.springframework.core.io.ClassPathResource;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.*;
import org.springframework.jdbc.datasource.init.ScriptUtils;
import org.springframework.transaction.support.TransactionTemplate;

import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicInteger;

/** H2 strategy fixture and explicitly simulated provider. Not native MySQL or real model evidence. */
final class SceneTestSupport implements AutoCloseable {
    final DriverManagerDataSource source;
    final JdbcTemplate jdbc;
    final ObjectMapper mapper=new ObjectMapper().findAndRegisterModules();
    final DataSourceTransactionManager manager;
    final SceneStore scenes;
    final SceneExecutionStore execution;
    final SceneDataService data;
    final SceneManagementService management;
    final SceneRuntimeService service;
    final AiRuntimeService runtime;
    final AiApplicationStore applications;
    final AiApplicationService versions;
    final AiRequestStore requests;
    final AiRequestLifecycle lifecycle;
    final JdbcAiApplicationReferences references;
    final Provider provider=new Provider();
    final AiRequestLogService logs;
    final StaticListableBeanFactory beans=new StaticListableBeanFactory();
    final TrustedActor actor=new TrustedActor("101","tenant_a",Set.of("ai:scene:invoke","ai:scene:query","ai:scene:edit","ai:request:list","ai:request:query","ai:request:export","ai:request:audit","lowcode:record:query","lowcode:record:list"),null);
    SceneTestSupport() throws Exception {
        source=new DriverManagerDataSource("jdbc:h2:mem:scene_runtime_"+UUID.randomUUID()+";MODE=MySQL;DATABASE_TO_LOWER=TRUE;DB_CLOSE_DELAY=-1;LOCK_TIMEOUT=5000","sa","");jdbc=new JdbcTemplate(source);manager=new DataSourceTransactionManager(source);
        script(read("V20261016_200__ai_app_runtime_v04.sql"));script(read("V20261016_220__ai_attempt_trace.sql"));
        script(read("V20261016_225__direct_scene_runtime.sql").split("-- Duplicate legacy")[0]);
        jdbc.execute("ALTER TABLE sys_ai_request ADD scene_key VARCHAR(128)");jdbc.execute("ALTER TABLE sys_ai_request ADD scene_version_id BIGINT");
        script(read("V20261012_160__lowcode_forms.sql").split("-- Prefer reserved IDs")[0]);
        jdbc.execute("CREATE TABLE sys_user(user_id BIGINT PRIMARY KEY,tenant_id VARCHAR(64),status INT,is_deleted INT)");jdbc.update("INSERT INTO sys_user VALUES(101,'tenant_a',1,0),(102,'tenant_b',1,0)");
        String schema="{\"fields\":[{\"fieldKey\":\"subject\",\"label\":\"主题\",\"type\":\"text\",\"maxLength\":2000},{\"fieldKey\":\"summary\",\"label\":\"摘要\",\"type\":\"textarea\",\"maxLength\":2000},{\"fieldKey\":\"internal_note\",\"label\":\"内部\",\"type\":\"text\",\"maxLength\":2000}]}";
        jdbc.update("INSERT INTO sys_lowcode_form(id,form_key,name,status,draft_schema,published_version,creator) VALUES(1,'fixture','Fixture form','enabled',?,1,'101')",schema);
        jdbc.update("INSERT INTO sys_lowcode_form_version(form_id,schema_version,schema_json,creator) VALUES(1,1,?,'101')",schema);
        jdbc.update("INSERT INTO sys_lowcode_record(id,form_id,schema_version,tenant_id,creator,data_json) VALUES(11,1,1,'tenant_a','101',?)","{\"subject\":\"visible subject\",\"summary\":null,\"internal_note\":\"must-never-enter-model\"}");
        var identity=new LowcodeIdentity(true);var schemas=new LowcodeSchema(mapper);var lowcode=new LowcodeService(jdbc,schemas,identity);
        data=new SceneDataService(jdbc,lowcode,identity,schemas,mapper,manager);
        data.database(new com.xinghuiTec.ai.scenedata.DatabaseBindingReader(jdbc,mapper,lowcode,schemas,new com.xinghuiTec.security.PlatformAccessService(false)));
        beans.addBean("provider",provider);beans.addBean("data",data);beans.addBean("references",new JdbcAiApplicationReferences(jdbc,manager));
        var legacy=new LegacySceneTemplates(jdbc,mapper,data);beans.addBean("legacy",legacy);
        references=new JdbcAiApplicationReferences(jdbc,manager);scenes=new SceneStore(jdbc,mapper);execution=new SceneExecutionStore(jdbc,mapper);
        var cipher=new SecretCipher(()->Base64.getEncoder().encodeToString(new byte[32]));
        management=new SceneManagementService(scenes,execution,references,beans.getBeanProvider(AiSceneDataRuntime.class),beans.getBeanProvider(AiProviderRuntime.class),cipher,mapper);management.legacyTemplates(beans.getBeanProvider(AiSceneLegacyTemplates.class));
        applications=new AiApplicationStore(jdbc,mapper);versions=new AiApplicationService(jdbc,mapper,applications,references,beans.getBeanProvider(AiProviderRuntime.class),beans.getBeanProvider(AiTemplateRuntime.class));
        var owner=new JdbcSceneOwnership(jdbc);applications.sceneOwnership(owner);versions.sceneOwnership(owner);
        requests=new AiRequestStore(jdbc);var journal=new AiRequestJournal(jdbc,requests,manager);var events=new Events();lifecycle=new AiRequestLifecycle(requests,events,manager);
        var worker=new AiRequestExecutor(requests,applications,journal,lifecycle,new AiSharedModelCursor(jdbc),events,beans.getBeanProvider(AiProviderRuntime.class),beans.getBeanProvider(AiTemplateRuntime.class),jdbc,manager);
        runtime=new AiRuntimeService(applications,versions,requests,journal,lifecycle,worker,beans.getBeanProvider(AiTemplateRuntime.class),jdbc,mapper,manager,cipher);
        runtime.sceneAccess(new SceneRequestAccessService(scenes,beans.getBeanProvider(AiSceneDataRuntime.class)));
        service=new SceneRuntimeService(scenes,management,execution,runtime,references,beans.getBeanProvider(AiSceneDataRuntime.class));logs=new AiRequestLogService(jdbc,applications,versions);
    }
    SceneDtos.Detail create(boolean record){return management.create(new SceneDtos.Create("Scene","",record?SceneDtos.Kind.RECORD_SUMMARY:SceneDtos.Kind.GENERAL,record?"1":null,UUID.randomUUID().toString()),actor);}
    SceneDtos.Definition definition(String prompt,boolean record){return new SceneDtos.Definition("Scene","",prompt,"",record?List.of(new SceneDtos.DataObject("lowcode.record","1",List.of("subject"))):List.of(),new SceneDtos.ModelPolicy(SceneDtos.ModelMode.FIXED,List.of(new SceneDtos.ModelBinding(new SceneDtos.ModelRef("17","8"),SceneDtos.KeyMode.AUTO,List.of(),null,List.of())),false,SceneDtos.OutputFormat.TEXT,10,2,2,100));}
    SceneDtos.SaveResult save(SceneDtos.Detail detail,String prompt,boolean record){return management.save(detail.scene().sceneKey(),new SceneDtos.Save(detail.scene().revision(),UUID.randomUUID().toString(),definition(prompt,record)),actor);}
    SceneDtos.Submission input(boolean record,String key){return new SceneDtos.Submission(record?new SceneDtos.BusinessRef("lowcode.record","1:11"):null,Map.of("user_request","brief"),key);}
    SceneDtos.Request terminal(String requestId)throws Exception{Instant limit=Instant.now().plusSeconds(5);SceneDtos.Request state;do{state=service.status(Long.parseLong(requestId),actor);if(Set.of("SUCCEEDED","FAILED","TIMED_OUT","CANCELLED","INCOMPLETE").contains(state.status()))return state;Thread.sleep(10);}while(Instant.now().isBefore(limit));throw new AssertionError("simulated worker did not finish");}
    void script(String sql)throws Exception{sql=sql.replace(" COLLATE utf8mb4_bin","").replace(" ENGINE=InnoDB DEFAULT CHARSET=utf8mb4","");try(var c=source.getConnection()){ScriptUtils.executeSqlScript(c,new ByteArrayResource(sql.getBytes(StandardCharsets.UTF_8)));}}
    String read(String name)throws Exception{try(var in=new ClassPathResource("db/migration/"+name).getInputStream()){return new String(in.readAllBytes(),StandardCharsets.UTF_8);}}
    @Override public void close(){provider.release.countDown();runtime.stop();data.close();}
    static final class Provider implements AiProviderRuntime {
        final AtomicInteger calls=new AtomicInteger();final List<ExecutionRequest> observed=new CopyOnWriteArrayList<>();
        volatile boolean unavailable;volatile CountDownLatch entered=new CountDownLatch(0),release=new CountDownLatch(0);
        volatile ExecutionStatus resultStatus=ExecutionStatus.SUCCEEDED;volatile ErrorCode resultError;volatile String resultText="controlled fixture result";
        public List<AvailableModel> availableModels(Requirements requirements){return List.of();}
        public Validation validateBindings(List<ModelBinding> bindings,Requirements requirements){return new Validation(unavailable?List.of(new Violation("models",ErrorCode.MODEL_UNAVAILABLE)):List.of());}
        public List<Target> resolveTargets(ModelBinding binding,Set<KeyRef> excluded,InvocationControl control){return unavailable?List.of():List.of(new Target(binding.model(),new KeyRef("19","3"),"SIMULATED","fixture-model","Key fixture",new Capabilities(Set.of(OutputFormat.TEXT),true,false)));}
        public ExecutionOutcome execute(ExecutionRequest request,StreamObserver observer,InvocationControl control){calls.incrementAndGet();observed.add(request);entered.countDown();try{while(!release.await(10,TimeUnit.MILLISECONDS))control.check();}catch(InterruptedException interrupted){Thread.currentThread().interrupt();throw new AiRuntimeException(ErrorCode.CANCELLED);}if(resultText!=null)observer.onDelta(resultText);return new ExecutionOutcome(resultStatus,resultError,false,resultText!=null,new Usage(5L,3L,8L),null,1);}
    }
    static final class Events implements AiRuntimeEvents {
        final Map<String,List<Event>> rows=new ConcurrentHashMap<>();
        public Event append(Event event){var entries=rows.computeIfAbsent(event.requestId(),ignored->new CopyOnWriteArrayList<>());synchronized(entries){Event next=event.sequenced(entries.size()+1L);entries.add(next);return next;}}
        public List<Event> after(String id,long sequence){return rows.getOrDefault(id,List.of()).stream().filter(e->e.sequence()>sequence).toList();}
    }
}
