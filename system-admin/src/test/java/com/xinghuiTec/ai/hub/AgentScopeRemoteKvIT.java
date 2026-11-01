package com.xinghuiTec.ai.hub;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.xinghuiTec.agent.api.*;
import com.xinghuiTec.agent.runtime.AgentScopeBinding;
import io.agentscope.core.agent.RuntimeContext;
import io.agentscope.extensions.redis.RedisDistributedStore;
import io.agentscope.harness.agent.filesystem.sandbox.AbstractSandboxFilesystem;
import org.junit.jupiter.api.*;
import org.junit.jupiter.api.io.TempDir;
import reactor.core.publisher.Flux;
import redis.clients.jedis.params.ScanParams;
import java.nio.file.Path;
import java.time.*;
import java.util.*;
import static org.assertj.core.api.Assertions.*;

/** Real Redis + official Harness/RemoteFilesystemSpec. Fixed synthetic model; never calls a provider. */
class AgentScopeRemoteKvIT {
    @TempDir Path workspace;
    @Test void nativeRemoteKvPersistsAcrossAgentInstancesWithoutShell() {
        String port=Objects.requireNonNull(System.getenv("REDIS_PORT"),"Assigned Redis required");
        if(!"true".equals(System.getenv("CI"))&&!"16384".equals(port))throw new IllegalStateException("Use only CI or the assigned local QA Redis");
        String prefix="xh:hub:qa:"+UUID.randomUUID()+":";
        var config=new AgentScopeKvConfiguration();
        try(var jedis=config.agentScopeKvClient("127.0.0.1",Integer.parseInt(port),Integer.parseInt(System.getenv().getOrDefault("REDIS_DATABASE","15")),"",System.getenv().getOrDefault("REDIS_PASSWORD",""))){
            var store=RedisDistributedStore.fromJedis(jedis,prefix);var json=new ObjectMapper();
            var call=new AgentCall(new AgentActor("qa_tenant","42"),UUID.randomUUID().toString(),UUID.randomUUID().toString(),"qa",Instant.now().plusSeconds(30),()->false);
            var context=RuntimeContext.builder().userId(call.actor().stateUserId()).sessionId(call.sessionId()).build();
            var resources=new AgentResourceGateway(){
                public com.fasterxml.jackson.databind.JsonNode resources(AgentCall c){return json.createArrayNode();}
                public com.fasterxml.jackson.databind.JsonNode describe(AgentCall c,String r){throw new AssertionError();}
                public com.fasterxml.jackson.databind.JsonNode query(AgentCall c,String r,com.fasterxml.jackson.databind.JsonNode a){throw new AssertionError();}
                public com.fasterxml.jackson.databind.JsonNode mutate(AgentCall c,String t,String r,com.fasterxml.jackson.databind.JsonNode a){throw new AssertionError();}
            };
            AgentModelGateway model=(c,w)->Flux.just("{\"choices\":[{\"index\":0,\"delta\":{\"content\":\"QA已接收\"},\"finish_reason\":null}]}","{\"choices\":[{\"index\":0,\"delta\":{},\"finish_reason\":\"stop\"}]}");
            try{
                try(var first=new AgentScopeBinding(call,"QA business tools",workspace,store.agentStateStore(),model,resources,json,true,"PLAN",store)){
                    var fs=first.nativeAgent().getWorkspaceManager().getFilesystem();
                    assertThat(fs).isNotInstanceOf(AbstractSandboxFilesystem.class);
                    assertThat(first.nativeAgent().getToolkit().getToolNames()).doesNotContain("execute","shell");
                    assertThat(fs.write(context,"/plans/qa.md","QA_NATIVE_PLAN").isSuccess()).isTrue();
                    first.run("看看商品").blockLast(Duration.ofSeconds(20));
                }
                AgentModelGateway continued=(c,w)->{assertThat(w).contains("看看商品");return model.stream(c,w);};
                try(var second=new AgentScopeBinding(call,"QA business tools",workspace,store.agentStateStore(),continued,resources,json,true,"EXECUTE",store)){
                    var fs=second.nativeAgent().getWorkspaceManager().getFilesystem();
                    assertThat(fs.read(context,"/plans/qa.md",0,100).fileData().content()).contains("QA_NATIVE_PLAN");
                    var other=RuntimeContext.builder().userId(call.actor().stateUserId()).sessionId(UUID.randomUUID().toString()).build();
                    assertThat(fs.read(other,"/plans/qa.md",0,100).isSuccess()).isFalse();
                    second.run("接着做").blockLast(Duration.ofSeconds(20));
                }
            }finally{
                String cursor="0";do{var result=jedis.scan(cursor,new ScanParams().match(prefix+"*").count(100));if(!result.getResult().isEmpty())jedis.del(result.getResult().toArray(String[]::new));cursor=result.getCursor();}while(!cursor.equals("0"));
            }
        }
    }
}
