package com.xinghuiTec.ai.prompt;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.xinghuiTec.ai.prompt.AiBusinessRenderException.Code;
import com.xinghuiTec.ai.prompt.AiBusinessVariableResolver.*;
import com.xinghuiTec.ai.prompt.AiPromptSceneRules.VariableDefinition;
import com.xinghuiTec.ai.prompt.AiPromptSceneRules.VariableType;
import com.xinghuiTec.domain.entity.LoginUser;
import com.xinghuiTec.domain.entity.SysUser;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.core.context.SecurityContextHolder;

import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

import static com.xinghuiTec.ai.prompt.AiBusinessRenderException.Code.*;
import static org.junit.jupiter.api.Assertions.*;

class AiBusinessPromptRendererTest {
    static final String SECRET = "SYNTHETIC_PRIVATE_VALUE";
    static final String ALPHA = "neutral_alpha";
    static final String BETA = "neutral_beta";
    record Reference(String resourceId) implements BusinessContext {}
    record OtherContext(String resourceId) implements BusinessContext {}

    /** Two neutral applications live only in test code, never Spring's production component scan. */
    static class NeutralResolver implements AiBusinessVariableResolver<Reference> {
        final String app;
        Set<String> names;
        String authority;
        String resourceTenant = "tenant-a";
        long owner = 7;
        int metadataCalls;
        int permissionCalls;
        int valueCalls;
        boolean throwValues;
        Map<String, String> values;

        NeutralResolver(String app, Set<String> names, Map<String, String> values) {
            this.app = app; this.names = names; this.values = values; this.authority = app + ":read";
        }
        public String appKey() { return app; }
        public Class<Reference> contextType() { return Reference.class; }
        public Set<String> variableNames() { return names; }
        public String requiredAuthority() { return authority; }
        public Resource findResource(Reference context, Actor actor) {
            metadataCalls++;
            if (context.resourceId() == null || context.resourceId().isBlank()) {
                throw new AiBusinessRenderException(CONTEXT_INVALID);
            }
            return "record-1".equals(context.resourceId()) ? new Resource("record-1", resourceTenant) : null;
        }
        public boolean canRead(Reference context, Resource resource, Actor actor) {
            permissionCalls++;
            return actor.userId() == owner;
        }
        public Map<String, String> resolve(Reference context, Resource resource, Actor actor, Set<String> names) {
            valueCalls++;
            if (throwValues) throw new IllegalStateException(SECRET + " resource=" + context.resourceId());
            return values;
        }
    }

    JdbcTemplate jdbc;
    AiPromptSceneRules rules;
    AiPromptSceneService scenes;
    AiBusinessPromptRenderer renderer;
    NeutralResolver alpha;
    NeutralResolver beta;
    long modelId;
    long alphaScene;

    @BeforeEach void setUp() {
        jdbc = new JdbcTemplate(new DriverManagerDataSource(
                "jdbc:h2:mem:ai03_" + UUID.randomUUID() + ";MODE=MySQL;DB_CLOSE_DELAY=-1", "sa", ""));
        jdbc.execute("CREATE TABLE sys_ai_model_config(model_id BIGINT AUTO_INCREMENT PRIMARY KEY,"
                + "provider_code VARCHAR(64),model_code VARCHAR(128),enabled BOOLEAN,revision INT)");
        jdbc.execute("CREATE TABLE sys_ai_prompt_scene(scene_id BIGINT AUTO_INCREMENT PRIMARY KEY,"
                + "app_key VARCHAR(64),scene_key VARCHAR(96),draft_model_id BIGINT,draft_prompt CLOB,"
                + "draft_variables_json CLOB,draft_revision INT DEFAULT 1,published_model_id BIGINT,"
                + "published_prompt CLOB,published_variables_json CLOB,published_revision INT,"
                + "enabled BOOLEAN DEFAULT FALSE,revision INT DEFAULT 0,"
                + "create_time TIMESTAMP DEFAULT CURRENT_TIMESTAMP,update_time TIMESTAMP DEFAULT CURRENT_TIMESTAMP,"
                + "UNIQUE(app_key,scene_key))");
        jdbc.update("INSERT INTO sys_ai_model_config(provider_code,model_code,enabled,revision) VALUES ('qa','neutral',TRUE,0)");
        modelId = jdbc.queryForObject("SELECT model_id FROM sys_ai_model_config", Long.class);
        rules = new AiPromptSceneRules();
        scenes = new AiPromptSceneService(jdbc, new ObjectMapper(), rules);
        alpha = new NeutralResolver(ALPHA, Set.of("title","privateNote"), Map.of("title","Alpha", "privateNote",SECRET));
        beta = new NeutralResolver(BETA, Set.of("score","ready"), Map.of("score","12.5", "ready","true"));
        renderer = new AiBusinessPromptRenderer(scenes, new AiVariableResolverRegistry(List.of(alpha,beta), rules), rules);
        alphaScene = publish(ALPHA, "summary", "{{title}} / {{privateNote}}", List.of(
                definition("title", VariableType.STRING, false), definition("privateNote", VariableType.STRING, true)));
        publish(BETA, "summary", "Score {{score}}, ready {{ready}}", List.of(
                definition("score", VariableType.NUMBER, false), definition("ready", VariableType.BOOLEAN, false)));
        authenticate(7, "tenant-a", "tenant-a", ALPHA+":read", BETA+":read");
    }

