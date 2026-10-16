package com.xinghuiTec.ai.prompt;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.xinghuiTec.ai.prompt.AiBusinessPromptRendererTest.NeutralResolver;
import com.xinghuiTec.ai.prompt.AiPromptSceneRules.VariableType;
import com.xinghuiTec.config.redis.RedisConfig;
import com.xinghuiTec.utils.JwtUtil;
import com.xinghuiTec.utils.RedisCacheUtils;
import io.lettuce.core.resource.DefaultClientResources;
import jakarta.servlet.Filter;
import org.apache.catalina.startup.Tomcat;
import org.apache.tomcat.util.descriptor.web.FilterDef;
import org.apache.tomcat.util.descriptor.web.FilterMap;
import org.flywaydb.core.Flyway;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import org.springframework.data.redis.connection.RedisStandaloneConfiguration;
import org.springframework.data.redis.connection.lettuce.LettuceClientConfiguration;
import org.springframework.data.redis.connection.lettuce.LettuceConnectionFactory;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import org.springframework.test.util.ReflectionTestUtils;
import org.springframework.web.context.support.GenericWebApplicationContext;
import org.springframework.web.servlet.DispatcherServlet;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.TimeUnit;

import static com.xinghuiTec.ai.prompt.AiBusinessPromptRendererTest.*;
import static org.junit.jupiter.api.Assertions.*;

