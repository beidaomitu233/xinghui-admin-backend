package com.xinghuiTec.ai.hub;

import com.fasterxml.jackson.databind.*;
import com.xinghuiTec.agent.api.*;
import com.xinghuiTec.agent.runtime.AgentScopeBinding;
import io.agentscope.core.state.AgentStateStore;
import io.agentscope.harness.agent.DistributedStore;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;
import reactor.core.publisher.Flux;
import java.nio.file.Path;
import java.util.List;
import java.util.function.Supplier;

/** Direct official Harness invocation. Only business identity, tools, approval and public events are adapted. */
@Component
public final class NativeAgentScopeExecution implements AgentExecutionGateway {
    @org.springframework.beans.factory.annotation.Autowired(required=false)
    private com.xinghuiTec.ai.agent.AgentExtensionService extensions;
    private final DistributedStore store;private final AgentChanges changes;private final ObjectMapper json;private final Path workspace;
    public NativeAgentScopeExecution(DistributedStore store,AgentChanges changes,ObjectMapper json,
            @Value("${ai.agent.workspace-root:${java.io.tmpdir}/xinghui-agent-workspaces}")String workspace){
        this.store=store;this.changes=changes;this.json=json;this.workspace=Path.of(workspace);
    }
    public Flux<AgentEventView> execute(AgentCall call,AgentPromptGateway.Preset preset,String input,List<String> images,String mode,
                                       AgentStateStore state,AgentResourceGateway resources){
        String contract="""
            你通过当前用户的业务工具完成任务。自主选择相关Skill并按需启用工具组，先查询真实资源、字段和当前记录，不要猜测ID或业务结果。
            内置业务写入工具会提供具体变更预览并等待原用户在页面确认。只有返回EXECUTED后才算执行成功，随后继续完成任务并检查结果。
            用户拒绝的变更不要反复提交。计划模式只查询和规划，由用户选择执行模式继续，不能自行批准计划。
            MCP工具遵循接入配置和模式，写入工具只在直接处理且管理员启用时开放。
            执行模式中内置业务信息足够就调用preview生成页面确认，不先用聊天文字再问是否生成预览；页面确认才是业务变更授权，文字确认不能代替它。
            不输出私有推理或逐条重复的内部工具过程，只呈现有用的进度、业务结果和需要用户决定的事项。
            """;
        var tools=new AgentResourceGateway(){
            public JsonNode resources(AgentCall c){return audited(c,"list","",json.createObjectNode(),()->resources.resources(c));}
            public JsonNode describe(AgentCall c,String r){return audited(c,"describe",r,json.createObjectNode(),()->resources.describe(c,r));}
            public JsonNode query(AgentCall c,String r,JsonNode args){return audited(c,"query",r,args,()->resources.query(c,r,args));}
            public JsonNode mutate(AgentCall c,String id,String r,JsonNode args){
                if(!"EXECUTE".equals(mode))throw new AgentProblem("PLAN_READ_ONLY","计划模式不能提交变更");
                var preview=audited(c,"preview",r,args,()->changes.preview(c,id,r,args));
                return changes.awaitDecision(c,preview.path("changeId").asText());
            }
        };
        return Flux.using(()->{
            var installed=extensions==null?AgentExtensionSet.empty():extensions.open(call,mode);
            try{return new AgentScopeBinding(call,contract+"\n"+preset.systemPrompt(),workspace.resolve(call.sessionId()),state,preset.models(),tools,json,true,mode,store,installed);}
            catch(RuntimeException failure){installed.close();throw failure;}
        },
            binding->binding.run(input,List.of(),images),AgentScopeBinding::close);
    }
    private JsonNode audited(AgentCall call,String operation,String resource,JsonNode args,Supplier<JsonNode> action){
        long started=System.nanoTime();
        try{var result=action.get();changes.audit(call,operation,resource,args,operation.equals("preview")?"AWAITING_CONFIRMATION":"SUCCEEDED",result.path("changeId").asText(null),(System.nanoTime()-started)/1_000_000,result,null);return result;}
        catch(RuntimeException failure){changes.audit(call,operation,resource,args,"REJECTED",null,(System.nanoTime()-started)/1_000_000,null,failure instanceof AgentProblem problem?problem.code():"BUSINESS_ERROR");throw failure;}
    }
}