    @AfterEach void clearAuthentication() { SecurityContextHolder.clearContext(); }

    static VariableDefinition definition(String name, VariableType type, boolean sensitive) {
        return new VariableDefinition(name,type,"Neutral test declaration",sensitive,
                type == VariableType.NUMBER ? "1" : type == VariableType.BOOLEAN ? "true" : "Sample");
    }

    static void authenticate(long userId, String accountTenant, String sessionTenant, String... authorities) {
        var user = new SysUser(); user.setUserId(userId); user.setTenantId(accountTenant); user.setStatus(1);
        var login = new LoginUser(user, List.of(authorities)); login.setTenantId(sessionTenant);
        SecurityContextHolder.getContext().setAuthentication(UsernamePasswordAuthenticationToken.authenticated(
                login, null, java.util.Arrays.stream(authorities).map(SimpleGrantedAuthority::new).toList()));
    }

    long publish(String app, String key, String prompt, List<VariableDefinition> definitions) {
        long id = scenes.create(new AiPromptSceneService.CreateRequest(app,key,modelId,prompt,definitions));
        var published = scenes.publish(id,new AiPromptSceneService.RevisionRequest(0));
        scenes.setEnabled(id,new AiPromptSceneService.StatusRequest(true,published.revision()));
        return id;
    }

    AiBusinessPromptRenderer.RenderedPrompt alphaResult() { return renderer.render(ALPHA,"summary",new Reference("record-1")); }
    void fails(Code code, Runnable operation) {
        assertEquals(code, assertThrows(AiBusinessRenderException.class, operation::run).code());
    }

    @Test void twoApplicationsRenderTheirOwnTypedPublishedValues() {
        var first = alphaResult();
        assertEquals("Alpha / " + SECRET, first.prompt());
        assertEquals(modelId, first.modelId()); assertEquals(1, first.publishedRevision());
        assertEquals("Score 12.5, ready true", renderer.render(BETA,"summary",new Reference("record-1")).prompt());
        assertEquals(1, alpha.permissionCalls); assertEquals(1, beta.permissionCalls);
        assertEquals(1, alpha.valueCalls); assertEquals(1, beta.valueCalls);
    }

    @Test void missingOrUntrustedAuthenticationCannotReadResources() {
        SecurityContextHolder.clearContext(); fails(UNAUTHENTICATED,this::alphaResult);
        SecurityContextHolder.getContext().setAuthentication(UsernamePasswordAuthenticationToken.authenticated("fake",null,List.of()));
        fails(UNAUTHENTICATED,this::alphaResult); assertEquals(0,alpha.metadataCalls);
    }

    @Test void sessionAccountTenantMismatchAndUnauthenticatedTokenFailClosed() {
        authenticate(7,"tenant-a","tenant-b",ALPHA+":read"); fails(INVALID_IDENTITY,this::alphaResult);
        var auth = SecurityContextHolder.getContext().getAuthentication(); auth.setAuthenticated(false);
        fails(UNAUTHENTICATED,this::alphaResult); assertEquals(0,alpha.metadataCalls);
    }

    @Test void platformConfigurationPermissionDoesNotSubstituteBusinessAccess() {
        authenticate(7,"tenant-a","tenant-a","ai:prompt:query","ai:model:query");
        fails(BUSINESS_PERMISSION_REQUIRED,this::alphaResult); assertEquals(0,alpha.metadataCalls);
    }

    @Test void unknownAppAndWrongContextCannotResolveValues() {
        fails(APP_NOT_REGISTERED,()->renderer.render("unknown","summary",new Reference("record-1")));
        fails(APP_NOT_REGISTERED,()->renderer.render(null,"summary",new Reference("record-1")));
        fails(CONTEXT_INVALID,()->renderer.render(ALPHA,"summary",new OtherContext("record-1")));
        fails(CONTEXT_INVALID,()->renderer.render(ALPHA,"summary",null)); assertEquals(0,alpha.metadataCalls);
    }

