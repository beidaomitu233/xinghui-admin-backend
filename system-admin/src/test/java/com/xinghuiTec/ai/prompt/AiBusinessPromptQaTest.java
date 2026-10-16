package com.xinghuiTec.ai.prompt;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.xinghuiTec.ai.prompt.AiBusinessPromptRendererTest.NeutralResolver;
import com.xinghuiTec.ai.prompt.AiBusinessPromptRendererTest.Reference;
import com.xinghuiTec.ai.prompt.AiPromptSceneRules.VariableType;
import org.flywaydb.core.Flyway;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import org.springframework.security.core.context.SecurityContextHolder;

import java.util.List;
import java.util.Map;
import java.util.Set;

import static com.xinghuiTec.ai.prompt.AiBusinessPromptRendererTest.*;
import static org.junit.jupiter.api.Assertions.*;

/** Explicit opt-in native QA. Test-only neutral resolvers; no HTTP, Redis, storage or model providers. */
@EnabledIfEnvironmentVariable(named="AI03_QA_ENABLED", matches="true")
class AiBusinessPromptQaTest {
    private static final String SCHEMA="xinghui_ai_qa_20261011";
    private static final String APP_A="ai03_fixture_20261011_alpha";
    private static final String APP_B="ai03_fixture_20261011_beta";
    private static final String MODEL_CODE="ai03fixture20261011";

