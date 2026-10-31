package com.xinghuiTec.ai.agent;

import com.xinghuiTec.agent.api.*;
import com.xinghuiTec.agent.persistence.JdbcAgentRepository;
import com.xinghuiTec.ai.application.*;
import com.xinghuiTec.ai.contract.*;
import com.xinghuiTec.ai.contract.AiProviderRuntime.*;
import com.xinghuiTec.ai.contract.AiTypes.*;
import com.xinghuiTec.ai.modelhub.ModelHubService;
import com.xinghuiTec.ai.scene.SceneFailure;
import com.xinghuiTec.ai.scene.SceneManagementService;
import org.springframework.stereotype.Component;
import reactor.core.publisher.Flux;
import reactor.core.publisher.FluxSink;
import reactor.core.scheduler.Schedulers;
import java.time.*;
import java.util.*;
import java.util.concurrent.atomic.*;

/** Reuses configured providers and versioned prompt routes. No credentials or HTTP transport in the SDK module. */
@Component
public class AgentConfiguredModels implements AgentPromptGateway {
    private final AgentAccess access;
    private final SceneManagementService scenes;
    private final ModelHubService models;
    private final AiSharedModelCursor cursor;
    private final AiProviderRuntime provider;
    private final AiCallLogStore logs;
    private final JdbcAgentRepository repository;
    @org.springframework.beans.factory.annotation.Autowired private AgentImages images;
    public AgentConfiguredModels(AgentAccess access,SceneManagementService scenes,ModelHubService models,
                                 AiSharedModelCursor cursor,AiProviderRuntime provider,AiCallLogStore logs,JdbcAgentRepository repository){
        this.access=access;this.scenes=scenes;this.models=models;this.cursor=cursor;this.provider=provider;this.logs=logs;this.repository=repository;
    }
    @Override public Preset resolve(AgentActor actor,String sceneKey){
        return resolve(actor,sceneKey,"");
    }
    @Override public Preset resolve(AgentActor actor,String sceneKey,String task){
        try{return access.as(actor,context->{
            var route=scenes.agentRoute(sceneKey,context.actor());
            var frozen=route.policy().hasBusinessModels()?models.freeze(route.policy()):null;
            var policy=frozen==null?route.policy():frozen.policy();
            var first=new AtomicInteger(-1);
            return new Preset(route.systemPrompt(),(call,wire)->Flux.<String>create(sink->{
                var cancelled=new AtomicBoolean();sink.onCancel(()->cancelled.set(true));
                try{execute(actor,route,policy,frozen,first,call,wire,sink,cancelled);sink.complete();}
                catch(RuntimeException failure){if(!sink.isCancelled())sink.error(classify(failure));}
            },FluxSink.OverflowStrategy.ERROR).subscribeOn(Schedulers.boundedElastic()),List.of());
        });}catch(RuntimeException failure){throw classify(failure);}
    }
    private TrustedActor authorize(AgentActor owner,SceneManagementService.AgentRoute pinned,ModelHubService.Frozen frozen,AgentCall call){
        if(!owner.equals(call.actor()))throw AgentAccess.denied();
        call.check();
        return access.as(owner,context->{
            var run=repository.run(owner,call.runId());
            if(!run.sessionId().equals(call.sessionId())||!run.promptSceneKey().equals(pinned.sceneKey()))throw AgentAccess.denied();
            var current=scenes.agentRoute(pinned.sceneKey(),context.actor());
            if(current.revision()!=pinned.revision()||!current.appVersion().equals(pinned.appVersion()))throw problem("VERSION_CONFLICT");
            if(frozen!=null&&!models.unchanged(frozen))throw problem("VERSION_CONFLICT");
            return context.actor();
        });
    }
    private void execute(AgentActor owner,SceneManagementService.AgentRoute route,AiApplicationPolicy policy,ModelHubService.Frozen frozen,
                         AtomicInteger first,AgentCall call,String wire,FluxSink<String> sink,AtomicBoolean cancelled){
        if(wire==null||wire.length()>262144)throw problem("MODEL_INPUT_LIMIT");
        var request=images==null?new AgentImages.Wire(wire,false):images.hydrate(call,wire);
        Instant deadline=Instant.now().plusSeconds(policy.timeoutSeconds());if(deadline.isAfter(call.deadline()))deadline=call.deadline();
        final Instant end=deadline;
        InvocationControl control=new InvocationControl(){public Instant deadline(){return end;}public boolean cancelled(){return cancelled.get()||sink.isCancelled()||Thread.currentThread().isInterrupted()||call.cancelled().getAsBoolean();}};
        TrustedActor actor=authorize(owner,route,frozen,call);control.check();
        if(first.get()<0){
            int selected=0;
            if(policy.modelMode()==AiApplicationPolicy.ModelMode.ROUND_ROBIN){
                var eligible=policy.models().stream().map(binding->!targets(binding,Set.of(),policy.requirements(),control,request.hasImages()).isEmpty()).toList();
                selected=cursor.next(Long.parseLong(route.appVersion()),eligible).orElseThrow(()->problem("MODEL_UNAVAILABLE"));
            }
            first.compareAndSet(-1,selected);
        }
        var order=policy.attemptModelOrder(first.get());int attempts=0,previous=-1;ErrorCode last=request.hasImages()?ErrorCode.CAPABILITY_UNSUPPORTED:ErrorCode.MODEL_UNAVAILABLE;
        for(int position=0;position<order.size();position++){
            int index=order.get(position);var attempted=new HashSet<KeyRef>();
            while(attempts<policy.maxAttempts()){
                control.check();actor=authorize(owner,route,frozen,call);
                var targets=targets(policy.models().get(index),attempted,policy.requirements(),control,request.hasImages());if(targets.isEmpty())break;
                var target=targets.get(0);attempted.add(target.key());
                int windows=targets.size();
                for(int next=position+1;next<order.size()&&windows<policy.maxAttempts()-attempts;next++)
                    windows+=targets(policy.models().get(order.get(next)),Set.of(),policy.requirements(),control,request.hasImages()).size();
                windows=Math.max(1,Math.min(windows,policy.maxAttempts()-attempts));
                long millis=Math.max(1,Duration.between(Instant.now(),end).toMillis()/windows);
                Instant attemptEnd=Instant.now().plusMillis(millis);if(attemptEnd.isAfter(end))attemptEnd=end;final Instant bounded=attemptEnd;
                InvocationControl attemptControl=new InvocationControl(){
                    public Instant deadline(){return end;}
                    public Instant firstOutputDeadline(){return bounded;}
                    public boolean cancelled(){return control.cancelled();}
                };
                String reason=attempts==0?"FIRST":previous==index?"NEXT_KEY":"FALLBACK_MODEL";previous=index;attempts++;
                String log=logs.beginAgentCall(actor,route.sceneKey(),route.appId(),call.sessionId(),call.runId(),call.callId(),target,attempts,reason);
                var emitted=new AtomicBoolean();long started=System.nanoTime();ExecutionOutcome outcome;
                try{
                    var invocation=new Invocation(actor,route.appId(),route.appVersion(),new BusinessRef("agent.session",call.sessionId()),call.runId());
                    // Each turn retains the configured completion limit. An attempt with any output is never replayed.
                    outcome=provider.executeTools(new ToolExecutionRequest(invocation,UUID.randomUUID().toString(),target,request.json(),policy.maxTokens()),frame->{
                        attemptControl.check();emitted.set(true);sink.next(frame);
                    },attemptControl);
                    if(outcome==null)throw new AiRuntimeException(ErrorCode.INVALID_RESPONSE);
                }catch(AiRuntimeException classified){
                    boolean timeout=classified.code()==ErrorCode.DEADLINE_EXCEEDED;
                    outcome=new ExecutionOutcome(classified.code()==ErrorCode.CANCELLED?ExecutionStatus.CANCELLED:timeout?ExecutionStatus.TIMED_OUT:ExecutionStatus.FAILED,
                            classified.code(),!emitted.get()&&(retryable(classified.code())||timeout&&Instant.now().isBefore(end)&&!control.cancelled()),emitted.get(),null,null,Math.max(0,(System.nanoTime()-started)/1_000_000));
                }catch(RuntimeException unexpected){
                    boolean stopped=control.cancelled();
                    outcome=new ExecutionOutcome(stopped?ExecutionStatus.CANCELLED:ExecutionStatus.FAILED,stopped?ErrorCode.CANCELLED:ErrorCode.INTERNAL_FAILURE,false,emitted.get(),null,null,Math.max(0,(System.nanoTime()-started)/1_000_000));
                }
                logs.completeAgentCall(log,outcome);
                if(sink.isCancelled())return;
                control.check();
                if(outcome.status()==ExecutionStatus.SUCCEEDED){if(!emitted.get())throw problem("INVALID_RESPONSE");return;}
                last=outcome.errorCode();
                if(emitted.get()||outcome.outputStarted()||!outcome.retryable()||last==ErrorCode.CANCELLED)throw problem(last.name());
            }
        }
        throw problem(last.name());
    }
    private List<Target> targets(ModelBinding binding,Set<KeyRef> attempted,Requirements required,InvocationControl control,boolean images){
        try{return provider.resolveTargets(binding,attempted,control).stream().filter(t->t.capabilities().supports(required)&&(!images||provider.supportsImages(t,control))).toList();}
        catch(AiRuntimeException missing){if(Set.of(ErrorCode.MODEL_UNAVAILABLE,ErrorCode.NO_USABLE_KEY,ErrorCode.CAPABILITY_UNSUPPORTED).contains(missing.code()))return List.of();throw missing;}
    }
    private static boolean retryable(ErrorCode code){return Set.of(ErrorCode.NETWORK_ERROR,ErrorCode.RATE_LIMITED,ErrorCode.PROVIDER_UNAVAILABLE,
            ErrorCode.AUTH_FAILED,ErrorCode.CREDENTIAL_UNAVAILABLE,ErrorCode.MODEL_UNAVAILABLE,ErrorCode.NO_USABLE_KEY,ErrorCode.CAPABILITY_UNSUPPORTED).contains(code);}
    private static AgentProblem classify(RuntimeException failure){
        if(failure instanceof AgentProblem safe)return safe;
        if(failure instanceof AiRuntimeException safe)return problem(safe.code().name());
        if(failure instanceof SceneFailure safe)return problem(safe.status()==403?"FORBIDDEN":safe.status()==409?"VERSION_CONFLICT":"MODEL_UNAVAILABLE");
        return problem("MODEL_UNAVAILABLE");
    }
    private static AgentProblem problem(String code){return new AgentProblem(code,"模型调用未完成，请检查任务状态后重试。");}
}
