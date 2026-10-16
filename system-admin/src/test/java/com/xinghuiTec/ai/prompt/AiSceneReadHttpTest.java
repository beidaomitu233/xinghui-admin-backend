package com.xinghuiTec.ai.prompt;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.xinghuiTec.ai.prompt.AiBusinessPromptRendererTest.*;
import com.xinghuiTec.config.SecurityConfig;
import com.xinghuiTec.controller.AiSceneReadController;
import com.xinghuiTec.domain.entity.LoginUser;
import com.xinghuiTec.domain.entity.SysUser;
import com.xinghuiTec.filter.AuthenticationEntryPointImpl;
import com.xinghuiTec.filter.LoginFilter;
import com.xinghuiTec.utils.JwtUtil;
import com.xinghuiTec.utils.RedisCacheUtils;
import jakarta.servlet.ServletContext;
import org.junit.jupiter.api.*;
import org.springframework.context.annotation.AnnotatedBeanDefinitionReader;
import org.springframework.context.annotation.Configuration;
import org.springframework.mock.web.MockServletContext;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import org.springframework.web.context.support.GenericWebApplicationContext;
import org.springframework.web.servlet.config.annotation.EnableWebMvc;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Set;

import static com.xinghuiTec.ai.prompt.AiBusinessPromptRendererTest.*;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;
import static org.springframework.security.test.web.servlet.setup.SecurityMockMvcConfigurers.springSecurity;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;

class AiSceneReadHttpTest {
    @Configuration @EnableWebMvc static class WebConfig {}
    static class Converter implements AiSceneContextConverter<Reference> {
        final String app; Set<String> allowed=Set.of("resourceId");
        Converter(String app) { this.app=app; }
        public String appKey() { return app; }
        public Class<Reference> contextType() { return Reference.class; }
        public Set<String> allowedFields() { return allowed; }
        public Set<String> requiredFields() { return Set.of("resourceId"); }
        public Reference convert(Map<String,String> fields) {
            String id=fields.get("resourceId");
            if(!id.matches("[A-Za-z0-9-]{1,64}")) throw new IllegalArgumentException("Never forward conversion input");
            return new Reference(id);
        }
    }
    static class Audit extends AiSceneReadAudit {
        final List<Event> events=new ArrayList<>();
        @Override public synchronized void record(Event event) { events.add(event); super.record(event); }
    }
    static GenericWebApplicationContext context(ServletContext servlet,AiPromptSceneRules rules,AiPromptSceneService scenes,
            NeutralResolver alpha,NeutralResolver beta,RedisCacheUtils cache,Audit audit) {
        var context=new GenericWebApplicationContext(); context.setServletContext(servlet);
        var registry=new AiVariableResolverRegistry(List.of(alpha,beta),rules);
        var renderer=new AiBusinessPromptRenderer(scenes,registry,rules);
        context.registerBean(AiPromptSceneRules.class,()->rules); context.registerBean(AiPromptSceneService.class,()->scenes);
        context.registerBean(AiVariableResolverRegistry.class,()->registry); context.registerBean(AiBusinessPromptRenderer.class,()->renderer);
        context.getBeanFactory().registerSingleton("redisCacheUtils",cache); context.registerBean(AiSceneReadAudit.class,()->audit);
        context.registerBean("alphaConverter",Converter.class,()->new Converter(alpha.app));
        context.registerBean("betaConverter",Converter.class,()->new Converter(beta.app));
        new AnnotatedBeanDefinitionReader(context).register(WebConfig.class,SecurityConfig.class,LoginFilter.class,
                AuthenticationEntryPointImpl.class,AiSceneReadService.class,AiSceneReadController.class,AiSceneReadBoundaryFilter.class);
        context.refresh(); return context;
    }
    static LoginUser session(long id,String tenant,String token,String... permissions) {
        var user=new SysUser(); user.setUserId(id); user.setStatus(1); user.setTenantId(tenant);
        var session=new LoginUser(user,List.of(permissions)); session.setTenantId(tenant); session.setToken(token); return session;
    }
    AiBusinessPromptRendererTest fixture;
    RedisCacheUtils cache;
    Audit audit;
    GenericWebApplicationContext context;
    MockMvc http;
    String token;
    final ObjectMapper json=new ObjectMapper();
    final String body="{\"context\":{\"resourceId\":\"record-1\"}}";

