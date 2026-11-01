package com.xinghuiTec.agent.runtime;

import com.fasterxml.jackson.databind.*;
import com.xinghuiTec.agent.api.*;
import io.agentscope.core.state.InMemoryAgentStateStore;
import io.agentscope.core.state.AgentStateStore;
import io.agentscope.harness.agent.DistributedStore;
import io.agentscope.harness.agent.filesystem.remote.store.InMemoryStore;
import org.junit.jupiter.api.*;
import org.junit.jupiter.api.io.TempDir;
import reactor.core.publisher.Flux;
import java.nio.file.Path;
import java.time.*;
import java.util.*;
import java.util.concurrent.atomic.AtomicInteger;
import static org.assertj.core.api.Assertions.*;

class HubEngineTest {
    @TempDir Path workspace;
    final ObjectMapper json=new ObjectMapper();final AtomicInteger mutations=new AtomicInteger();
    AgentCall call(){return new AgentCall(new AgentActor("tenant-a","42"),"session-a","run-a","run",Instant.now().plusSeconds(30),()->false);}
    // The production store is Redis/JDBC. A delegating interface fixture keeps this SDK contract test database-free.
    AgentStateStore state(){return org.mockito.Mockito.mock(AgentStateStore.class,org.mockito.AdditionalAnswers.delegatesTo(new InMemoryAgentStateStore()));}
    DistributedStore distributed(){return DistributedStore.builder().agentStateStore(state()).baseStore(new InMemoryStore()).build();}
    AgentResourceGateway resources(){return new AgentResourceGateway(){
        public JsonNode resources(AgentCall c){return read("[{\"resource\":\"forms\",\"name\":\"业务表单\"},{\"resource\":\"system-parameters\",\"name\":\"系统参数\"}]");}
        public JsonNode describe(AgentCall c,String r){return read("{\"name\":\"商品\",\"operations\":[\"query\",\"create\"]}");}
        public JsonNode query(AgentCall c,String r,JsonNode args){return read("{\"records\":[{\"name\":\"QA纸杯\"}]}");}
        public JsonNode mutate(AgentCall c,String id,String r,JsonNode args){mutations.incrementAndGet();return read("{\"status\":\"AWAITING_CONFIRMATION\"}");}
    };}
    @Test void schemasAreDeferredUntilNativeSkillLoadsAndHostToolsNeverAppear(){
        var turns=new AtomicInteger();AgentModelGateway model=(call,wire)->{
            var request=read(wire);var names=new HashSet<String>();request.path("tools").forEach(t->names.add(t.path("function").path("name").asText()));
            assertThat(names).contains("plan_exit").doesNotContain("execute","web_fetch","web_search","read_file","system_parameters_query");
            int turn=turns.getAndIncrement();
            if(turn==0){
                assertThat(names).contains("load_skill_through_path","resources_list").doesNotContain("forms_query");
                var matcher=java.util.regex.Pattern.compile("(?s)<name>forms</name>.*?<skill-id>([^<]+)</skill-id>").matcher(wire);
                assertThat(matcher.find()).as("Native skill metadata must be present").isTrue();
                return tool("load_skill_through_path",json.createObjectNode().put("skillId",matcher.group(1)).put("path","SKILL.md").toString());
            }
            if(turn==1)return tool("reset_equipped_tools","{\"to_activate\":[\"forms\"]}");
            assertThat(names).contains("forms_query","forms_describe","forms_preview");return text("已加载商品管理能力");
        };
        try(var runtime=new AgentScopeBinding(call(),"通过业务工具完成任务",workspace,state(),model,resources(),json,true,"EXECUTE",distributed())){
            runtime.run("看看有哪些商品").blockLast(Duration.ofSeconds(25));
        }
        assertThat(turns).hasValue(3);
    }
    @Test void readOnlyResourceGroupsDoNotRegisterMutationTools(){
        var gateway=org.mockito.Mockito.mock(AgentResourceGateway.class);
        org.mockito.Mockito.when(gateway.resources(org.mockito.ArgumentMatchers.any())).thenReturn(read("[{\"resource\":\"files\",\"name\":\"文件元数据\",\"readOnly\":true}]"));
        var toolkit=SkillBusinessTools.create(call(),gateway,json);
        assertThat(toolkit.getToolNames()).contains("files_describe","files_query").doesNotContain("files_preview");
    }
    @Test void planModeCannotPreviewWritesEvenIfModelCallsHiddenTool(){
        var turn=new AtomicInteger();AgentModelGateway model=(call,wire)->{
            if(turn.getAndIncrement()==0)return tool("forms_preview","{\"resource\":\"forms:1\",\"arguments\":{\"operation\":\"create\"}}");
            return text("计划已整理，等待用户切换到执行。");
        };
        try(var runtime=new AgentScopeBinding(call(),"先计划",workspace,state(),model,resources(),json,true,"PLAN",distributed())){
            runtime.run("整理商品").blockLast(Duration.ofSeconds(25));
        }
        assertThat(mutations).hasValue(0);
    }
    @Test void planCanDiscoverSkillsAndFinishWithoutApprovingItsOwnExecution(){
        var turn=new AtomicInteger();
        AgentModelGateway model=(c,wire)->{
            int step=turn.getAndIncrement();
            if(step==0)return tool("reset_equipped_tools","{\"to_activate\":[\"forms\"]}");
            if(step==1){
                assertThat(wire).contains("forms_describe");
                return tool("forms_query","{\"resource\":\"forms:1\",\"arguments\":{}}");
            }
            if(step==2){assertThat(wire).contains("QA纸杯");return tool("plan_write","{\"content\":\"查询商品后等待用户批准新增。\"}");}
            if(step==3){assertThat(wire).contains("Plan saved");return tool("plan_exit","{\"summary\":\"请批准计划\"}");}
            throw new AssertionError("Native ASK must stop without another model round");
        };
        var owner=call();
        try(var runtime=new AgentScopeBinding(owner,"先计划",workspace,state(),model,resources(),json,true,"PLAN",distributed())){
            runtime.run("新增一个杯子，先计划").blockLast(Duration.ofSeconds(25));
            var context=io.agentscope.core.agent.RuntimeContext.builder().userId(owner.actor().stateUserId()).sessionId(owner.sessionId()).build();
            assertThat(runtime.nativeAgent().getDelegate().getAgentState(context).getPlanModeContext().isPlanActive()).isTrue();
        }
        assertThat(turn).hasValue(4);
        assertThat(mutations).hasValue(0);
    }
    @Test void nativePlanPauseResumesOnlyAfterAuthenticatedExecutionAndSurvivesRecreation(){
        var persisted=state();var kv=distributed();var step=new AtomicInteger();
        AgentModelGateway model=(c,wire)->switch(step.getAndIncrement()){
            case 0->tool("plan_write","{\"content\":\"核对价格并提交改价预览。\"}");
            case 1->tool("plan_exit","{\"summary\":\"计划已就绪\"}");
            default->{assertThat(wire).contains("Plan approved");yield text("继续执行并等待具体业务确认。");}
        };
        try(var runtime=new AgentScopeBinding(call(),"先计划",workspace,persisted,model,resources(),json,true,"PLAN",kv)){
            var events=runtime.run("把杯子改成35元").collectList().block(Duration.ofSeconds(25));
            assertThat(events).anyMatch(event->event.type().equals("PLAN_READY"));
            assertThat(step).hasValue(2);assertThat(mutations).hasValue(0);
        }
        try(var runtime=new AgentScopeBinding(call(),"执行已确认计划",workspace,persisted,model,resources(),json,true,"EXECUTE",kv)){
            runtime.run("按计划继续执行").blockLast(Duration.ofSeconds(25));
        }
        assertThat(step).hasValue(3);assertThat(mutations).hasValue(0);
    }
    @Test void textSayingExecuteDoesNotApproveNativePlanWhileUserKeepsPlanMode(){
        var persisted=state();var kv=distributed();var step=new AtomicInteger();
        AgentModelGateway model=(c,wire)->switch(step.getAndIncrement()){
            case 0->tool("plan_write","{\"content\":\"先核对商品价格。\"}");
            case 1->tool("plan_exit","{\"summary\":\"请求执行\"}");
            default->{assertThat(wire).contains("Permission denied by user");yield text("仍保留计划，未提交业务变更。");}
        };
        try(var runtime=new AgentScopeBinding(call(),"先计划",workspace,persisted,model,resources(),json,true,"PLAN",kv)){
            runtime.run("改价前先做计划").blockLast(Duration.ofSeconds(25));
        }
        try(var runtime=new AgentScopeBinding(call(),"继续计划",workspace,persisted,model,resources(),json,true,"PLAN",kv)){
            runtime.run("执行").blockLast(Duration.ofSeconds(25));
            var context=io.agentscope.core.agent.RuntimeContext.builder().userId(call().actor().stateUserId()).sessionId(call().sessionId()).build();
            assertThat(runtime.nativeAgent().isPlanModeActive(context)).isTrue();
        }
        assertThat(mutations).hasValue(0);
    }
    @Test void nativeRemoteKvSurvivesAgentRecreationAndKeepsSessionWorkspacesSeparateWithoutShell(){
        var store=distributed();var owner=call();
        var rc=io.agentscope.core.agent.RuntimeContext.builder().userId(owner.actor().stateUserId()).sessionId(owner.sessionId()).build();
        try(var binding=new AgentScopeBinding(owner,"业务工具",workspace,state(),(c,w)->text("QA"),resources(),json,true,"EXECUTE",store)){
            var fs=binding.nativeAgent().getWorkspaceManager().getFilesystem();
            assertThat(fs).isInstanceOf(io.agentscope.harness.agent.filesystem.CompositeFilesystem.class)
                .isNotInstanceOf(io.agentscope.harness.agent.filesystem.sandbox.AbstractSandboxFilesystem.class);
            assertThat(binding.nativeAgent().getToolkit().getToolNames()).doesNotContain("execute","shell","read_file");
            assertThat(fs.write(rc,"/plans/qa-plan.md","QA_STORE_PLAN").isSuccess()).isTrue();
        }
        try(var binding=new AgentScopeBinding(owner,"业务工具",workspace,state(),(c,w)->text("QA"),resources(),json,true,"EXECUTE",store)){
            var fs=binding.nativeAgent().getWorkspaceManager().getFilesystem();
            assertThat(fs.read(rc,"/plans/qa-plan.md",0,100).fileData().content()).contains("QA_STORE_PLAN");
            var other=io.agentscope.core.agent.RuntimeContext.builder().userId(owner.actor().stateUserId()).sessionId("different-session").build();
            assertThat(fs.read(other,"/plans/qa-plan.md",0,100).isSuccess()).isFalse();
        }
    }
    @Test void userSelectedExecutionCanFinishNativePlanningWithoutGettingStuckInPlanMode(){
        var step=new AtomicInteger();
        AgentModelGateway model=(c,wire)->switch(step.getAndIncrement()){
            case 0->tool("plan_enter","{}");
            case 1->tool("plan_write","{\"content\":\"查询后新增商品，并由页面确认具体变更。\"}");
            case 2->tool("plan_exit","{\"summary\":\"执行已选择的任务\"}");
            default->{assertThat(wire).contains("Plan approved");yield text("将提交具体业务预览，等待页面确认。");}
        };
        var owner=call();
        try(var runtime=new AgentScopeBinding(owner,"先规划再执行",workspace,state(),model,resources(),json,true,"EXECUTE",distributed())){
            var events=runtime.run("新增杯子").collectList().block(Duration.ofSeconds(25));
            assertThat(events).anySatisfy(event->{
                assertThat(event.type()).isEqualTo("PLAN_DOCUMENT");
                assertThat(event.data().get("markdown")).isEqualTo("查询后新增商品，并由页面确认具体变更。");
            });
            var rc=io.agentscope.core.agent.RuntimeContext.builder().userId(owner.actor().stateUserId()).sessionId(owner.sessionId()).build();
            assertThat(runtime.nativeAgent().getDelegate().getAgentState(rc).getPlanModeContext().isPlanActive()).isFalse();
        }
        assertThat(mutations).hasValue(0);
    }
    JsonNode read(String wire){try{return json.readTree(wire);}catch(Exception error){throw new AssertionError(error);}}
    Flux<String> tool(String name,String args){var function=json.createObjectNode().put("name",name).put("arguments",args);var t=json.createObjectNode().put("index",0).put("id",UUID.randomUUID().toString()).put("type","function").set("function",function);return Flux.just(chunk(json.createObjectNode().set("tool_calls",json.createArrayNode().add(t)),null),chunk(json.createObjectNode(),"tool_calls"));}
    Flux<String> text(String text){return Flux.just(chunk(json.createObjectNode().put("content",text),null),chunk(json.createObjectNode(),"stop"));}
    String chunk(JsonNode delta,String finish){var choice=json.createObjectNode().put("index",0).put("finish_reason",finish);choice.set("delta",delta);return json.createObjectNode().put("id",UUID.randomUUID().toString()).put("object","chat.completion.chunk").set("choices",json.createArrayNode().add(choice)).toString();}
}
