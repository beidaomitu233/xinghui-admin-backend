package com.xinghuiTec.agent.runtime;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.xinghuiTec.agent.api.*;
import io.agentscope.core.message.ToolResultBlock;
import io.agentscope.core.tool.*;
import reactor.core.publisher.Mono;
import reactor.core.scheduler.Schedulers;
import java.util.*;

/** Native deferred tool groups; no schema flood and no keyword/intent router. */
final class SkillBusinessTools {
    static Toolkit create(AgentCall call, AgentResourceGateway gateway, ObjectMapper json) {
        var toolkit=new Toolkit();
        toolkit.registerAgentTool(new AgentTool(){
            public String getName(){return "resources_list";}
            public String getDescription(){return "列出当前账号可访问的业务资源名称。按任务加载对应Skill，再查询资源的实际字段定义。";}
            public Map<String,Object> getParameters(){return Map.of("type","object","properties",Map.of(),"additionalProperties",false);}
            public boolean isReadOnly(){return true;}
            public Mono<ToolResultBlock> callAsync(ToolCallParam ignored){return Mono.fromCallable(()->ToolResultBlock.text(gateway.resources(call).toString())).subscribeOn(Schedulers.boundedElastic());}
        });
        var catalog=gateway.resources(call);
        var namespaces=new LinkedHashSet<String>();
        catalog.forEach(item->namespaces.add(item.path("resource").asText().split(":",2)[0]));
        for(String namespace:namespaces) {
            if(!namespace.matches("[a-z][a-z0-9-]{0,49}"))throw new IllegalStateException("Invalid tool namespace");
            toolkit.createSkillToolGroup(namespace,"业务能力："+namespace,false,namespace);
            boolean readOnly=java.util.stream.StreamSupport.stream(catalog.spliterator(),false)
                    .filter(item->item.path("resource").asText().split(":",2)[0].equals(namespace)).allMatch(item->item.path("readOnly").asBoolean(false));
            for(String operation:readOnly?List.of("describe","query"):List.of("describe","query","preview")) {
                String name=namespace.replace('-','_')+"_"+operation;
                toolkit.registration().agentTool(new BusinessTool(name,namespace,operation,call,gateway,json)).group(namespace).apply();
            }
        }
        return toolkit;
    }
    private record BusinessTool(String name,String namespace,String operation,AgentCall call,
            AgentResourceGateway gateway,ObjectMapper json) implements AgentTool {
        public String getName(){return name;}
        public String getDescription(){return switch(operation){
            case "describe"->"获取资源字段、操作和参数定义。resource须为此Skill下的可访问资源。";
            case "query"->"按资源定义查询业务数据，分页并选择所需字段。";
            default->"提交具体业务变更预览并等待用户确认，返回EXECUTED及业务结果后才算执行成功。用户拒绝返回REJECTED；失败返回FAILED。确认成功后继续任务。";
        };}
        public Map<String,Object> getParameters(){
            Map<String,Object> arguments=operation.equals("preview")
                    ?Map.of("type","object","required",List.of("operation"),"properties",Map.of("operation",Map.of("type","string","description","从当前资源describe返回的operations中选择写入操作；其他字段按mutation参数定义填写。")))
                    :Map.of("type","object");
            return Map.of("type","object","additionalProperties",false,
                "properties",Map.of("resource",Map.of("type","string"),"arguments",arguments),
                "required",List.of("resource","arguments"));
        }
        public boolean isReadOnly(){return !operation.equals("preview");}
        public Mono<ToolResultBlock> callAsync(ToolCallParam param){return Mono.fromCallable(()->{
            call.check();var args=param.getInput();String resource=String.valueOf(args.getOrDefault("resource",""));
            if(!resource.equals(namespace)&&!resource.startsWith(namespace+":"))throw new AgentProblem("FORBIDDEN","资源不属于当前Skill");
            if(args.keySet().stream().anyMatch(k->!Set.of("resource","arguments").contains(k)))throw new AgentProblem("INVALID_ARGUMENTS","参数包含未定义字段");
            JsonNode input=json.valueToTree(args.getOrDefault("arguments",Map.of()));
            if(!input.isObject())throw new AgentProblem("INVALID_ARGUMENTS","参数须为对象");
            var toolCall=new AgentCall(call.actor(),call.sessionId(),call.runId(),param.getToolUseBlock().getId(),call.deadline(),call.cancelled());
            JsonNode result=switch(operation){
                case "describe"->gateway.describe(toolCall,resource);
                case "query"->gateway.query(toolCall,resource,input);
                default->gateway.mutate(toolCall,param.getToolUseBlock().getId(),resource,input);
            };return ToolResultBlock.text(result.toString());
        }).onErrorResume(AgentProblem.class,p->Mono.just(ToolResultBlock.error(json.createObjectNode().put("code",p.code()).put("message",p.getMessage()).toString())))
          .onErrorResume(Exception.class,p->Mono.just(ToolResultBlock.error("业务操作未完成，请查询当前状态。")))
          .subscribeOn(Schedulers.boundedElastic());}
    }
}
