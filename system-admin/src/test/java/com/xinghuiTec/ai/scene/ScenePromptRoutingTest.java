package com.xinghuiTec.ai.scene;

import com.xinghuiTec.ai.application.*;
import com.xinghuiTec.domain.entity.LoginUser;
import com.xinghuiTec.domain.entity.SysUser;
import com.xinghuiTec.security.PlatformAccessService;
import org.junit.jupiter.api.*;
import org.springframework.context.annotation.*;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.config.annotation.method.configuration.EnableMethodSecurity;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;

import java.util.*;

import static org.junit.jupiter.api.Assertions.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

class ScenePromptRoutingTest {
    private SceneTestSupport f;
    @BeforeEach void setup() throws Exception {f=new SceneTestSupport();}
    @AfterEach void close(){SecurityContextHolder.clearContext();f.close();}

    private SceneDtos.ModelPolicy fixedRoute() {
        return new SceneDtos.ModelPolicy(SceneDtos.ModelMode.FIXED,List.of(new SceneDtos.ModelBinding(new SceneDtos.ModelRef("17","8"),SceneDtos.KeyMode.FIXED,
                List.of(new SceneDtos.KeyRef("19","3"),new SceneDtos.KeyRef("20","4")),new SceneDtos.KeyRef("19","3"),List.of(new SceneDtos.KeyRef("20","4")))),false,SceneDtos.OutputFormat.TEXT,10,2,2,100);
    }
    private SceneDtos.SaveResult restricted() {
        var created=f.create(false);var d=f.definition("Initial {{user_request}}",false);
        return f.management.save(created.scene().sceneKey(),new SceneDtos.Save(created.scene().revision(),UUID.randomUUID().toString(),new SceneDtos.Definition(d.name(),d.purpose(),d.promptText(),"",d.dataObjects(),fixedRoute())),f.actor);
    }
    private SceneDtos.Definition body(SceneDtos.Definition d,String text) {
        return new SceneDtos.Definition(d.name(),d.purpose(),text,"",d.dataObjects(),null,d.dataBindings());
    }
    @Test void newSceneUsesSingleInitialModelAndBoundedServerDefaults() {
        var scene=f.create(false);var definition=new SceneDtos.Definition("Scene","","Hello {{user_request}}","",List.of(),null,List.of(),new SceneDtos.ModelRef("17","8"));
        var saved=f.management.save(scene.scene().sceneKey(),new SceneDtos.Save(0,UUID.randomUUID().toString(),definition),f.actor);
        var route=saved.detail().definition().modelPolicy();
        assertEquals(SceneDtos.KeyMode.AUTO,route.models().get(0).keyMode());assertEquals(1,route.models().size());assertFalse(route.fallback());
        assertEquals(120,route.timeoutSeconds());assertEquals(1024,route.maxTokens());assertEquals(8,route.maxAttempts());assertNull(saved.detail().definition().initialModel());
        assertEquals(0,f.provider.calls.get());
    }
    @Test void omittedRouteOnPromptSavePreservesFixedKeysAndCallerPolicy() {
        var initial=restricted();String key=initial.detail().scene().sceneKey();var original=f.execution.policy(f.scenes.version(initial.detail().scene().currentVersionId()).appVersion());
        var saved=f.management.save(key,new SceneDtos.Save(initial.detail().scene().revision(),UUID.randomUUID().toString(),body(initial.detail().definition(),"Revised {{user_request}}")),f.actor);
        assertEquals(original,f.execution.policy(f.scenes.version(saved.detail().scene().currentVersionId()).appVersion()));
        assertEquals(SceneDtos.KeyMode.FIXED,saved.detail().definition().modelPolicy().models().get(0).keyMode());assertEquals("Revised {{user_request}}",saved.detail().definition().promptText());
    }
    @Test void oldEqualRouteIsCompatibleButPromptCannotWidenItToAuto() {
        var initial=restricted();String key=initial.detail().scene().sceneKey();
        var same=f.management.save(key,new SceneDtos.Save(initial.detail().scene().revision(),UUID.randomUUID().toString(),initial.detail().definition()),f.actor);
        var wide=f.definition("Changed {{user_request}}",false);
        assertEquals("ROUTING_MANAGEMENT_REQUIRED",assertThrows(SceneFailure.class,()->f.management.save(key,new SceneDtos.Save(same.detail().scene().revision(),UUID.randomUUID().toString(),wide),f.actor)).getMessage());
        assertEquals(SceneDtos.KeyMode.FIXED,f.management.routing(key).modelPolicy().models().get(0).keyMode());
    }
    @Test void dedicatedRouteWritePreservesPromptCompiledBytesAndScopesAndReplays() {
        var initial=restricted();String key=initial.detail().scene().sceneKey();String oldVersion=initial.detail().scene().currentVersionId();
        String compiled=f.jdbc.queryForObject("SELECT compiled_json FROM sys_ai_scene_version WHERE scene_version_id=?",String.class,oldVersion);
        var before=f.execution.policy(f.scenes.version(oldVersion).appVersion());
        var desired=f.definition("unused",false).modelPolicy();var change=new SceneRoutingDtos.Change(initial.detail().scene().revision(),UUID.randomUUID().toString(),desired);
        var result=f.management.routing(key,change,f.actor);assertEquals(result,f.management.routing(key,change,f.actor));
        assertEquals(compiled,f.jdbc.queryForObject("SELECT compiled_json FROM sys_ai_scene_version WHERE scene_version_id=?",String.class,result.routing().currentVersionId()));
        var after=f.execution.policy(f.scenes.version(result.routing().currentVersionId()).appVersion());assertEquals(before.allowedSubjects(),after.allowedSubjects());assertEquals(before.allowedAuthorities(),after.allowedAuthorities());assertEquals(before.scope(),after.scope());
        assertEquals(initial.detail().definition().promptText(),f.management.detail(key,f.actor).definition().promptText());assertEquals(desired,result.routing().modelPolicy());
        assertEquals("IDEMPOTENCY_CONFLICT",assertThrows(SceneFailure.class,()->f.management.routing(key,new SceneRoutingDtos.Change(change.expectedRevision(),change.clientMutationId(),fixedRoute()),f.actor)).getMessage());
        assertEquals(2,f.jdbc.queryForObject("SELECT COUNT(*) FROM sys_ai_scene_version",Integer.class));assertEquals(0,f.provider.calls.get());
    }
    @Test void routeAndBodyUseSameCasAndDoNotOverwriteEachOther() {
        var initial=restricted();String key=initial.detail().scene().sceneKey();
        var prompt=f.management.save(key,new SceneDtos.Save(initial.detail().scene().revision(),UUID.randomUUID().toString(),body(initial.detail().definition(),"Latest {{user_request}}")),f.actor);
        assertEquals("VERSION_CONFLICT",assertThrows(SceneFailure.class,()->f.management.routing(key,new SceneRoutingDtos.Change(initial.detail().scene().revision(),UUID.randomUUID().toString(),f.definition("unused",false).modelPolicy()),f.actor)).getMessage());
        var route=f.management.routing(key,new SceneRoutingDtos.Change(prompt.detail().scene().revision(),UUID.randomUUID().toString(),f.definition("unused",false).modelPolicy()),f.actor);
        assertEquals("VERSION_CONFLICT",assertThrows(SceneFailure.class,()->f.management.save(key,new SceneDtos.Save(prompt.detail().scene().revision(),UUID.randomUUID().toString(),body(prompt.detail().definition(),"Stale body")),f.actor)).getMessage());
        assertEquals("Latest {{user_request}}",f.management.detail(key,f.actor).definition().promptText());assertEquals(route.routing().revision(),f.management.routing(key).revision());
    }
    @Configuration @EnableMethodSecurity static class Security {}
    private void login(String tenant,String... rights) {
        var user=new SysUser();user.setUserId(66L);user.setTenantId(tenant);var login=new LoginUser(user,List.of(rights));login.setTenantId(tenant);
        SecurityContextHolder.getContext().setAuthentication(new UsernamePasswordAuthenticationToken(login,null,login.getAuthorities()));
    }
    @Test void actualRoutingHttpRequiresBothActionsAndRejectsActorForgery() throws Exception {
        var initial=restricted();String key=initial.detail().scene().sceneKey();String url="/system/ai/settings/scene-routes/"+key;
        try(var context=new AnnotationConfigApplicationContext()) {
            context.register(Security.class);context.registerBean("platformAccess",PlatformAccessService.class,()->new PlatformAccessService(true));
            context.registerBean(SceneRoutingController.class,()->new SceneRoutingController(f.management,new AiInvocationIdentity(),new AiRuntimeBodyCodec()));context.refresh();
            var mvc=MockMvcBuilders.standaloneSetup(context.getBean(SceneRoutingController.class)).setControllerAdvice(new SceneHttpErrors()).addFilters(new AiRuntimeBoundaryFilter(f.mapper)).build();
            login("000000","ai:config:query");mvc.perform(get(url)).andExpect(status().isForbidden());
            login("000000","ai:config:query","ai:scene:query");mvc.perform(get(url)).andExpect(status().isOk()).andExpect(jsonPath("$.data.modelPolicy.models[0].keyMode").value("FIXED"));
            var change=new SceneRoutingDtos.Change(initial.detail().scene().revision(),UUID.randomUUID().toString(),f.definition("unused",false).modelPolicy());String json=f.mapper.writeValueAsString(change);
            login("000000","ai:scene:edit");mvc.perform(put(url).contentType("application/json").content(json)).andExpect(status().isForbidden());
            login("000001","ai:scene:edit","ai:config:edit");mvc.perform(put(url).contentType("application/json").content(json)).andExpect(status().isForbidden());
            login("000000","ai:scene:edit","ai:config:edit");mvc.perform(put(url).contentType("application/json").content(json.substring(0,json.length()-1)+",\"actor\":\"66\"}")).andExpect(status().isBadRequest());
            mvc.perform(put(url).contentType("application/json").content(json)).andExpect(status().isOk()).andExpect(jsonPath("$.data.routing.modelPolicy.models[0].keyMode").value("AUTO"));
        }
    }
}
