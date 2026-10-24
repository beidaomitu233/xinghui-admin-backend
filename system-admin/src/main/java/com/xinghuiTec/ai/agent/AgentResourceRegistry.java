package com.xinghuiTec.ai.agent;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.xinghuiTec.agent.api.*;
import com.xinghuiTec.businessconfig.BusinessConfigService;
import com.xinghuiTec.exception.ServiceException;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.stereotype.Component;
import java.util.*;
import java.util.function.Supplier;

/** Registration is by resource namespace, never by chat keywords or page routes. */
@Component
public class AgentResourceRegistry implements AgentResourceGateway,AgentHistoryAccess {
    private final Map<String,AgentResourceProvider> providers;
    private final AgentAccess access;
    private final ObjectMapper json;
    public AgentResourceRegistry(List<AgentResourceProvider> providers,AgentAccess access,ObjectMapper json){
        var registered=new TreeMap<String,AgentResourceProvider>();
        for(var provider:providers)if(!provider.namespace().matches("[a-z][a-z0-9-]{0,49}")||registered.putIfAbsent(provider.namespace(),provider)!=null)throw new IllegalArgumentException("Duplicate or invalid business resource namespace");
        this.providers=Collections.unmodifiableMap(registered);this.access=access;this.json=json;
    }
    public JsonNode resources(AgentCall call){return execute(call,()->{
        var entries=json.createArrayNode();for(var provider:providers.values()){
            JsonNode catalog=provider.catalog(call);if(catalog==null||!catalog.isArray())throw new IllegalStateException("Invalid resource catalog");catalog.forEach(entries::add);
        }return entries;
    });}
    public JsonNode describe(AgentCall call,String resource){return execute(call,()->provider(resource).describe(call,resource));}
    public JsonNode query(AgentCall call,String resource,JsonNode arguments){return execute(call,()->{AgentArguments.object(arguments);return provider(resource).query(call,resource,arguments);});}
    public JsonNode mutate(AgentCall call,String toolCallId,String resource,JsonNode arguments){return execute(call,()->{AgentArguments.object(arguments);return provider(resource).mutate(call,resource,arguments);});}
    public void requireReadable(AgentActor actor,Set<String> resources){access.as(actor,context->{for(String resource:resources)provider(resource).requireReadable(actor,resource);return null;});}
    private AgentResourceProvider provider(String resource){
        if(resource==null||!resource.matches("[a-z][a-z0-9-]{0,49}(?::[A-Za-z0-9_.-]{1,70})?"))throw AgentArguments.invalid("资源标识无效，请先发现业务资源。");
        var provider=providers.get(resource.split(":",2)[0]);if(provider==null)throw new AgentProblem("RESOURCE_UNAVAILABLE","业务资源不存在或当前不可用。");return provider;
    }
    private JsonNode execute(AgentCall call,Supplier<JsonNode> work){
        call.check();return access.as(call.actor(),context->{try{
            JsonNode result=Objects.requireNonNull(work.get());call.check();
            if(result.toString().length()>65536)throw new AgentProblem("TOOL_OUTPUT_LIMIT","结果过大，请减少pageSize或通过fields选择所需字段。");return result;
        }catch(AccessDeniedException denied){throw AgentAccess.denied();}
         catch(ServiceException invalid){throw new AgentProblem("BUSINESS_VALIDATION",safe(invalid.getMessage()));}
         catch(BusinessConfigService.Failure invalid){throw new AgentProblem(invalid.status==409?"VERSION_CONFLICT":"BUSINESS_VALIDATION",safe(invalid.getMessage()));}
        });
    }
    private static String safe(String message){return message!=null&&message.length()<=500&&message.codePoints().noneMatch(Character::isISOControl)?message:"业务校验未通过，请检查字段、权限和当前版本。";}
}