    @BeforeEach void setUp() {
        fixture=new AiBusinessPromptRendererTest(); fixture.setUp(); SecurityContextHolder.clearContext();
        cache=mock(RedisCacheUtils.class); audit=new Audit(); token=JwtUtil.createJWT("7","tenant-a");
        when(cache.getCacheObject("admin:login:7")).thenReturn(session(7,"tenant-a",token,ALPHA+":read",BETA+":read"));
        context=context(new MockServletContext(),fixture.rules,fixture.scenes,fixture.alpha,fixture.beta,cache,audit);
        http=MockMvcBuilders.webAppContextSetup(context).addFilters(context.getBean(AiSceneReadBoundaryFilter.class)).apply(springSecurity()).build();
    }
    @AfterEach void tearDown() { if(context!=null) context.close(); SecurityContextHolder.clearContext(); }
    MvcResult request(String app,String key,String payload,String authorization) throws Exception {
        var request=post("/ai/scenes/"+app+"/"+key+"/render").contentType("application/json").content(payload);
        if(authorization!=null) request.header("Authorization",authorization);
        return http.perform(request).andReturn();
    }
    void failure(MvcResult result,int status,String code) throws Exception {
        assertEquals(status,result.getResponse().getStatus());
        var response=json.readTree(result.getResponse().getContentAsString()); assertTrue(response.get("data")==null || response.get("data").isNull());
        if(code!=null) assertEquals("AI_SCENE_READ:"+code,response.get("message").asText());
        assertFalse(result.getResponse().getContentAsString().contains(SECRET));
        assertTrue(result.getResponse().getHeader("Cache-Control").contains("no-store"));
    }

