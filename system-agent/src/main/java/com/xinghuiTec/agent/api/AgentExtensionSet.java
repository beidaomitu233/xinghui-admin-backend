package com.xinghuiTec.agent.api;

import io.agentscope.core.skill.repository.AgentSkillRepository;
import io.agentscope.core.tool.Toolkit;
import io.agentscope.core.tool.mcp.McpClientWrapper;
import java.util.*;

/** Per-invocation registrations consumed by the official Harness and Toolkit. */
public record AgentExtensionSet(AgentSkillRepository skills,List<Mcp> mcps) implements AutoCloseable {
    public record Mcp(McpClientWrapper client,List<String> tools,boolean allowWrites) {}
    public static AgentExtensionSet empty(){return new AgentExtensionSet(null,List.of());}
    public Set<String> install(Toolkit toolkit,boolean execute){
        var names=new HashSet<String>();
        for(var connection:mcps){
            var before=new HashSet<>(toolkit.getToolNames());
            toolkit.registration().mcpClient(connection.client()).enableTools(connection.tools()).apply();
            for(String name:Set.copyOf(toolkit.getToolNames()))if(!before.contains(name)){
                if(!toolkit.getTool(name).isReadOnly()&&(!execute||!connection.allowWrites()))toolkit.removeTool(name);
                else names.add(name);
            }
        }
        return Set.copyOf(names);
    }
    @Override public void close(){
        for(var connection:mcps)try{connection.client().close();}catch(RuntimeException ignored){}
        if(skills!=null)try{skills.close();}catch(Exception ignored){}
    }
}
