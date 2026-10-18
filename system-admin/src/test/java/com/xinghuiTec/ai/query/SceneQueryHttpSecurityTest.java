package com.xinghuiTec.ai.query;

import com.xinghuiTec.ai.application.*;
import com.xinghuiTec.ai.contract.*;
import com.xinghuiTec.ai.contract.AiSceneQueryRuntime.*;
import com.xinghuiTec.ai.contract.AiTypes.*;
import com.xinghuiTec.domain.entity.*;
import org.junit.jupiter.api.*;
import org.springframework.context.annotation.*;
import org.springframework.mock.web.MockServletContext;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.config.annotation.method.configuration.EnableMethodSecurity;
import org.springframework.security.config.annotation.web.configuration.EnableWebSecurity;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.web.SecurityFilterChain;
import org.springframework.test.web.servlet.*;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import org.springframework.web.context.support.AnnotationConfigWebApplicationContext;
import org.springframework.web.servlet.config.annotation.EnableWebMvc;
import java.util.*;
import static org.junit.jupiter.api.Assertions.*;
import static org.springframework.security.test.web.servlet.setup.SecurityMockMvcConfigurers.springSecurity;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.authentication;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

class SceneQueryHttpSecurityTest {
    @Configuration @EnableWebMvc @EnableWebSecurity @EnableMethodSecurity static class Web {
        @Bean SecurityFilterChain chain(HttpSecurity http)throws Exception{return http.csrf(c->c.disable()).authorizeHttpRequests(c->c.anyRequest().authenticated()).httpBasic(c->{}).build();}
        @Bean FakeRuntime runtime(){return new FakeRuntime();}
        @Bean AiInvocationIdentity identity(){return new AiInvocationIdentity();}
        @Bean SceneQueryController controller(FakeRuntime runtime,AiInvocationIdentity identity){return new SceneQueryController(runtime,identity);}
    }
    static class FakeRuntime implements AiSceneQueryRuntime {
        int calls;TrustedActor actor;
        public AuthorizedSchema authorizedSchema(TrustedActor actor,QueryContext context,InvocationControl control){this.actor=actor;calls++;return new AuthorizedSchema(List.of(),List.of(),List.of(),"safe","fingerprint");}
        public SqlValidation validateGeneratedSql(SqlQuery q,TrustedActor a,QueryContext c,InvocationControl i){calls++;return new SqlValidation(true,q.sql(),List.of(),List.of(),"fingerprint");}
        public CompiledQuery compile(SqlQuery q,TrustedActor a,QueryContext c,InvocationControl i){throw new UnsupportedOperationException();}
        public QueryResult preview(SqlQuery q,TrustedActor a,QueryContext c,InvocationControl i){calls++;return new QueryResult(List.of(),List.of(),0,false,List.of());}
        public QueryResult read(CompiledQuery q,TrustedActor a,QueryContext c,InvocationControl i){throw new UnsupportedOperationException();}
        public void authorize(CompiledQuery q,TrustedActor a,QueryContext c,InvocationControl i){throw new UnsupportedOperationException();}
    }
    AnnotationConfigWebApplicationContext context;MockMvc http;FakeRuntime runtime;
    @BeforeEach void start(){context=new AnnotationConfigWebApplicationContext();context.setServletContext(new MockServletContext());context.register(Web.class);context.refresh();runtime=context.getBean(FakeRuntime.class);
        http=MockMvcBuilders.webAppContextSetup(context).addFilters(new AiRuntimeBoundaryFilter(new com.fasterxml.jackson.databind.ObjectMapper())).apply(springSecurity()).build();}
    @AfterEach void close(){if(context!=null)context.close();}
    UsernamePasswordAuthenticationToken login(String permission){var user=new SysUser();user.setUserId(101L);user.setTenantId("tenant_a");user.setStatus(1);var principal=new LoginUser(user,List.of(permission));principal.setTenantId("tenant_a");return new UsernamePasswordAuthenticationToken(principal,null,principal.getAuthorities());}
    @Test void realMethodSecurityProxyStartsAndAdmitsTenantSceneAction()throws Exception{
        assertTrue(org.springframework.aop.support.AopUtils.isAopProxy(context.getBean(SceneQueryController.class)));
        http.perform(post("/system/ai/scene-data/sql/schema").with(authentication(login("ai:scene:edit"))).contentType("application/json").content("{\"datasetKeys\":[],\"businessRef\":null}"))
                .andExpect(status().isOk()).andExpect(header().string("Cache-Control","no-store, private"));assertEquals("tenant_a",runtime.actor.tenantId());assertEquals(1,runtime.calls);
    }
    @Test void anonymousAndUnrelatedAuthorityCannotReachRuntime()throws Exception{
        http.perform(post("/system/ai/scene-data/sql/schema").contentType("application/json").content("{}" )).andExpect(status().isUnauthorized());
        http.perform(post("/system/ai/scene-data/sql/schema").with(authentication(login("system:user:query"))).contentType("application/json").content("{}" )).andExpect(status().isForbidden());assertEquals(0,runtime.calls);
    }
    @Test void runtimeBoundaryDoesNotConsumeSqlPreviewBody()throws Exception{
        var response=http.perform(post("/system/ai/scene-data/sql/preview").with(authentication(login("ai:scene:test"))).contentType("application/json")
                .content("{\"datasetKeys\":[],\"query\":{\"sql\":\"select id from orders\",\"maxRows\":20}}"))
                .andExpect(status().isOk()).andReturn().getResponse();
        assertEquals(0,new com.fasterxml.jackson.databind.ObjectMapper().readTree(response.getContentAsString()).path("data").path("rowCount").intValue());assertEquals(1,runtime.calls);
    }
    @Test void duplicateUnknownOversizeAndForgedScopeBodiesFailBeforeRuntime()throws Exception{
        for(String value:List.of("{\"datasetKeys\":[],\"datasetKeys\":[]}","{\"actor\":{\"tenantId\":\"other\"}}", "{\"scope\":\"PLATFORM\"}"))
            http.perform(post("/system/ai/scene-data/sql/schema").with(authentication(login("ai:scene:query"))).contentType("application/json").content(value)).andExpect(status().isBadRequest());
        http.perform(post("/system/ai/scene-data/sql/schema").with(authentication(login("ai:scene:query"))).contentType("application/json").content(" ".repeat(65537))).andExpect(status().isPayloadTooLarge());assertEquals(0,runtime.calls);
    }
}