/** Explicit opt-in. Existing QA schema is validated, never migrated or recreated. */
@EnabledIfEnvironmentVariable(named="AI04_QA_ENABLED",matches="true")
class AiSceneReadNativeQaTest {
    static final String A="ai04_fixture_20261011_alpha",B="ai04_fixture_20261011_beta",MODEL="ai04fixture20261011";
    static final long USER=2026101104001L,OTHER=2026101104002L;
    final ObjectMapper json=new ObjectMapper();
    final HttpClient client=HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(3)).build();
    int requests;
    HttpResponse<String> call(String path,String body,String token) throws Exception {
        var builder=HttpRequest.newBuilder(URI.create("http://127.0.0.1:7801"+path)).timeout(Duration.ofSeconds(5))
                .header("Content-Type","application/json").POST(HttpRequest.BodyPublishers.ofString(body));
        if(token!=null) builder.header("Authorization",token);
        requests++; return client.send(builder.build(),HttpResponse.BodyHandlers.ofString());
    }
    static void filter(org.apache.catalina.Context web,String name,Filter filter) {
        var def=new FilterDef(); def.setFilterName(name); def.setFilter(filter); web.addFilterDef(def);
        var mapping=new FilterMap(); mapping.setFilterName(name); mapping.addURLPattern("/*"); web.addFilterMap(mapping);
    }
    @Test void nativeHttpUsesRealJwtRedisPublishedSqlSnapshotAndDecodedPathBoundary() throws Exception {
        String password=System.getenv("AI04_QA_PASSWORD"),redisPassword=System.getenv("AI04_QA_REDIS_PASSWORD");
        assertNotNull(password); assertNotNull(redisPassword);
        var source=new DriverManagerDataSource("jdbc:mysql://127.0.0.1:13317/xinghui_ai_qa_20261011?useUnicode=true&characterEncoding=utf8&useSSL=false&serverTimezone=Asia/Shanghai","qa_ai",password);
        var jdbc=new JdbcTemplate(source);
        var identity=jdbc.queryForMap("SELECT DATABASE() db,CURRENT_USER() account,@@port port,@@datadir dir,@@version version");
        assertEquals("xinghui_ai_qa_20261011",identity.get("db")); assertEquals("qa_ai@127.0.0.1",identity.get("account"));
        assertEquals(13317,((Number)identity.get("port")).intValue()); assertEquals("8.0.12",identity.get("version"));
        assertEquals("d:/changhuiengineer/devproject/xinghui-admin2/.qa-local/20261011/mysql-data/",identity.get("dir").toString().replace('\\','/').toLowerCase(java.util.Locale.ROOT));
        assertEquals(16,jdbc.queryForObject("SELECT COUNT(*) FROM flyway_schema_history WHERE success=1",Integer.class));
        assertEquals(0,jdbc.queryForObject("SELECT COUNT(*) FROM flyway_schema_history WHERE success=0",Integer.class));
        Flyway.configure().dataSource(source).locations("classpath:db/migration","classpath:db/optional-migration")
                .baselineOnMigrate(false).outOfOrder(false).cleanDisabled(true).load().validate();
        assertEquals(0,jdbc.queryForObject("SELECT COUNT(*) FROM sys_ai_prompt_scene WHERE app_key IN (?,?)",Integer.class,A,B));
        assertEquals(0,jdbc.queryForObject("SELECT COUNT(*) FROM sys_ai_model_config WHERE provider_code='ai04-qa' AND model_code=?",Integer.class,MODEL));
        var resources=DefaultClientResources.builder().ioThreadPoolSize(2).computationThreadPoolSize(2).build();
        var redisConfig=new RedisStandaloneConfiguration("127.0.0.1",16379); redisConfig.setDatabase(11); redisConfig.setPassword(redisPassword);
        var redis=new LettuceConnectionFactory(redisConfig,LettuceClientConfiguration.builder().clientResources(resources).commandTimeout(Duration.ofSeconds(3)).build());
        var rules=new AiPromptSceneRules(); var scenes=new AiPromptSceneService(jdbc,json,rules);
        Tomcat tomcat=null; GenericWebApplicationContext context=null; Long model=null;
        var keys=List.of("admin:login:"+USER,"admin:login:"+OTHER);
        var ownedKeys=new java.util.ArrayList<String>(); var ownedScenes=new java.util.ArrayList<Long>();
        RedisCacheUtils cache=new RedisCacheUtils();
        try {
            redis.afterPropertiesSet(); redis.start(); var template=RedisConfig.createTemplate(redis);
            ReflectionTestUtils.setField(cache,"redisTemplate",template);
            for(String key:keys) assertNull(cache.getCacheObject(key),"Fixture login key must be absent");
            jdbc.update("INSERT INTO sys_ai_model_config(provider_code,model_code,endpoint_url,timeout_seconds,enabled,revision) VALUES ('ai04-qa',?,'https://example.invalid/ai04-fixture',30,1,0)",MODEL);
            model=jdbc.queryForObject("SELECT model_id FROM sys_ai_model_config WHERE provider_code='ai04-qa' AND model_code=?",Long.class,MODEL);
            long first=scenes.create(new AiPromptSceneService.CreateRequest(A,"summary",model,"{{title}} / {{privateNote}}",List.of(definition("title",VariableType.STRING,false),definition("privateNote",VariableType.STRING,true)))); ownedScenes.add(first);
            long second=scenes.create(new AiPromptSceneService.CreateRequest(B,"summary",model,"{{score}} / {{ready}}",List.of(definition("score",VariableType.NUMBER,false),definition("ready",VariableType.BOOLEAN,false)))); ownedScenes.add(second);
            for(long id:ownedScenes) { var published=scenes.publish(id,new AiPromptSceneService.RevisionRequest(0)); scenes.setEnabled(id,new AiPromptSceneService.StatusRequest(true,published.revision())); }
            var alpha=new NeutralResolver(A,Set.of("title","privateNote"),Map.of("title","Native HTTP Alpha","privateNote",SECRET)); alpha.owner=USER;
            var beta=new NeutralResolver(B,Set.of("score","ready"),Map.of("score","8.5","ready","false")); beta.owner=USER;
            String token=JwtUtil.createJWT(Long.toString(USER),"tenant-a"),other=JwtUtil.createJWT(Long.toString(OTHER),"tenant-b");
            ownedKeys.add(keys.get(0)); cache.setCacheObject(keys.get(0),AiSceneReadHttpTest.session(USER,"tenant-a",token,A+":read",B+":read"),600,TimeUnit.SECONDS);
            ownedKeys.add(keys.get(1)); cache.setCacheObject(keys.get(1),AiSceneReadHttpTest.session(OTHER,"tenant-b",other,A+":read"),600,TimeUnit.SECONDS);
            tomcat=new Tomcat(); Path base=Path.of("target/ai04-native-tomcat").toAbsolutePath(); Files.createDirectories(base);
            tomcat.setBaseDir(base.toString()); tomcat.setPort(7801); tomcat.getConnector().setProperty("address","127.0.0.1");
            var web=tomcat.addContext("",base.toString()); var audit=new AiSceneReadHttpTest.Audit();
            context=AiSceneReadHttpTest.context(web.getServletContext(),rules,scenes,alpha,beta,cache,audit);
            var servlet=Tomcat.addServlet(web,"ai04",new DispatcherServlet(context)); servlet.setLoadOnStartup(1); web.addServletMappingDecoded("/","ai04");
            filter(web,"ai04Boundary",context.getBean(AiSceneReadBoundaryFilter.class)); filter(web,"security",context.getBean("springSecurityFilterChain",Filter.class));
            tomcat.start();
            String path="/ai/scenes/"+A+"/summary/render",body="{\"context\":{\"resourceId\":\"record-1\"}}";
            var ok=call(path,body,token); assertEquals(200,ok.statusCode()); var data=json.readTree(ok.body()).get("data");
            assertEquals(5,data.size()); assertEquals("Native HTTP Alpha / "+SECRET,data.get("prompt").asText()); assertEquals(model.toString(),data.get("modelId").asText());
            assertFalse(ok.body().contains("endpoint")); assertFalse(ok.body().contains("credentialEnvName"));
            assertEquals("8.5 / false",json.readTree(call("/ai/scenes/"+B+"/summary/render",body,token).body()).get("data").get("prompt").asText());
            var current=scenes.get(first); scenes.updateDraft(first,new AiPromptSceneService.DraftRequest(model,"DRAFT {{title}}",List.of(definition("title",VariableType.STRING,false)),current.revision()));
            assertEquals(data,json.readTree(call(path,body,token).body()).get("data"));
            assertEquals(401,call(path,body,null).statusCode());
            var denied=call(path,body,other); assertEquals(403,denied.statusCode()); assertFalse(denied.body().contains(SECRET));
            assertEquals(400,call(path,"{\"context\":{\"resourceId\":\"record-1\",\"tenantId\":\"tenant-a\"}}",token).statusCode());
            int before=audit.events.size();
            var encoded=call("/%61i/scenes/"+A+"/summary/render",body,token); assertEquals(200,encoded.statusCode());
            assertEquals("no-store",encoded.headers().firstValue("Cache-Control").orElseThrow()); assertEquals(before+1,audit.events.size());
            var large=call("/%61i/scenes/"+A+"/summary/render","x".repeat(16385),token); assertEquals(413,large.statusCode());
            assertEquals("no-store",large.headers().firstValue("Cache-Control").orElseThrow()); assertEquals(before+2,audit.events.size());
            assertEquals("REQUEST_TOO_LARGE",audit.events.get(audit.events.size()-1).outcome());
            for(var event:audit.events) { assertFalse(event.toString().contains(SECRET)); assertFalse(event.toString().contains(token)); assertFalse(event.toString().contains("resourceId")); }
            System.out.println("AI04_NATIVE_HTTP_OK requests="+requests+" apps=2 jwtRedis=true sqlSnapshot=true draftIsolation=true encodedPath200=true encodedOversize413=true providerRequests=0");
        } finally {
            if(tomcat!=null) { try { tomcat.stop(); } finally { tomcat.destroy(); } }
            if(context!=null) context.close();
            for(String key:ownedKeys) { cache.deleteObject(key); assertNull(cache.getCacheObject(key)); }
            redis.destroy(); resources.shutdown();
            for(long id:ownedScenes) { var current=scenes.get(id); if(current.enabled()) current=scenes.setEnabled(id,new AiPromptSceneService.StatusRequest(false,current.revision())); scenes.delete(id,current.revision()); }
            if(model!=null) jdbc.update("DELETE FROM sys_ai_model_config WHERE model_id=? AND provider_code='ai04-qa' AND model_code=?",model,MODEL);
            assertEquals(0,jdbc.queryForObject("SELECT COUNT(*) FROM sys_ai_prompt_scene WHERE app_key IN (?,?)",Integer.class,A,B));
            assertEquals(0,jdbc.queryForObject("SELECT COUNT(*) FROM sys_ai_model_config WHERE provider_code='ai04-qa' AND model_code=?",Integer.class,MODEL));
            assertEquals(16,jdbc.queryForObject("SELECT COUNT(*) FROM flyway_schema_history WHERE success=1",Integer.class));
            System.out.println("AI04_NATIVE_CLEAN_OK scenes=0 models=0 ownedRedisKeys=0 history=16 sharedServicesRetained=true");
        }
    }
}
