package com.xinghuiTec.ai.config;

import com.xinghuiTec.ai.contract.AiProviderRuntime.*;
import com.xinghuiTec.ai.contract.AiRuntimeException;
import jakarta.annotation.PreDestroy;
import org.springframework.boot.context.event.ApplicationReadyEvent;
import org.springframework.context.event.EventListener;
import org.springframework.stereotype.Service;
import org.springframework.transaction.support.TransactionTemplate;

import java.time.Instant;
import java.util.List;
import java.util.concurrent.*;

import static com.xinghuiTec.ai.config.AiConfigurationDtos.*;
import static com.xinghuiTec.ai.contract.AiTypes.*;

@Service
public class AiConfigurationTests {
    private final AiConfigurationService configs;
    private final OpenRouterTransport transport;
    private final TransactionTemplate transactions;
    private com.xinghuiTec.ai.application.AiCallLogStore callLogs;
    @org.springframework.beans.factory.annotation.Autowired(required=false)
    public void callLogs(com.xinghuiTec.ai.application.AiCallLogStore store){this.callLogs=store;}
    private org.springframework.beans.factory.ObjectProvider<AiConfigurationTestObserver> completionObservers;
    @org.springframework.beans.factory.annotation.Autowired
    public void completionObservers(org.springframework.beans.factory.ObjectProvider<AiConfigurationTestObserver> observers){this.completionObservers=observers;}
    private final ScheduledExecutorService leases=Executors.newSingleThreadScheduledExecutor(r->{
        var thread=new Thread(r,"ai-test-lease");thread.setDaemon(true);return thread;
    });
    private final ConcurrentMap<Long,ScheduledFuture<?>> heartbeats=new ConcurrentHashMap<>();
    private final ExecutorService workers=new ThreadPoolExecutor(2,2,0,TimeUnit.SECONDS,new ArrayBlockingQueue<>(16),r->{
        var thread=new Thread(r,"ai-configuration-test");thread.setDaemon(true);return thread;
    });
    public AiConfigurationTests(AiConfigurationService configs,OpenRouterTransport transport,TransactionTemplate transactions) {
        this.configs=configs;this.transport=transport;this.transactions=transactions;
    }
    @EventListener(ApplicationReadyEvent.class)
    public void recover() {
        recoverExpired();
        leases.scheduleWithFixedDelay(()->{ try { recoverExpired(); } catch(RuntimeException unavailable) { /* Retry reconciliation; never replay a provider call. */ } },10,10,TimeUnit.SECONDS);
    }
    private void recoverExpired() {
        // Every owner renews its own batch, including queued work. Other live instances are not interrupted.
        var databaseNow=configs.jdbc.queryForObject("SELECT CURRENT_TIMESTAMP",java.sql.Timestamp.class);
        configs.jdbc.update("UPDATE sys_ai_configuration_test SET status='INTERRUPTED',update_time=CURRENT_TIMESTAMP WHERE status IN ('QUEUED','RUNNING') AND update_time < ?",java.sql.Timestamp.from(java.util.Objects.requireNonNull(databaseNow).toInstant().minusSeconds(90)));
        configs.jdbc.update("UPDATE sys_ai_configuration SET running_test_id=NULL WHERE running_test_id IN (SELECT test_id FROM sys_ai_configuration_test WHERE status='INTERRUPTED')");
    }
    @PreDestroy public void close() { workers.shutdownNow(); leases.shutdownNow(); }
    public TestRun start(long configId,TestStart request) { return start(configId,request,false,null); }
    public TestRun start(long configId,TestStart request,TrustedActor actor) { return start(configId,request,false,actor); }
    TestRun startSettings(long configId,TestStart request) { return start(configId,request,true,null); }
    TestRun startSettings(long configId,TestStart request,TrustedActor actor) { return start(configId,request,true,actor); }
    private TestRun start(long configId,TestStart request,boolean settings,TrustedActor actor) {
        if(callLogs!=null&&(actor==null||actor.servicePrincipalRef()!=null))throw AiConfigurationService.fail(403,"可信发起人缺失");
        if(!request.confirmedCosts()) throw AiConfigurationService.fail(400,"请先确认测试搭配数及可能费用");
        long run=transactions.execute(tx->{
            var h=configs.head(configId,true); AiConfigurationService.checkRevision(h,request.revision());
            if(h.runningTest()!=null||!request.configVersion().equals(AiConfigurationService.str(h.draft()))) throw AiConfigurationService.fail(409,"已有测试运行或草稿已变化");
            var s=configs.snapshot(configId,h.draft());
            if(s.models().isEmpty()||s.keys().isEmpty()) throw AiConfigurationService.fail(409,"请先配置模型和Key");
            long test=configs.insert("INSERT INTO sys_ai_configuration_test(config_id,version_id,status) VALUES(?,?,'QUEUED')",configId,h.draft());
            if(actor!=null&&callLogs!=null)configs.jdbc.update("UPDATE sys_ai_configuration_test SET initiator_subject_ref=?,initiator_tenant_ref=? WHERE test_id=?",actor.subjectId(),actor.tenantId(),test);
            var old=h.active()==null?null:configs.snapshot(configId,h.active());
            boolean rotation=settings && old!=null && configs.pureKeyRotation(old,s);
            for(var m:s.models()) for(var k:s.keys()) {
                if(!AiConfigurationService.associated(m,k)) continue;
                if(settings && old!=null && !old.configVersion().equals(s.configVersion()) && configs.compatibleSettings(old,s) && old.models().contains(m)
                        && old.keys().stream().anyMatch(prior->prior.keyRef().equals(k.keyRef()) && prior.keyVersion().equals(k.keyVersion()) && AiConfigurationService.associated(m,prior))
                        && (rotation || configs.validProof(s,m,k) && configs.keyUsable(k))) continue;
                configs.insert("INSERT INTO sys_ai_configuration_test_pair(test_id,model_ref,key_ref,key_version,proof_hash) VALUES(?,?,?,?,?)",test,AiConfigurationService.id(m.modelRef()),AiConfigurationService.id(k.keyRef()),AiConfigurationService.id(k.keyVersion()),configs.proof(s,m,k));
            }
            configs.jdbc.update("UPDATE sys_ai_configuration SET latest_test_id=?,running_test_id=? WHERE config_id=?",test,test,configId);
            return test;
        });
        try {
            heartbeats.put(run,leases.scheduleWithFixedDelay(()->{
                try { configs.jdbc.update("UPDATE sys_ai_configuration_test SET update_time=CURRENT_TIMESTAMP WHERE test_id=? AND status IN ('QUEUED','RUNNING')",run); }
                catch(RuntimeException unavailable) { /* Expiry stops future attempts; it never restarts work. */ }
            },5,5,TimeUnit.SECONDS));
            workers.submit(()->run(configId,run));
        }
        catch(RejectedExecutionException busy) {
            stopHeartbeat(run);
            configs.jdbc.update("UPDATE sys_ai_configuration_test SET status='INTERRUPTED' WHERE test_id=?",run);
            configs.jdbc.update("UPDATE sys_ai_configuration SET running_test_id=NULL WHERE config_id=? AND running_test_id=?",configId,run);
            throw AiConfigurationService.fail(503,"测试队列已满，请稍后重试");
        }
        return get(configId,run);
    }
    private void stopHeartbeat(long run) { var heartbeat=heartbeats.remove(run); if(heartbeat!=null) heartbeat.cancel(false); }
    boolean cancelled(long run) {
        return Thread.currentThread().isInterrupted()||Boolean.TRUE.equals(configs.jdbc.queryForObject("SELECT CASE WHEN cancel_requested=1 OR status NOT IN ('QUEUED','RUNNING') THEN 1 ELSE 0 END FROM sys_ai_configuration_test WHERE test_id=?",Boolean.class,run));
    }
    void run(long configId,long run) {
        String finalState="COMPLETED";
        try {
            if(cancelled(run)) { finalState="CANCELLED"; return; }
            if(configs.jdbc.update("UPDATE sys_ai_configuration_test SET status='RUNNING',update_time=CURRENT_TIMESTAMP WHERE test_id=? AND status='QUEUED'",run)!=1) return;
            long version=configs.jdbc.queryForObject("SELECT version_id FROM sys_ai_configuration_test WHERE test_id=?",Long.class,run);
            var s=configs.snapshot(configId,version);
            for(var m:s.models()) for(var k:s.keys()) {
                if(configs.jdbc.queryForObject("SELECT COUNT(*) FROM sys_ai_configuration_test_pair WHERE test_id=? AND model_ref=? AND key_ref=?",Integer.class,run,AiConfigurationService.id(m.modelRef()),AiConfigurationService.id(k.keyRef()))==0) continue;
                if(cancelled(run)) { finalState="CANCELLED"; return; }
                configs.jdbc.update("UPDATE sys_ai_configuration_test_pair SET status='RUNNING' WHERE test_id=? AND model_ref=? AND key_ref=?",run,AiConfigurationService.id(m.modelRef()),AiConfigurationService.id(k.keyRef()));
                String callId=null;
                if(callLogs!=null){Long pair=configs.jdbc.queryForObject("SELECT pair_id FROM sys_ai_configuration_test_pair WHERE test_id=? AND model_ref=? AND key_ref=?",Long.class,run,AiConfigurationService.id(m.modelRef()),AiConfigurationService.id(k.keyRef()));
                    callId=callLogs.beginConfigurationPair(run,pair,m.modelRef(),m.modelCode(),s.providerCode(),k.label());}
                ExecutionOutcome result;
                String actualProof=configs.proof(s,m,k,"UNAVAILABLE");
                try {
                    String secret=configs.resolveSecret(k); Instant deadline=Instant.now().plusSeconds(s.timeoutSeconds());
                    actualProof=configs.proof(s,m,k,secret);
                    boolean json=OpenRouterCapabilities.jsonEnabled(m);
                    result=call(s,m.modelCode(),secret,List.of(new AiMessage(MessageRole.USER,json?"Return exactly one JSON object with the field ok set to true.":"Reply with exactly OK.")),new Requirements(json?OutputFormat.JSON_OBJECT:OutputFormat.TEXT,false,false),256,new StreamObserver() {
                        public void onDelta(String text) {} public void onUsage(Usage usage) {}
                    },new InvocationControl() {
                        public Instant deadline() { return deadline; } public boolean cancelled() { return AiConfigurationTests.this.cancelled(run); }
                    });
                } catch(RuntimeException failure) {
                    ErrorCode error=failure instanceof AiRuntimeException classified?classified.code():ErrorCode.CREDENTIAL_UNAVAILABLE;
                    result=new ExecutionOutcome(ExecutionStatus.FAILED,error,false,false,null,null,0);
                }
                if(callLogs!=null)callLogs.completeConfigurationPair(callId,result);
                configs.jdbc.update("UPDATE sys_ai_configuration_test_pair SET status=?,error_code=?,elapsed_ms=?,proof_hash=?,checked_at=CURRENT_TIMESTAMP WHERE test_id=? AND model_ref=? AND key_ref=?",
                        result.status()==ExecutionStatus.SUCCEEDED?"SUCCESS":"FAILED",result.errorCode()==null?null:result.errorCode().name(),result.elapsedMs(),actualProof,run,AiConfigurationService.id(m.modelRef()),AiConfigurationService.id(k.keyRef()));
                configs.jdbc.update("UPDATE sys_ai_configuration_test SET update_time=CURRENT_TIMESTAMP WHERE test_id=?",run);
            }
        } catch(RuntimeException unavailable) { finalState="INTERRUPTED"; }
        finally {
            // Restore health only after a complete non-cancelled probe; any 401 for this material wins.
            if("COMPLETED".equals(finalState)) {
                long checkedVersion=configs.jdbc.queryForObject("SELECT version_id FROM sys_ai_configuration_test WHERE test_id=?",Long.class,run);
                for(var key:configs.snapshot(configId,checkedVersion).keys()) {
                    var states=configs.jdbc.query("SELECT status,error_code FROM sys_ai_configuration_test_pair WHERE test_id=? AND key_ref=?",(rs,n)->new String[]{rs.getString(1),rs.getString(2)},run,AiConfigurationService.id(key.keyRef()));
                    if(!states.isEmpty() && states.stream().allMatch(p->"AUTH_FAILED".equals(p[1]))) configs.keyRetested(key,true);
                    else if(!states.isEmpty() && states.stream().allMatch(p->"SUCCESS".equals(p[0]))) configs.keyRetested(key,false);
                }
            }
            stopHeartbeat(run);
            // Publish terminal evidence and release its running marker atomically. Completion may also
            // be observed by startSettings/reconcile; it must never see COMPLETED with a stale marker.
            String completedState=finalState;
            transactions.executeWithoutResult(status->{
                configs.jdbc.update("UPDATE sys_ai_configuration_test SET status=?,update_time=CURRENT_TIMESTAMP WHERE test_id=? AND status IN ('QUEUED','RUNNING')",completedState,run);
                configs.jdbc.update("UPDATE sys_ai_configuration SET running_test_id=NULL WHERE config_id=? AND running_test_id=?",configId,run);
            });
            if(completionObservers!=null){var observer=completionObservers.getIfUnique();if(observer!=null)observer.completed(configId,run);}
        }
    }
    private ExecutionOutcome call(Snapshot snapshot,String model,String secret,List<AiMessage> messages,Requirements requirements,
                                  int tokens,StreamObserver observer,InvocationControl control) {
        // Preserve the original OpenRouter transport seam; both branches use the same bounded parser.
        if("openrouter".equals(snapshot.providerCode())) return transport.call(model,snapshot.timeoutSeconds(),secret,messages,requirements,tokens,observer,control);
        return transport.callForProvider(snapshot.providerCode(),snapshot.endpointUrl(),model,snapshot.timeoutSeconds(),secret,messages,requirements,tokens,observer,control);
    }
    public TestRun get(long configId,long run) {
        var rows=configs.jdbc.query("SELECT version_id,status FROM sys_ai_configuration_test WHERE config_id=? AND test_id=?",(rs,n)->new String[]{rs.getString(1),rs.getString(2)},configId,run);
        if(rows.isEmpty()) throw AiConfigurationService.fail(404,"测试批次不存在");
        var row=rows.get(0); var s=configs.snapshot(configId,AiConfigurationService.id(row[0]));
        var pairs=configs.jdbc.query("SELECT * FROM sys_ai_configuration_test_pair WHERE test_id=? ORDER BY pair_id",(rs,n)->new TestPair(rs.getString("model_ref"),
                s.models().stream().filter(m->m.modelRef().equals(rsString(rs,"model_ref"))).findFirst().orElseThrow().modelCode(),
                rs.getString("key_ref"),rs.getString("key_version"),s.keys().stream().filter(k->k.keyRef().equals(rsString(rs,"key_ref"))).findFirst().orElseThrow().label(),
                rs.getString("status"),rs.getString("error_code"),rs.getObject("elapsed_ms")==null?null:rs.getLong("elapsed_ms")),run);
        return new TestRun(Long.toString(run),Long.toString(configId),row[0],row[1],pairs.size(),(int)pairs.stream().filter(p->List.of("SUCCESS","FAILED").contains(p.status())).count(),pairs);
    }
    private static String rsString(java.sql.ResultSet rs,String name) { try { return rs.getString(name); } catch(java.sql.SQLException e) { throw new IllegalStateException("Test metadata unavailable"); } }
    public TestRun cancel(long configId,long run) {
        get(configId,run);
        configs.jdbc.update("UPDATE sys_ai_configuration_test SET cancel_requested=1 WHERE config_id=? AND test_id=? AND status IN ('QUEUED','RUNNING')",configId,run);
        configs.jdbc.update("UPDATE sys_ai_configuration_test SET status='CANCELLED' WHERE config_id=? AND test_id=? AND status='QUEUED'",configId,run);
        configs.jdbc.update("UPDATE sys_ai_configuration SET running_test_id=NULL WHERE config_id=? AND running_test_id=? AND EXISTS(SELECT 1 FROM sys_ai_configuration_test WHERE test_id=? AND status='CANCELLED')",configId,run,run);
        return get(configId,run);
    }
}