    @Test void missingResourceAndIncompleteContextNeverReadValues() {
        fails(RESOURCE_NOT_FOUND,()->renderer.render(ALPHA,"summary",new Reference("missing")));
        fails(CONTEXT_INVALID,()->renderer.render(ALPHA,"summary",new Reference("")));
        assertEquals(0,alpha.permissionCalls); assertEquals(0,alpha.valueCalls);
    }

    @Test void crossTenantOwnershipFailsBeforeApplicationPermissionAndValues() {
        authenticate(7,"tenant-b","tenant-b",ALPHA+":read");
        fails(TENANT_MISMATCH,this::alphaResult); assertEquals(0,alpha.permissionCalls); assertEquals(0,alpha.valueCalls);
    }

    @Test void appSpecificOwnerPermissionIsActuallyExecuted() {
        authenticate(8,"tenant-a","tenant-a",ALPHA+":read");
        fails(RESOURCE_FORBIDDEN,this::alphaResult); assertEquals(1,alpha.permissionCalls); assertEquals(0,alpha.valueCalls);
    }

    @Test void anotherApplicationsVariableCannotSatisfyCurrentScene() {
        publish(ALPHA,"cross","{{score}}",List.of(definition("score",VariableType.NUMBER,false)));
        fails(VARIABLE_NOT_REGISTERED,()->renderer.render(ALPHA,"cross",new Reference("record-1")));
        assertEquals(0,alpha.metadataCalls); assertEquals(0,beta.valueCalls);
    }

    @Test void missingNullBlankAndExtraValuesNeverFallBackToSamples() {
        alpha.values=Map.of("title","Alpha"); fails(MISSING_VARIABLE,this::alphaResult);
        alpha.values=new HashMap<>(); alpha.values.put("title","Alpha"); alpha.values.put("privateNote",null);
        fails(MISSING_VARIABLE,this::alphaResult);
        alpha.values=Map.of("title","Alpha","privateNote"," "); fails(MISSING_VARIABLE,this::alphaResult);
        alpha.values=Map.of("title","Alpha","privateNote",SECRET,"other","extra"); fails(UNDECLARED_VALUE,this::alphaResult);
        alpha.values=null; fails(MISSING_VARIABLE,this::alphaResult);
    }

    @Test void numberBooleanAndPerValueLengthUseStrictPublishedTypes() {
        beta.values=Map.of("score","NaN","ready","true");
        fails(INVALID_VARIABLE_VALUE,()->renderer.render(BETA,"summary",new Reference("record-1")));
        beta.values=Map.of("score","2","ready","yes");
        fails(INVALID_VARIABLE_VALUE,()->renderer.render(BETA,"summary",new Reference("record-1")));
        alpha.values=Map.of("title","x".repeat(2049),"privateNote",SECRET); fails(INVALID_VARIABLE_VALUE,this::alphaResult);
    }

    @Test void totalOutputLengthIsBoundedBeforeBuildingAnOversizedPrompt() {
        publish(ALPHA,"large","{{title}}".repeat(7),List.of(definition("title",VariableType.STRING,false)));
        alpha.values=Map.of("title","x".repeat(2048));
        fails(OUTPUT_TOO_LONG,()->renderer.render(ALPHA,"large",new Reference("record-1")));
        alpha.values=Map.of("title","x".repeat(1714));
        assertEquals(11998, renderer.render(ALPHA,"large",new Reference("record-1")).prompt().length());
    }

    @Test void variableTextIsInsertedLiterallyAndOnlyOnce() {
        alpha.values=Map.of("title","{{privateNote}} $1 \\ literal","privateNote",SECRET);
        assertEquals("{{privateNote}} $1 \\ literal / "+SECRET,alphaResult().prompt());
    }

    @Test void draftChangesDoNotAlterRuntimePublishedSnapshot() {
        var current = scenes.get(alphaScene);
        scenes.updateDraft(alphaScene,new AiPromptSceneService.DraftRequest(modelId,"DRAFT {{title}}",
                List.of(definition("title",VariableType.STRING,false)),current.revision()));
        assertEquals("Alpha / "+SECRET,alphaResult().prompt());
        assertEquals(1,alphaResult().publishedRevision());
    }

    @Test void unpublishedDisabledMissingSceneAndDisabledModelDoNotReadBusinessValues() {
        scenes.create(new AiPromptSceneService.CreateRequest(ALPHA,"unpublished",modelId,"{{title}}",
                List.of(definition("title",VariableType.STRING,false))));
        fails(SCENE_UNAVAILABLE,()->renderer.render(ALPHA,"unpublished",new Reference("record-1")));
        fails(SCENE_UNAVAILABLE,()->renderer.render(ALPHA,"missing",new Reference("record-1")));
        var current=scenes.get(alphaScene);
        scenes.setEnabled(alphaScene,new AiPromptSceneService.StatusRequest(false,current.revision()));
        fails(SCENE_UNAVAILABLE,this::alphaResult);
        current=scenes.get(alphaScene);
        scenes.setEnabled(alphaScene,new AiPromptSceneService.StatusRequest(true,current.revision()));
        jdbc.update("UPDATE sys_ai_model_config SET enabled=FALSE WHERE model_id=?",modelId);
        fails(SCENE_UNAVAILABLE,this::alphaResult); assertEquals(0,alpha.metadataCalls); assertEquals(0,alpha.valueCalls);
    }

