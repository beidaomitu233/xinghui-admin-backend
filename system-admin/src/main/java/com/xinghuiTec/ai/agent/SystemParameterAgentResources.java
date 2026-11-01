package com.xinghuiTec.ai.agent;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.xinghuiTec.agent.api.*;
import com.xinghuiTec.domain.dto.ConfigChangeRequest;
import com.xinghuiTec.domain.vo.ConfigView;
import com.xinghuiTec.service.SysConfigService;
import org.springframework.stereotype.Component;
import java.util.*;

/** Reuses the native published parameter catalog; unknown/legacy secret keys stay inaccessible. */
@Component
public class SystemParameterAgentResources implements AgentResourceProvider {
    private final SysConfigService configs;private final AgentAccess access;private final ObjectMapper json;
    public SystemParameterAgentResources(SysConfigService configs,AgentAccess access,ObjectMapper json){this.configs=configs;this.access=access;this.json=json;}
    public String namespace(){return "system-parameters";}
    public JsonNode catalog(AgentCall call){var context=access.current(call.actor());var entries=json.createArrayNode();if(context.platform()&&(context.has("system:config:list")||context.has("system:config:query")))entries.addObject().put("resource",namespace()).put("name","系统参数").put("description","系统原生可管理参数与允许值，不包含历史未知配置或密钥。");return entries;}
    public void requireReadable(AgentActor actor,String resource){var context=access.current(actor);context.requirePlatform();if(!namespace().equals(resource)||!context.has("system:config:list")&&!context.has("system:config:query"))throw AgentAccess.denied();}
    public JsonNode describe(AgentCall call,String resource){
        requireReadable(call.actor(),resource);var context=access.current(call.actor());var result=json.createObjectNode().put("resource",resource).put("name","系统参数");
        result.set("definitions",json.valueToTree(configs.managedDefinitions()));var actions=result.putArray("operations");
        if(context.has("system:config:list"))actions.add("query");if(context.has("system:config:query"))actions.add("get");
        for(var entry:Map.of("create","add","update","edit","delete","remove").entrySet())if(context.has("system:config:"+entry.getValue()))actions.add(entry.getKey());
        result.set("query",json.valueToTree(Map.of("id","可选，configId正整数字符串，省略列出参数")));
        result.set("mutation",json.valueToTree(Map.of("operation","create/update/delete","id","update/delete必填，configId字符串","configKey","create/update必填，来自definitions","configValue","create/update必填，新值须满足definitions","expectedValue","update/delete必填，来自查询的当前configValue，防止覆盖并发修改")));return result;
    }
    public JsonNode query(AgentCall call,String resource,JsonNode arguments){
        requireReadable(call.actor(),resource);AgentArguments.only(arguments,"id");var context=access.current(call.actor());
        if(arguments.hasNonNull("id")){context.require("system:config:query");return view(configs.getManaged(id(arguments)));}
        context.require("system:config:list");var result=json.createArrayNode();configs.listManaged().forEach(value->result.add(view(value)));return result;
    }
    public JsonNode mutate(AgentCall call,String resource,JsonNode input){
        requireReadable(call.actor(),resource);String operation=AgentArguments.text(input,"operation",20,true);var context=access.current(call.actor());
        return switch(operation){
            case "create"->{context.require("system:config:add");AgentArguments.only(input,"operation","configKey","configValue");var id=configs.addManaged(change(input));yield json.createObjectNode().put("configId",id.toString()).put("created",true);}
            case "update"->{context.require("system:config:edit");AgentArguments.only(input,"operation","id","configKey","configValue","expectedValue");int id=id(input);configs.updateManagedExpected(id,AgentArguments.text(input,"expectedValue",500,true),change(input));yield json.createObjectNode().put("configId",Integer.toString(id)).put("updated",true);}
            case "delete"->{context.require("system:config:remove");AgentArguments.only(input,"operation","id","expectedValue");int id=id(input);configs.removeManagedExpected(id,AgentArguments.text(input,"expectedValue",500,true));yield json.createObjectNode().put("configId",Integer.toString(id)).put("deleted",true);}
            default->throw AgentArguments.invalid("操作未定义。");
        };
    }
    private static ConfigChangeRequest change(JsonNode input){return new ConfigChangeRequest(AgentArguments.text(input,"configKey",100,true),AgentArguments.text(input,"configValue",500,true));}
    private static int id(JsonNode input){long id=AgentArguments.id(input,"id");if(id>Integer.MAX_VALUE)throw AgentArguments.invalid("参数ID超出范围。");return (int)id;}
    private ObjectNode view(ConfigView value){var result=(ObjectNode)json.valueToTree(value);return result.put("configId",value.configId().toString());}
}
