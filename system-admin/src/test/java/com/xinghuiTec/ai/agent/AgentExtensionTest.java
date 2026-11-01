package com.xinghuiTec.ai.agent;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.sun.net.httpserver.HttpServer;
import com.xinghuiTec.agent.api.*;
import com.xinghuiTec.config.SecretCipher;
import io.agentscope.core.tool.Toolkit;
import io.agentscope.core.tool.ToolCallParam;
import io.agentscope.core.tool.mcp.McpClientWrapper;
import io.modelcontextprotocol.spec.McpSchema;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import reactor.core.publisher.Mono;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.util.*;
import java.util.concurrent.atomic.AtomicInteger;
import static org.junit.jupiter.api.Assertions.*;

/** Native SDK registration and real HTTP retrieval against synthetic local services. */
class AgentExtensionTest {
    final ObjectMapper json=new ObjectMapper();
    AgentExtensionService service(AgentAdapterFixture f){
        var jdbc=new JdbcTemplate(new DriverManagerDataSource("jdbc:h2:mem:extensions_"+UUID.randomUUID()+";MODE=MySQL;DB_CLOSE_DELAY=-1","sa",""));
        jdbc.execute("CREATE TABLE sys_ai_agent_extension(extension_id VARCHAR(36) PRIMARY KEY,tenant_ref VARCHAR(64),kind VARCHAR(16),code VARCHAR(64),name VARCHAR(100),description VARCHAR(1000),enabled BOOLEAN,config_json CLOB,credential_ciphertext CLOB,version BIGINT DEFAULT 0,updated_at TIMESTAMP)");
        return new AgentExtensionService(jdbc,json,new SecretCipher(()->Base64.getEncoder().encodeToString(new byte[32])),f.access);
    }
    @Test void managedSkillLoadsThroughOfficialRepositoryAndSecretsStayServerSide()throws Exception{
        try(var f=new AgentAdapterFixture()){
            var service=service(f);var actor=f.access.as(f.a,AgentAccess.Context::actor);
            var skill=service.save(actor,new AgentExtensionService.Draft(null,"SKILL","sales-guide","销售指引","查询商品与销售",true,json.readTree("{\"content\":\"# 销售指引\\n先查询商品，再汇总价格。\"}"),null,false,null));
            try(var extensions=service.open(f.call(f.a),"EXECUTE")){
                assertTrue(extensions.skills().skillExists("sales-guide"));
                assertTrue(extensions.skills().getSkill("sales-guide").getSkillContent().contains("先查询商品"));
                assertTrue(extensions.skills().getAllSkills().size()>1);
            }
            assertTrue(service.list(f.b.tenantId()).isEmpty());
            var mcp=service.save(actor,new AgentExtensionService.Draft(null,"MCP","reports","报表","查询报表",false,json.readTree("{\"url\":\"http://127.0.0.1:12345/mcp\",\"transport\":\"HTTP\"}"),"synthetic-api-key",false,null));
            assertTrue(mcp.secretSet());assertFalse(json.writeValueAsString(mcp).contains("synthetic-api-key"));
            assertEquals("synthetic-api-key",service.secret(actor.tenantId(),mcp));
        }
    }
    @Test void officialToolkitFiltersWriteToolsAndRechecksDisabledMcpBeforeExecution()throws Exception{
        try(var f=new AgentAdapterFixture()){
            var service=service(f);var actor=f.access.as(f.a,AgentAccess.Context::actor);
            var entry=service.save(actor,new AgentExtensionService.Draft(null,"MCP","qa-tools","QA tools","fixture",true,json.readTree("{\"url\":\"http://127.0.0.1:12345/mcp\",\"allowWrites\":true}"),null,false,null));
            var calls=new AtomicInteger();
            var raw=new McpClientWrapper("qa-tools"){
                public Mono<Void> initialize(){initialized=true;return Mono.empty();}
                public Mono<List<McpSchema.Tool>> listTools(){try{return Mono.just(List.of(
                    json.readValue("{\"name\":\"lookup\",\"description\":\"查询报表\",\"inputSchema\":{\"type\":\"object\",\"properties\":{}},\"annotations\":{\"readOnlyHint\":true}}",McpSchema.Tool.class),
                    json.readValue("{\"name\":\"update\",\"description\":\"修改报表\",\"inputSchema\":{\"type\":\"object\",\"properties\":{}},\"annotations\":{\"readOnlyHint\":false}}",McpSchema.Tool.class)));}catch(Exception e){return Mono.error(e);}}
                public Mono<McpSchema.CallToolResult> callTool(String name,Map<String,Object> args){return callTool(name,args,null);}
                public Mono<McpSchema.CallToolResult> callTool(String name,Map<String,Object> args,Map<String,Object> meta){calls.incrementAndGet();try{return Mono.just(json.readValue("{\"content\":[{\"type\":\"text\",\"text\":\"QA result\"}],\"isError\":false}",McpSchema.CallToolResult.class));}catch(Exception e){return Mono.error(e);}}
                public void close(){}
            };
            var client=new AuthorizedMcpClient(raw,f.call(f.a),entry,service,f.access,true);
            var toolkit=new Toolkit();
            try(var extensions=new AgentExtensionSet(null,List.of(new AgentExtensionSet.Mcp(client,List.of(),true)))){
                var names=extensions.install(toolkit,false);
                assertEquals(1,names.size());var tool=toolkit.getTool(names.iterator().next());assertTrue(tool.isReadOnly());
                var result=tool.callAsync(ToolCallParam.builder().input(Map.of()).build()).block();assertNotNull(result);assertEquals(1,calls.get());
                service.save(actor,new AgentExtensionService.Draft(entry.id(),entry.kind(),entry.code(),entry.name(),entry.description(),false,entry.config(),null,false,entry.version()));
                assertThrows(Exception.class,()->client.callTool("lookup",Map.of()).block());assertEquals(1,calls.get());
            }
        }
    }
    @Test void difyAndFastgptReturnNormalizedCitationsThroughBusinessGateway()throws Exception{
        try(var f=new AgentAdapterFixture()){
            var service=service(f);var provider=new ExternalKnowledgeResources(service,f.access,json);var actor=f.access.as(f.a,AgentAccess.Context::actor);
            var requests=new AtomicInteger();var server=HttpServer.create(new InetSocketAddress("127.0.0.1",0),0);
            server.createContext("/mcp",exchange->{try{
                if(!exchange.getRequestMethod().equals("POST")){exchange.sendResponseHeaders(405,-1);return;}
                var request=json.readTree(exchange.getRequestBody());
                if(!request.has("id")){exchange.sendResponseHeaders(202,-1);return;}
                var response=json.createObjectNode().put("jsonrpc","2.0");response.set("id",request.get("id"));
                var result=json.createObjectNode();String method=request.path("method").asText();
                if(method.equals("initialize")){
                    result.put("protocolVersion",request.path("params").path("protocolVersion").asText());
                    result.set("capabilities",json.readTree("{\"tools\":{}}"));result.set("serverInfo",json.readTree("{\"name\":\"qa-mcp\",\"version\":\"1\"}"));
                }else if(method.equals("tools/list"))result.set("tools",json.readTree("[{\"name\":\"lookup\",\"description\":\"QA report\",\"inputSchema\":{\"type\":\"object\",\"properties\":{}},\"annotations\":{\"readOnlyHint\":true}}]"));
                else result.set("content",json.readTree("[{\"type\":\"text\",\"text\":\"QA result\"}]"));
                response.set("result",result);byte[] bytes=json.writeValueAsBytes(response);exchange.getResponseHeaders().set("Content-Type","application/json");exchange.sendResponseHeaders(200,bytes.length);exchange.getResponseBody().write(bytes);
            }finally{exchange.close();}});
            server.createContext("/",exchange->{try{
                var input=json.readTree(exchange.getRequestBody());assertEquals("Bearer synthetic-key",exchange.getRequestHeaders().getFirst("Authorization"));
                String body;
                if(exchange.getRequestURI().getPath().endsWith("/retrieve")){
                    assertEquals("退货流程",input.path("query").asText());assertEquals(3,input.path("retrieval_model").path("top_k").asInt());
                    body="{\"records\":[{\"score\":0.91,\"segment\":{\"id\":\"s1\",\"content\":\"七天内申请退货\",\"document\":{\"name\":\"售后手册\"}}}]}";
                }else{
                    assertEquals(5000,input.path("limit").asInt());assertEquals("退货流程",input.path("text").asText());
                    body="{\"data\":{\"list\":[{\"id\":\"s2\",\"q\":\"退货流程\",\"a\":\"联系客服\",\"sourceName\":\"售后问答\",\"score\":0.88}]}}";
                }
                requests.incrementAndGet();byte[] bytes=body.getBytes(StandardCharsets.UTF_8);exchange.getResponseHeaders().set("Content-Type","application/json");exchange.sendResponseHeaders(200,bytes.length);exchange.getResponseBody().write(bytes);
            }finally{exchange.close();}});server.start();
            try{
                for(String kind:List.of("DIFY","FASTGPT")){
                    var spec=json.createObjectNode().put("url","http://127.0.0.1:"+server.getAddress().getPort()+ (kind.equals("DIFY")?"/v1":"/api")).put("provider",kind).put("datasetId","qa-dataset").put("topK",3);
                    var entry=service.save(actor,new AgentExtensionService.Draft(null,"KNOWLEDGE",kind.toLowerCase(Locale.ROOT),kind,"售后知识",true,spec,"synthetic-key",false,null));
                    var output=f.access.as(f.a,context->provider.query(f.call(f.a),"knowledge:"+entry.id(),json.createObjectNode().put("query","退货流程")));
                    assertEquals(1,output.path("records").size());assertFalse(output.path("records").get(0).path("document").asText().isBlank());
                    assertThrows(AgentProblem.class,()->f.access.as(f.b,context->provider.query(f.call(f.b),"knowledge:"+entry.id(),json.createObjectNode().put("query","退货流程"))));
                }
                assertEquals(2,requests.get());
                var mcpSpec=json.createObjectNode().put("url","http://127.0.0.1:"+server.getAddress().getPort()+"/mcp").put("transport","HTTP");
                var mcp=service.save(actor,new AgentExtensionService.Draft(null,"MCP","remote-fixture","Remote fixture","QA report",true,mcpSpec,null,false,null));
                assertEquals("lookup",service.discover(actor,mcp.id()).get(0).get("name"));
                try(var extensions=service.open(f.call(f.a),"EXECUTE")){
                    var toolkit=new Toolkit();var installed=extensions.install(toolkit,true);assertEquals(1,installed.size());
                    assertNotNull(toolkit.getTool(installed.iterator().next()).callAsync(ToolCallParam.builder().input(Map.of()).build()).block());
                }

            }finally{server.stop(0);}
        }
    }
}
