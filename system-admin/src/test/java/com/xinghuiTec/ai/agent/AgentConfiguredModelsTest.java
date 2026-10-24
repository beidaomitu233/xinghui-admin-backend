package com.xinghuiTec.ai.agent;

import com.xinghuiTec.agent.api.*;
import com.xinghuiTec.agent.persistence.JdbcAgentRepository;
import com.xinghuiTec.ai.application.*;
import com.xinghuiTec.ai.contract.*;
import com.xinghuiTec.ai.contract.AiProviderRuntime.*;
import com.xinghuiTec.ai.contract.AiTypes.*;
import com.xinghuiTec.ai.modelhub.ModelHubService;
import com.xinghuiTec.ai.scene.SceneManagementService;
import org.junit.jupiter.api.*;
import java.time.*;
import java.util.*;
import java.util.concurrent.*;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

/** Application routing only, with synthetic provider responses; no network or paid calls. */
class AgentConfiguredModelsTest {
    AgentAdapterFixture fixture;SceneManagementService scenes;AiProviderRuntime provider;AiCallLogStore logs;
    JdbcAgentRepository repository;AgentConfiguredModels models;SceneManagementService.AgentRoute route;
    AgentCall call;Target free,paid;AiSharedModelCursor cursor;
    @BeforeEach void setup(){
        fixture=new AgentAdapterFixture();scenes=mock(SceneManagementService.class);provider=mock(AiProviderRuntime.class);
        logs=mock(AiCallLogStore.class);repository=mock(JdbcAgentRepository.class);cursor=mock(AiSharedModelCursor.class);
        var requirements=new Requirements(OutputFormat.TEXT,true,true);var caps=new Capabilities(Set.of(OutputFormat.TEXT),true,true);
        var key=new KeyRef("shared-key","1");free=new Target(new ModelVersionRef("1","11"),key,"fixture","free-model","QA",caps);
        paid=new Target(new ModelVersionRef("2","22"),key,"fixture","paid-fallback","QA",caps);
        var bindings=List.of(free,paid).stream().map(t->new ModelBinding(t.model(),KeyMode.AUTO,Set.of(),null,List.of())).toList();
        var policy=new AiApplicationPolicy(AiApplicationPolicy.ModelMode.FIXED,bindings,true,null,Set.of(),requirements,
                AiApplicationPolicy.Scope.FRONTEND,Set.of(),Set.of("ai:agent:invoke"),null,60,4,1,4096);
        route=new SceneManagementService.AgentRoute("scene.agent",2,"1","11","Synthetic system prompt",policy);
        when(scenes.agentRoute(eq(route.sceneKey()),any())).thenAnswer(i->route);
        call=new AgentCall(fixture.a,UUID.randomUUID().toString(),UUID.randomUUID().toString(),UUID.randomUUID().toString(),Instant.now().plusSeconds(60),()->false);
        when(repository.run(fixture.a,call.runId())).thenReturn(new JdbcAgentRepository.Run(call.runId(),call.sessionId(),"RUNNING",route.sceneKey(),"QA input",null,call.deadline()));
        when(provider.resolveTargets(any(),anySet(),any())).thenAnswer(i->{ModelBinding binding=i.getArgument(0);Set<KeyRef> used=i.getArgument(1);Target target=binding.model().equals(free.model())?free:paid;return used.contains(target.key())?List.of():List.of(target);});
        when(logs.beginAgentCall(any(),anyString(),anyString(),anyString(),anyString(),anyString(),any(),anyInt(),anyString())).thenReturn("fixture-log");
        models=new AgentConfiguredModels(fixture.access,scenes,mock(ModelHubService.class),cursor,provider,logs,repository);
    }
    @AfterEach void close(){fixture.close();}
    ExecutionOutcome outcome(ExecutionStatus status,ErrorCode code,boolean retry,boolean output){return new ExecutionOutcome(status,code,retry,output,new Usage(3L,2L,5L),"qa-response",5);}
    @Test void sameKeyCanServeSeveralModelsAndPaidFallbackIsLastWithFullTurnBudget(){
        when(provider.executeTools(any(),any(),any())).thenAnswer(i->{ToolExecutionRequest request=i.getArgument(0);ToolStreamObserver observer=i.getArgument(1);
            assertEquals(4096,request.maxTokens());assertEquals(call.runId(),request.invocation().requestId());
            if(request.target().equals(free))return outcome(ExecutionStatus.FAILED,ErrorCode.RATE_LIMITED,true,false);
            observer.onFrame("synthetic-final-frame");return outcome(ExecutionStatus.SUCCEEDED,null,false,true);
        });
        assertEquals(List.of("synthetic-final-frame"),models.resolve(fixture.a,route.sceneKey()).models().stream(call,"{}").collectList().block(Duration.ofSeconds(5)));
        var calls=org.mockito.ArgumentCaptor.forClass(ToolExecutionRequest.class);verify(provider,times(2)).executeTools(calls.capture(),any(),any());
        assertEquals(List.of(free,paid),calls.getAllValues().stream().map(ToolExecutionRequest::target).toList());
        verify(logs,times(2)).completeAgentCall(eq("fixture-log"),any());
        verify(logs).beginAgentCall(any(),eq(route.sceneKey()),eq("1"),eq(call.sessionId()),eq(call.runId()),eq(call.callId()),eq(paid),eq(2),eq("FALLBACK_MODEL"));
    }
    @Test void evenUnforwardedToolOrReasoningOutputPreventsFallbackAndEveryAttemptIsLogged(){
        when(provider.executeTools(any(),any(),any())).thenReturn(outcome(ExecutionStatus.INCOMPLETE,ErrorCode.INVALID_RESPONSE,false,true));
        assertEquals("INVALID_RESPONSE",assertThrows(AgentProblem.class,()->models.resolve(fixture.a,route.sceneKey()).models().stream(call,"{}").blockLast(Duration.ofSeconds(5))).code());
        verify(provider,times(1)).executeTools(any(),any(),any());verify(logs).completeAgentCall(eq("fixture-log"),any());
    }
    @Test void promptChangePermissionRevocationAndMismatchedSessionStopBeforeProvider(){
        var preset=models.resolve(fixture.a,route.sceneKey());
        route=new SceneManagementService.AgentRoute(route.sceneKey(),3,"1","12","Changed",route.policy());
        assertEquals("VERSION_CONFLICT",assertThrows(AgentProblem.class,()->preset.models().stream(call,"{}").blockLast(Duration.ofSeconds(5))).code());
        fixture.permissions.remove("ai:agent:invoke");
        assertEquals("FORBIDDEN",assertThrows(AgentProblem.class,()->preset.models().stream(call,"{}").blockLast(Duration.ofSeconds(5))).code());
        verify(provider,never()).executeTools(any(),any(),any());
    }
    @Test void cancellingSubscriptionInterruptsSilentTransportAndRecordsCancellation()throws Exception {
        var entered=new CountDownLatch(1);var stopped=new CountDownLatch(1);
        when(provider.executeTools(any(),any(),any())).thenAnswer(i->{InvocationControl control=i.getArgument(2);entered.countDown();
            long until=System.nanoTime()+TimeUnit.SECONDS.toNanos(3);while(!control.cancelled()&&System.nanoTime()<until)java.util.concurrent.locks.LockSupport.parkNanos(TimeUnit.MILLISECONDS.toNanos(5));
            assertTrue(control.cancelled());stopped.countDown();return outcome(ExecutionStatus.CANCELLED,ErrorCode.CANCELLED,false,false);
        });
        var subscription=models.resolve(fixture.a,route.sceneKey()).models().stream(call,"{}").subscribe();
        assertTrue(entered.await(2,TimeUnit.SECONDS));subscription.dispose();assertTrue(stopped.await(2,TimeUnit.SECONDS));
        verify(logs,timeout(1000)).completeAgentCall(eq("fixture-log"),argThat(o->o.status()==ExecutionStatus.CANCELLED));
    }
}