    @Test void realJwtFilterReturnsOnlyAuthorizedPublishedFieldsForTwoApplications() throws Exception {
        var first=request(ALPHA,"summary",body,token); assertEquals(200,first.getResponse().getStatus());
        var data=json.readTree(first.getResponse().getContentAsString()).get("data");
        assertEquals(5,data.size()); assertEquals("Alpha / "+SECRET,data.get("prompt").asText());
        assertEquals("1",data.get("modelId").asText()); assertEquals(1,data.get("publishedRevision").asInt());
        assertFalse(data.has("endpointUrl")); assertFalse(data.has("credentialEnvName"));
        assertEquals("Score 12.5, ready true",json.readTree(request(BETA,"summary",body,token).getResponse().getContentAsString()).get("data").get("prompt").asText());
        assertEquals("no-store",first.getResponse().getHeader("Cache-Control"));
        assertNotNull(first.getResponse().getHeader("X-Request-ID")); assertEquals(2,audit.events.size());
        assertEquals(7L,audit.events.get(0).userId()); assertEquals("SUCCESS",audit.events.get(0).outcome());
    }
    @Test void missingInvalidAndStaleJwtAreDeniedBeforeBusinessReads() throws Exception {
        failure(request(ALPHA,"summary",body,null),401,null);
        failure(request(ALPHA,"summary",body,"not-a-token"),401,null);
        failure(request(ALPHA,"summary",body,JwtUtil.createJWT("7","tenant-a")),401,null);
        assertEquals(0,fixture.alpha.metadataCalls); assertEquals(3,audit.events.size());
    }
    @Test void clientIdentityAndPolymorphicOrUnexpectedContextFieldsAreRejected() throws Exception {
        for(String payload:List.of("{\"context\":{\"resourceId\":\"record-1\",\"tenantId\":\"tenant-a\"}}",
                "{\"userId\":7,\"context\":{\"resourceId\":\"record-1\"}}",
                "{\"context\":{\"@class\":\"anything\",\"resourceId\":\"record-1\"}}",
                "{\"context\":{\"resourceId\":{\"nested\":\"record-1\"}}}","{\"context\":{}}")) {
            failure(request(ALPHA,"summary",payload,token),400,"CONTEXT_INVALID");
        }
        assertEquals(0,fixture.alpha.metadataCalls);
    }
    @Test void malformedDuplicateDeepTrailingAndLongJsonHasNoEchoOrPartialResult() throws Exception {
        for(String payload:List.of("{\"context\":", "{\"context\":{\"resourceId\":\"record-1\",\"resourceId\":\"other\"}}",
                body+" {}", "{\"context\":{\"resourceId\":\""+"x".repeat(257)+"\"}}", "{\"context\":{\"resourceId\":[[[[\""+SECRET+"\"]]]]}}")) {
            failure(request(ALPHA,"summary",payload,token),400,"CONTEXT_INVALID");
        }
    }
    @Test void knownAndChunkedBodySizesAreBoundedAndCompressedBodyIsRejected() throws Exception {
        failure(request(ALPHA,"summary"," ".repeat(16385),token),413,"REQUEST_TOO_LARGE");
        var response=new org.springframework.mock.web.MockHttpServletResponse();
        var req=new org.springframework.mock.web.MockHttpServletRequest("POST","/ai/scenes/a/b/render") {
            @Override public long getContentLengthLong() { return -1; }
        };
        req.setContentType("application/json"); req.setContent(new byte[16385]);
        context.getBean(AiSceneReadBoundaryFilter.class).doFilter(req,response,(request,result)->fail("Oversized unknown-length input reached chain"));
        assertEquals(413,response.getStatus());
        var compressed=http.perform(post("/ai/scenes/"+ALPHA+"/summary/render").contentType("application/json")
                .header("Content-Encoding","gzip").header("Authorization",token).content(body)).andReturn();
        failure(compressed,415,"UNSUPPORTED_CONTENT");
    }
    @Test void operationResourceTenantAndOwnerPermissionsAreRealFailureBranches() throws Exception {
        when(cache.getCacheObject("admin:login:7")).thenReturn(session(7,"tenant-a",token,"ai:prompt:query"));
        failure(request(ALPHA,"summary",body,token),403,"BUSINESS_PERMISSION_REQUIRED");
        when(cache.getCacheObject("admin:login:7")).thenReturn(session(7,"tenant-a",token,ALPHA+":read"));
        failure(request(ALPHA,"summary","{\"context\":{\"resourceId\":\"missing\"}}",token),404,"RESOURCE_NOT_FOUND");
        fixture.alpha.resourceTenant="tenant-b"; failure(request(ALPHA,"summary",body,token),403,"TENANT_MISMATCH");
        fixture.alpha.resourceTenant="tenant-a"; fixture.alpha.owner=8;
        failure(request(ALPHA,"summary",body,token),403,"RESOURCE_FORBIDDEN"); assertEquals(0,fixture.alpha.valueCalls);
    }
    @Test void unregisteredAppsAndAnotherAppsContextDoNotFallBack() throws Exception {
        failure(request("unknown","summary",body,token),404,"APP_NOT_REGISTERED");
        failure(request(ALPHA,"summary","{\"context\":{\"otherAppReference\":\"record-1\"}}",token),400,"CONTEXT_INVALID");
        assertEquals(0,fixture.beta.valueCalls);
    }
    @Test void snapshotStateAndDraftRevisionRemainAuthoritative() throws Exception {
        var current=fixture.scenes.get(fixture.alphaScene);
        fixture.scenes.updateDraft(fixture.alphaScene,new AiPromptSceneService.DraftRequest(fixture.modelId,"DRAFT {{title}}",
                List.of(definition("title",AiPromptSceneRules.VariableType.STRING,false)),current.revision()));
        assertEquals("Alpha / "+SECRET,json.readTree(request(ALPHA,"summary",body,token).getResponse().getContentAsString()).get("data").get("prompt").asText());
        current=fixture.scenes.get(fixture.alphaScene); fixture.scenes.setEnabled(fixture.alphaScene,new AiPromptSceneService.StatusRequest(false,current.revision()));
        failure(request(ALPHA,"summary",body,token),409,"SCENE_UNAVAILABLE");
        current=fixture.scenes.get(fixture.alphaScene); fixture.scenes.setEnabled(fixture.alphaScene,new AiPromptSceneService.StatusRequest(true,current.revision()));
        fixture.jdbc.update("UPDATE sys_ai_model_config SET enabled=FALSE WHERE model_id=?",fixture.modelId);
        failure(request(ALPHA,"summary",body,token),409,"SCENE_UNAVAILABLE");
    }
    @Test void missingVariablesAndResolverFailureNeverExposeValuesInAuditOrErrors() throws Exception {
        fixture.alpha.values=Map.of("title","Alpha"); failure(request(ALPHA,"summary",body,token),422,"MISSING_VARIABLE");
        fixture.alpha.throwValues=true; failure(request(ALPHA,"summary",body,token),503,"RESOLVER_FAILED");
        assertFalse(audit.events.toString().contains(SECRET)); assertFalse(audit.events.toString().contains("record-1"));
        assertFalse(audit.events.toString().contains(token));
        assertFalse(java.util.Arrays.stream(AiSceneReadController.class.getDeclaredMethods()).anyMatch(method->method.isAnnotationPresent(com.xinghuiTec.annotation.Log.class)));
    }
    @Test void unpublishedSceneCannotProduceAnHttpResult() throws Exception {
        fixture.scenes.create(new AiPromptSceneService.CreateRequest(ALPHA,"draft_only",fixture.modelId,"{{title}}",
                List.of(definition("title",AiPromptSceneRules.VariableType.STRING,false))));
        failure(request(ALPHA,"draft_only",body,token),409,"SCENE_UNAVAILABLE");
        assertEquals(0,fixture.alpha.valueCalls);
    }
    @Test void duplicateConvertersAndIdentityFieldRegistrationAreRejected() {
        var registry=new AiVariableResolverRegistry(List.of(fixture.alpha,fixture.beta),fixture.rules);
        var converter=new Converter(ALPHA);
        var duplicate=assertThrows(AiBusinessRenderException.class,()->new AiSceneReadService(List.of(converter,converter),fixture.renderer,registry,fixture.rules));
        assertEquals(AiBusinessRenderException.Code.DUPLICATE_REGISTRATION,duplicate.code());
        converter.allowed=Set.of("resourceId","tenantId");
        assertEquals(AiBusinessRenderException.Code.INVALID_REGISTRATION,assertThrows(AiBusinessRenderException.class,
                ()->new AiSceneReadService(List.of(converter),fixture.renderer,registry,fixture.rules)).code());
    }
}
