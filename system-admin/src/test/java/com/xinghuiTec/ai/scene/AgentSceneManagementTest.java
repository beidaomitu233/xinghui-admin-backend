package com.xinghuiTec.ai.scene;

import com.xinghuiTec.ai.contract.AiTypes.TrustedActor;
import org.junit.jupiter.api.*;
import java.util.*;
import static org.junit.jupiter.api.Assertions.*;

class AgentSceneManagementTest {
    SceneTestSupport fixture;
    final TrustedActor agent=new TrustedActor("101","tenant_a",Set.of("ai:agent:invoke"),null);
    @BeforeEach void setup()throws Exception{fixture=new SceneTestSupport();}
    @AfterEach void close(){fixture.close();}
    SceneDtos.Detail configured(String prompt) {
        var created=fixture.management.create(new SceneDtos.Create("业务助手","管理当前账号可访问的业务",SceneDtos.Kind.AGENT,null,UUID.randomUUID().toString()),fixture.actor);
        return fixture.save(created,prompt,false).detail();
    }
    @Test void agentPromptUsesExistingVersionedManagementAndNeverCallsAModelOnSave() {
        var saved=configured("自主规划任务，通过可用工具读取并管理业务，报告实际执行结果。");
        var route=fixture.management.agentRoute(saved.scene().sceneKey(),agent);
        assertTrue(route.policy().requirements().tools());assertEquals(Set.of("ai:agent:invoke"),route.policy().allowedAuthorities());
        assertEquals(saved.definition().promptText(),route.systemPrompt());assertNotNull(route.appVersion());
        assertEquals(0,fixture.provider.calls.get());assertEquals(1,fixture.jdbc.queryForObject("SELECT COUNT(*) FROM sys_ai_scene",Integer.class));
        var entries=fixture.management.agentChoices(agent);assertEquals(1,entries.size());assertFalse(entries.get(0).canStartFromMonitor());
        assertFalse(fixture.mapper.valueToTree(entries).toString().contains("自主规划"));assertFalse(route.toString().contains("自主规划"));
    }
    @Test void ordinarySceneAndConversationEndpointsCannotExecuteAgentPresets() {
        var saved=configured("业务系统助手");
        assertTrue(fixture.service.candidates(null,null,fixture.actor).isEmpty());
        assertEquals("USE_AGENT_WORKBENCH",assertThrows(SceneFailure.class,()->fixture.service.submit(saved.scene().sceneKey(),null,fixture.actor)).getMessage());
        assertEquals(403,assertThrows(SceneFailure.class,()->fixture.management.agentRoute(saved.scene().sceneKey(),fixture.actor)).status());
        assertEquals(0,fixture.provider.calls.get());
    }
    @Test void currentDisableAndPermissionAreRecheckedBeforeLoadingHiddenAgentPrompt() {
        var saved=configured("业务系统助手");
        fixture.management.enabled(saved.scene().sceneKey(),new SceneDtos.Enabled(saved.scene().revision(),UUID.randomUUID().toString(),false),fixture.actor);
        assertEquals("AGENT_PROMPT_UNAVAILABLE",assertThrows(SceneFailure.class,()->fixture.management.agentRoute(saved.scene().sceneKey(),agent)).getMessage());
        assertTrue(fixture.management.agentChoices(agent).isEmpty());
        assertEquals(403,assertThrows(SceneFailure.class,()->fixture.management.agentChoices(fixture.actor)).status());
    }
    @Test void agentUserRequestVariableUsesTheActualNaturalLanguageTask() {
        var created=fixture.management.create(new SceneDtos.Create("业务助手","",SceneDtos.Kind.AGENT,null,UUID.randomUUID().toString()),fixture.actor);
        var saved=fixture.save(created,"请执行 {{user_request:用户要求}}",false).detail();
        var context=fixture.management.agentContext(saved.scene().sceneKey(),agent,"帮我整理商品");
        assertTrue(context.stream().anyMatch(text->text.contains("帮我整理商品")));
        assertTrue(context.stream().noneMatch(text->text.contains("{{user_request")));
        assertEquals(0,fixture.provider.calls.get());
        var general=fixture.save(fixture.create(false),"普通对话",false).detail();
        assertEquals("AGENT_PROMPT_UNAVAILABLE",assertThrows(SceneFailure.class,()->fixture.management.agentRoute(general.scene().sceneKey(),agent)).getMessage());
    }
}