    @Test void approvedNativeSnapshotRendersTwoAppsAndCleansItsExactFixtures() {
        String password=System.getenv("AI03_QA_PASSWORD");
        assertNotNull(password,"QA password must be supplied in process environment");
        var source=new DriverManagerDataSource("jdbc:mysql://127.0.0.1:13317/"+SCHEMA
                +"?useUnicode=true&characterEncoding=utf8&useSSL=false&serverTimezone=Asia/Shanghai","qa_ai",password);
        var jdbc=new JdbcTemplate(source);
        var identity=jdbc.queryForMap("SELECT DATABASE() AS db,CURRENT_USER() AS account,@@port AS port,"
                +"@@datadir AS dir,@@version AS version");
        assertEquals(SCHEMA,identity.get("db"));
        assertEquals("qa_ai@127.0.0.1",identity.get("account"));
        assertEquals(13317,((Number)identity.get("port")).intValue());
        assertEquals("8.0.12",identity.get("version"));
        assertEquals("d:/changhuiengineer/devproject/xinghui-admin2/.qa-local/20261011/mysql-data/",
                identity.get("dir").toString().replace('\\','/').toLowerCase(java.util.Locale.ROOT));
        assertEquals(0,jdbc.queryForObject("SELECT COUNT(*) FROM information_schema.tables WHERE table_schema=?",Integer.class,SCHEMA));
        assertEquals(0,jdbc.queryForObject("SELECT COUNT(*) FROM information_schema.tables WHERE table_schema=? AND table_name='flyway_schema_history'",Integer.class,SCHEMA));
        System.out.println("AI03_QA_PREFLIGHT_OK schema="+SCHEMA+" account=qa_ai@127.0.0.1 port=13317 version=8.0.12 tables=0");
        var flyway=Flyway.configure().dataSource(source).locations("classpath:db/migration","classpath:db/optional-migration")
                .baselineOnMigrate(false).outOfOrder(false).validateOnMigrate(true).cleanDisabled(true).load();
        flyway.migrate(); flyway.validate();
        assertEquals(2,jdbc.queryForObject("SELECT COUNT(*) FROM flyway_schema_history WHERE version IN ('20260926.90','20260926.100') AND success=1",Integer.class));
        assertEquals(0,jdbc.queryForObject("SELECT COUNT(*) FROM flyway_schema_history WHERE success=0",Integer.class));
        assertEquals(0,jdbc.queryForObject("SELECT COUNT(*) FROM sys_ai_prompt_scene WHERE app_key IN (?,?)",Integer.class,APP_A,APP_B));
        assertEquals(0,jdbc.queryForObject("SELECT COUNT(*) FROM sys_ai_model_config WHERE provider_code='ai03-qa' AND model_code=?",Integer.class,MODEL_CODE));
        var rules=new AiPromptSceneRules();
        var scenes=new AiPromptSceneService(jdbc,new ObjectMapper(),rules);
        Long modelId=null;
        try {
            jdbc.update("INSERT INTO sys_ai_model_config(provider_code,model_code,endpoint_url,timeout_seconds,enabled,revision) VALUES ('ai03-qa',?,'https://example.invalid/ai03-fixture',30,1,0)",MODEL_CODE);
            modelId=jdbc.queryForObject("SELECT model_id FROM sys_ai_model_config WHERE provider_code='ai03-qa' AND model_code=?",Long.class,MODEL_CODE);
            var alpha=new NeutralResolver(APP_A,Set.of("title","privateNote"),Map.of("title","Native Alpha","privateNote",SECRET));
            var beta=new NeutralResolver(APP_B,Set.of("score","ready"),Map.of("score","8.5","ready","false"));
            var renderer=new AiBusinessPromptRenderer(scenes,new AiVariableResolverRegistry(List.of(alpha,beta),rules),rules);
            long first=scenes.create(new AiPromptSceneService.CreateRequest(APP_A,"summary",modelId,"{{title}} / {{privateNote}}",List.of(
                    definition("title",VariableType.STRING,false),definition("privateNote",VariableType.STRING,true))));
            long second=scenes.create(new AiPromptSceneService.CreateRequest(APP_B,"summary",modelId,"{{score}} / {{ready}}",List.of(
                    definition("score",VariableType.NUMBER,false),definition("ready",VariableType.BOOLEAN,false))));
            for (long id:List.of(first,second)) {
                var published=scenes.publish(id,new AiPromptSceneService.RevisionRequest(0));
                scenes.setEnabled(id,new AiPromptSceneService.StatusRequest(true,published.revision()));
            }
            authenticate(7,"tenant-a","tenant-a",APP_A+":read",APP_B+":read");
            var firstResult=renderer.render(APP_A,"summary",new Reference("record-1"));
            assertEquals("Native Alpha / "+SECRET,firstResult.prompt());
            assertEquals("8.5 / false",renderer.render(APP_B,"summary",new Reference("record-1")).prompt());
            var current=scenes.get(first);
            scenes.updateDraft(first,new AiPromptSceneService.DraftRequest(modelId,"DRAFT {{title}}",List.of(definition("title",VariableType.STRING,false)),current.revision()));
            assertEquals(firstResult.prompt(),renderer.render(APP_A,"summary",new Reference("record-1")).prompt());
            assertEquals(firstResult.publishedRevision(),renderer.render(APP_A,"summary",new Reference("record-1")).publishedRevision());
            authenticate(7,"tenant-b","tenant-b",APP_A+":read");
            var denied=assertThrows(AiBusinessRenderException.class,()->renderer.render(APP_A,"summary",new Reference("record-1")));
            assertEquals(AiBusinessRenderException.Code.TENANT_MISMATCH,denied.code());
            System.out.println("AI03_QA_RENDER_OK modelId="+modelId+" sceneIds="+first+","+second+" snapshotIsolation=true crossTenantDenied=true");
        } finally {
            SecurityContextHolder.clearContext();
            for (Long id:jdbc.queryForList("SELECT scene_id FROM sys_ai_prompt_scene WHERE app_key IN (?,?) AND scene_key='summary'",Long.class,APP_A,APP_B)) {
                var current=scenes.get(id);
                if(current.enabled()) current=scenes.setEnabled(id,new AiPromptSceneService.StatusRequest(false,current.revision()));
                scenes.delete(id,current.revision());
            }
            if(modelId!=null) jdbc.update("DELETE FROM sys_ai_model_config WHERE model_id=? AND provider_code='ai03-qa' AND model_code=?",modelId,MODEL_CODE);
            else jdbc.update("DELETE FROM sys_ai_model_config WHERE provider_code='ai03-qa' AND model_code=?",MODEL_CODE);
            assertEquals(0,jdbc.queryForObject("SELECT COUNT(*) FROM sys_ai_prompt_scene WHERE app_key IN (?,?)",Integer.class,APP_A,APP_B));
            assertEquals(0,jdbc.queryForObject("SELECT COUNT(*) FROM sys_ai_model_config WHERE provider_code='ai03-qa' AND model_code=?",Integer.class,MODEL_CODE));
            System.out.println("AI03_QA_CLEAN_OK scenes=0 models=0 noPersistentBusinessFixtures=true");
        }
    }
}
