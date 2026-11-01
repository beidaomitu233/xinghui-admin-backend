package com.xinghuiTec.ai.agent;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.xinghuiTec.agent.api.*;
import com.xinghuiTec.businessconfig.BusinessConfigService;
import org.springframework.stereotype.Component;
import java.util.*;

/** Configuration metadata stays in its business module; stored secret values never reach tools. */
@Component
public class BusinessConfigAgentResources implements AgentResourceProvider {
    private final BusinessConfigService configs;private final AgentAccess access;private final ObjectMapper json;
    public BusinessConfigAgentResources(BusinessConfigService configs,AgentAccess access,ObjectMapper json){this.configs=configs;this.access=access;this.json=json;}
    public String namespace(){return "business-config";}
    public JsonNode catalog(AgentCall call){
        var context=access.current(call.actor());var result=json.createArrayNode();
        if(context.platform()&&context.has("system:businessConfig:query"))for(var description:configs.descriptions())result.addObject().put("resource","business-config:"+description.section()).put("name",description.name()).put("description","业务接入配置；密钥仅可替换或清除，不能读取原值。");
        return result;
    }
    public void requireReadable(AgentActor actor,String resource){var context=access.current(actor);context.requirePlatform();context.require("system:businessConfig:query");section(resource);}
    public JsonNode describe(AgentCall call,String resource){
        requireReadable(call.actor(),resource);String section=section(resource);var description=configs.descriptions().stream().filter(d->d.section().equals(section)).findFirst().orElseThrow();
        var result=json.createObjectNode().put("resource",resource).put("name",description.name());result.set("parameters",json.valueToTree(description.parameters()));result.set("writeOnlySecrets",json.valueToTree(description.secrets()));
        var actions=result.putArray("operations").add("query");if(access.current(call.actor()).has("system:businessConfig:edit"))actions.add("update");
        result.set("mutation",json.valueToTree(Map.of("operation","update","revision","必填，来自查询结果","enabled","可选布尔值，省略保持原值","parameters","可选键值对象；仅替换提供的参数","secrets","可选，仅填写用户提供的新密钥；留空保持原值","clearSecrets","可选，要清除的密钥字段名数组")));return result;
    }
    public JsonNode query(AgentCall call,String resource,JsonNode arguments){requireReadable(call.actor(),resource);AgentArguments.only(arguments);return json.valueToTree(configs.get(section(resource)));}
    public JsonNode mutate(AgentCall call,String resource,JsonNode input){
        requireReadable(call.actor(),resource);access.current(call.actor()).require("system:businessConfig:edit");
        AgentArguments.only(input,"operation","revision","enabled","parameters","secrets","clearSecrets");if(!"update".equals(AgentArguments.text(input,"operation",20,true)))throw AgentArguments.invalid("此配置支持update操作。");
        String section=section(resource);var current=configs.get(section);var parameters=new LinkedHashMap<>(current.parameters());parameters.putAll(strings(input,"parameters"));
        boolean enabled=current.enabled();if(input.hasNonNull("enabled")){if(!input.get("enabled").isBoolean())throw AgentArguments.invalid("enabled必须是布尔值。");enabled=input.get("enabled").booleanValue();}
        var clear=new ArrayList<String>();if(input.hasNonNull("clearSecrets")){if(!input.get("clearSecrets").isArray()||input.get("clearSecrets").size()>16)throw AgentArguments.invalid("clearSecrets须为字段名数组。");for(var field:input.get("clearSecrets")){if(!field.isTextual())throw AgentArguments.invalid("clearSecrets须为字段名数组。");clear.add(field.asText());}}
        return json.valueToTree(configs.save(section,new BusinessConfigService.Change(enabled,parameters,strings(input,"secrets"),clear,AgentArguments.revision(input,"revision"))));
    }
    private String section(String resource){String section=resource.startsWith("business-config:")?resource.substring(16):"";if(configs.descriptions().stream().noneMatch(d->d.section().equals(section)))throw AgentArguments.invalid("业务配置分区不存在。");return section;}
    private static Map<String,String> strings(JsonNode input,String field){var result=new LinkedHashMap<String,String>();if(!input.hasNonNull(field))return result;var node=input.get(field);AgentArguments.object(node);node.fields().forEachRemaining(entry->{if(!entry.getValue().isTextual())throw AgentArguments.invalid(field+"仅接受字符串值。");result.put(entry.getKey(),entry.getValue().textValue());});return result;}
}
