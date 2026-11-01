package com.xinghuiTec.ai.agent;

import com.xinghuiTec.agent.api.*;
import io.agentscope.core.tool.mcp.McpClientWrapper;
import io.modelcontextprotocol.spec.McpSchema;
import reactor.core.publisher.Mono;
import java.util.*;

/** Authentication adapter around the official MCP client; protocol and tool conversion stay native. */
final class AuthorizedMcpClient extends McpClientWrapper {
    private final McpClientWrapper delegate;private final AgentCall call;private final AgentExtensionService.Entry snapshot;
    private final AgentExtensionService service;private final AgentAccess access;private final boolean execute;
    AuthorizedMcpClient(McpClientWrapper delegate,AgentCall call,AgentExtensionService.Entry snapshot,AgentExtensionService service,AgentAccess access,boolean execute){
        super(delegate.getName());this.delegate=delegate;this.call=call;this.snapshot=snapshot;this.service=service;this.access=access;this.execute=execute;
    }
    public Mono<Void> initialize(){return delegate.initialize().doOnSuccess(value->initialized=true);}
    public Mono<List<McpSchema.Tool>> listTools(){return delegate.listTools().doOnNext(tools->{cachedTools.clear();tools.forEach(tool->cachedTools.put(tool.name(),tool));});}
    public Mono<McpSchema.CallToolResult> callTool(String name,Map<String,Object> arguments){return callTool(name,arguments,null);}
    public Mono<McpSchema.CallToolResult> callTool(String name,Map<String,Object> arguments,Map<String,Object> meta){
        return Mono.defer(()->{
            call.check();access.as(call.actor(),context->{
                var current=service.get(call.actor().tenantId(),snapshot.id());
                if(current.version()!=snapshot.version()||!service.available(context.actor(),current))throw new AgentProblem("FORBIDDEN","MCP授权或配置已变更，请重新发起任务");
                var tool=cachedTools.get(name);if(tool==null)throw new AgentProblem("TOOL_UNAVAILABLE","MCP工具已不可用");
                boolean readOnly=tool.annotations()!=null&&Boolean.TRUE.equals(tool.annotations().readOnlyHint());
                if(!readOnly&&(!execute||!current.config().path("allowWrites").asBoolean(false)))throw new AgentProblem("PLAN_READ_ONLY","当前模式不能执行此MCP操作");
                if(current.config().path("tools").isArray()&&current.config().path("tools").size()>0){
                    boolean selected=false;for(var configured:current.config().path("tools"))if(configured.asText().equals(name))selected=true;
                    if(!selected)throw new AgentProblem("FORBIDDEN","MCP工具未启用");
                }
                return null;
            });
            return delegate.callTool(name,arguments,meta).doOnNext(result->call.check());
        });
    }
    public void close(){delegate.close();}
}
