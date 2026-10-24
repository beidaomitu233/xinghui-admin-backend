package com.xinghuiTec.ai.agent;

import com.fasterxml.jackson.databind.JsonNode;
import com.xinghuiTec.agent.api.*;
import com.xinghuiTec.agent.persistence.JdbcAgentRepository;
import com.xinghuiTec.businessconfig.BusinessConfigService;
import com.xinghuiTec.config.SecretCipher;
import com.xinghuiTec.lowcode.*;
import com.xinghuiTec.oss.config.OssRuntimeSettings;
import com.xinghuiTec.sms.core.RuntimeSmsService;
import com.xinghuiTec.wechat.WechatConfigService;
import org.junit.jupiter.api.*;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.*;
import java.time.*;
import java.nio.charset.StandardCharsets;
import java.util.*;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

class AgentBusinessResourceTest {
    AgentAdapterFixture f;JdbcTemplate jdbc;LowcodeService forms;AgentResourceRegistry registry;JdbcAgentRepository repository;AgentCall call;String resource;BusinessConfigService configs;
    @BeforeEach void setup()throws Exception {
        f=new AgentAdapterFixture();jdbc=new JdbcTemplate(new DriverManagerDataSource("jdbc:h2:mem:agentresources_"+UUID.randomUUID()+";MODE=MySQL;DATABASE_TO_LOWER=TRUE;DB_CLOSE_DELAY=-1","sa",""));
        String lowcode=migration("V20261012_160__lowcode_forms.sql");for(String sql:lowcode.split(";"))if(sql.strip().startsWith("CREATE TABLE IF NOT EXISTS sys_lowcode_"))jdbc.execute(sql);
        for(String sql:migration("V20261024_400__agent_workbench.sql").replace(" ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_bin","").split(";"))if(!sql.isBlank())jdbc.execute(sql);
        jdbc.execute("CREATE TABLE sys_business_config(section VARCHAR(16) PRIMARY KEY,enabled BOOLEAN,parameters_json TEXT,secrets_ciphertext TEXT,revision INT,update_time TIMESTAMP DEFAULT CURRENT_TIMESTAMP)");
        jdbc.execute("CREATE TABLE sys_business_config_history(section VARCHAR(16),revision INT,enabled BOOLEAN,parameters_json TEXT,secrets_ciphertext TEXT,PRIMARY KEY(section,revision))");
        var schemas=new LowcodeSchema(f.json);forms=new LowcodeService(jdbc,schemas,new LowcodeIdentity(true));
        var cipher=new SecretCipher(()->"AAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAA=");configs=new BusinessConfigService(jdbc,f.json,cipher,mock(WechatConfigService.class),mock(OssRuntimeSettings.class),mock(RuntimeSmsService.class));
        registry=new AgentResourceRegistry(List.of(new LowcodeAgentResources(forms,f.access,f.json),new BusinessConfigAgentResources(configs,f.access,f.json)),f.access,f.json);
        repository=new JdbcAgentRepository(jdbc,new DataSourceTransactionManager(jdbc.getDataSource()),new AgentPayloadCipher(cipher),registry,f.json);
        resource="forms:"+createForm("first_form");var session=repository.create(f.a,"QA");var admitted=repository.admit(f.a,session.id(),UUID.randomUUID().toString(),"Synthetic task","scene.agent-fixture",Duration.ofMinutes(1));repository.claim(f.a,admitted.run().id());
        call=new AgentCall(f.a,session.id(),admitted.run().id(),UUID.randomUUID().toString(),Instant.now().plusSeconds(60),()->false);
    }
    String migration(String name)throws Exception{try(var stream=getClass().getResourceAsStream("/db/migration/"+name)){assertNotNull(stream);return new String(stream.readAllBytes(),StandardCharsets.UTF_8).lines().filter(line->!line.stripLeading().startsWith("--")).collect(java.util.stream.Collectors.joining("\n"));}}
    String createForm(String key){return f.access.as(f.platform,context->{var schema=new LowcodeSchema.Schema(List.of(new LowcodeSchema.Field("title","商品名称","text",true,null,List.of(),"",100,null,null,true,true),new LowcodeSchema.Field("amount","金额","number",false,null,List.of(),"",null,null,null,true,true),new LowcodeSchema.Field("available","上架","switch",false,null,List.of(),"",null,null,null,true,true)));var id=forms.create(new LowcodeService.Draft("虚构业务",key,"draft",0,schema));forms.publish(Long.parseLong(id),0);return id;});}
    JsonNode value(String json)throws Exception{return f.json.readTree(json);}
    JsonNode mutate(String id,String operation,JsonNode input){return repository.mutateOnce(call,id,operation,input,()->registry.mutate(call,id,operation,input));}
    @AfterEach void close(){if(jdbc!=null)jdbc.execute("SHUTDOWN");if(f!=null)f.close();}
    @Test void newPublishedBusinessesReuseOneAdapterAndNativeTypedCrudWithTenantIsolation()throws Exception {
        assertTrue(registry.describe(call,resource).path("operations").toString().contains("bulkCreate"));
        String second=createForm("second_form");assertEquals("forms:"+second,registry.query(f.call(f.platform),"forms",value("{}" )).path("records").get(0).path("resource").asText());
        var add=value("{\"operation\":\"create\",\"schemaVersion\":1,\"data\":{\"title\":\"QA product\",\"amount\":0,\"available\":false}}");
        var saved=mutate("create-one",resource,add);String id=saved.path("id").asText();assertFalse(id.isBlank());assertEquals(0,saved.path("data").path("amount").intValue());assertFalse(saved.path("data").path("available").booleanValue());
        assertEquals(id,mutate("create-one",resource,add).path("id").asText());assertEquals(1,jdbc.queryForObject("SELECT COUNT(*) FROM sys_lowcode_record",Integer.class));
        var projected=registry.query(call,resource,value("{\"id\":\""+id+"\",\"fields\":[\"title\"]}"));assertEquals(1,projected.path("data").size());
        assertEquals("0",registry.query(f.call(f.b),resource,value("{}" )).path("total").asText());assertThrows(AgentProblem.class,()->registry.query(f.call(f.b),resource,value("{\"id\":\""+id+"\"}")));
        var update=value("{\"operation\":\"update\",\"id\":\""+id+"\",\"schemaVersion\":1,\"revision\":0,\"data\":{\"title\":\"Edited\",\"amount\":1,\"available\":true}}");
        assertEquals(1,mutate("update-one",resource,update).path("revision").intValue());assertThrows(AgentProblem.class,()->mutate("stale-update",resource,update));
        mutate("remove-one",resource,value("{\"operation\":\"delete\",\"id\":\""+id+"\",\"revision\":1}"));assertEquals("0",registry.query(call,resource,value("{}" )).path("total").asText());
    }
    @Test void bulkFailureRollsBackAllWritesAndUnknownIdentityArgumentsCannotChangeScope()throws Exception {
        var bulk=value("{\"operation\":\"bulkCreate\",\"items\":[{\"schemaVersion\":1,\"data\":{\"title\":\"First\"}},{\"schemaVersion\":1,\"data\":{\"title\":42}}]}");
        assertThrows(AgentProblem.class,()->mutate("bulk-invalid",resource,bulk));assertEquals(0,jdbc.queryForObject("SELECT COUNT(*) FROM sys_lowcode_record",Integer.class));assertEquals(0,jdbc.queryForObject("SELECT COUNT(*) FROM sys_agent_tool_receipt",Integer.class));
        assertThrows(AgentProblem.class,()->registry.query(call,resource,value("{\"tenantId\":\"tenant_b\"}")));
        f.permissions.remove("lowcode:record:add");assertThrows(AgentProblem.class,()->mutate("add-denied",resource,value("{\"operation\":\"create\",\"schemaVersion\":1,\"data\":{\"title\":\"Denied\"}}")));
    }
    @Test void historicalAnswersAndReceiptReplayRecheckCurrentResourcePermissions()throws Exception {
        var input=value("{\"operation\":\"create\",\"schemaVersion\":1,\"data\":{\"title\":\"Private fixture\"}}");mutate("history-add",resource,input);
        assertNotNull(repository.session(f.a,call.sessionId()));f.permissions.remove("lowcode:record:list");f.permissions.remove("lowcode:record:query");
        assertThrows(AgentProblem.class,()->repository.session(f.a,call.sessionId()));assertThrows(AgentProblem.class,()->mutate("history-add",resource,input));
    }
    @Test void globalConfigurationUsesNativeRevisionAndWriteOnlySecretHandling()throws Exception {
        assertThrows(AgentProblem.class,()->registry.describe(call,"business-config:oss"));var admin=f.call(f.platform);
        var first=registry.mutate(admin,"config-one","business-config:oss",value("{\"operation\":\"update\",\"revision\":0,\"parameters\":{\"bucket\":\"qa-only\"},\"secrets\":{\"secretKey\":\"synthetic-private-secret\"}}"));
        assertTrue(first.path("secretsConfigured").path("secretKey").asBoolean());assertFalse(first.toString().contains("synthetic-private-secret"));
        var next=registry.mutate(admin,"config-two","business-config:oss",value("{\"operation\":\"update\",\"revision\":1,\"parameters\":{\"basePath\":\"qa\"}}"));assertEquals("qa-only",next.path("parameters").path("bucket").asText());assertTrue(next.path("secretsConfigured").path("secretKey").asBoolean());
        assertThrows(AgentProblem.class,()->registry.mutate(admin,"config-stale","business-config:oss",value("{\"operation\":\"update\",\"revision\":0}")));
        assertFalse(registry.query(admin,"business-config:oss",value("{}" )).toString().contains("synthetic-private-secret"));
        f.permissions.remove("system:businessConfig:edit");assertThrows(AgentProblem.class,()->registry.mutate(admin,"config-denied","business-config:oss",value("{\"operation\":\"update\",\"revision\":2}")));
    }
}