    @Test void sensitiveRuntimeIsControlledAndPreviewAndDiagnosticsRemainRedacted() {
        var result=alphaResult(); assertTrue(result.prompt().contains(SECRET)); assertFalse(result.toString().contains(SECRET));
        var preview=scenes.preview(alphaScene,new AiPromptSceneService.PreviewRequest(scenes.get(alphaScene).revision(),Map.of("privateNote",SECRET)));
        assertFalse(preview.prompt().contains(SECRET)); assertTrue(preview.prompt().contains("[敏感变量已隐藏]"));
        alpha.throwValues=true;
        var failure=assertThrows(AiBusinessRenderException.class,this::alphaResult);
        assertEquals(RESOLVER_FAILED,failure.code()); assertNull(failure.getCause());
        assertFalse(failure.toString().contains(SECRET)); assertFalse(failure.toString().contains("record-1"));
        var logger=(ch.qos.logback.classic.Logger)org.slf4j.LoggerFactory.getLogger(AiBusinessPromptRenderer.class);
        var appender=new ch.qos.logback.core.read.ListAppender<ch.qos.logback.classic.spi.ILoggingEvent>();
        appender.start(); logger.addAppender(appender);
        try {
            logger.info("Internal result {}",result);
            logger.warn("Internal failure {}",failure.toString());
            assertEquals(2,appender.list.size());
            assertTrue(appender.list.stream().noneMatch(event->event.getFormattedMessage().contains(SECRET)));
            assertTrue(appender.list.stream().noneMatch(event->event.getFormattedMessage().contains(result.prompt())));
        } finally { logger.detachAppender(appender); appender.stop(); }
    }

    @Test void registrationIsUniqueAndDoesNotAcceptPlatformManagementAuthority() {
        fails(DUPLICATE_REGISTRATION,()->new AiVariableResolverRegistry(List.of(alpha,alpha),rules));
        alpha.authority="ai:prompt:query"; fails(INVALID_REGISTRATION,()->new AiVariableResolverRegistry(List.of(alpha),rules));
        alpha.authority=ALPHA+":read"; alpha.names=Set.of("invalid.name");
        fails(INVALID_REGISTRATION,()->new AiVariableResolverRegistry(List.of(alpha),rules));
    }

    @Test void registeredMetadataIsCapturedAndCannotBeChangedAfterRegistration() {
        alpha.names=Set.of("changed"); alpha.authority="ai:prompt:query";
        assertEquals("Alpha / "+SECRET,alphaResult().prompt());
    }

    @Test void disabledOrIncompleteServerIdentityCannotReachBusinessMetadata() {
        var login=(LoginUser)SecurityContextHolder.getContext().getAuthentication().getPrincipal();
        login.getUser().setStatus(0); fails(INVALID_IDENTITY,this::alphaResult);
        login.getUser().setStatus(1); login.getUser().setUserId(null); fails(INVALID_IDENTITY,this::alphaResult);
        login.getUser().setUserId(7L); login.setTenantId(null); fails(INVALID_IDENTITY,this::alphaResult);
        assertEquals(0,alpha.metadataCalls);
    }

    @Test void springWiringSupportsCodeRegisteredApplicationsAndAnEmptyRegistry() {
        for(boolean registered:List.of(true,false)) {
            try(var context=new org.springframework.context.annotation.AnnotationConfigApplicationContext()) {
                context.registerBean(AiPromptSceneRules.class,()->rules);
                context.registerBean(AiPromptSceneService.class,()->scenes);
                if(registered) {
                    context.registerBean("alphaResolver",NeutralResolver.class,()->alpha);
                    context.registerBean("betaResolver",NeutralResolver.class,()->beta);
                }
                context.register(AiVariableResolverRegistry.class,AiBusinessPromptRenderer.class); context.refresh();
                var internal=context.getBean(AiBusinessPromptRenderer.class);
                if(registered) assertEquals("Alpha / "+SECRET,internal.render(ALPHA,"summary",new Reference("record-1")).prompt());
                else fails(APP_NOT_REGISTERED,()->internal.render(ALPHA,"summary",new Reference("record-1")));
            }
        }
    }
}
