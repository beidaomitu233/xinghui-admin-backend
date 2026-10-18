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
        int calls;TrustedActor actor;SqlQuery lastQuery;
        public AuthorizedSchema authorizedSchema(TrustedActor actor,QueryContext context,InvocationControl control){this.actor=actor;calls++;return new AuthorizedSchema(List.of(),List.of(),List.of(),"safe","fingerprint");}
        public SqlValidation validateGeneratedSql(SqlQuery q,TrustedActor a,QueryContext c,InvocationControl i){calls++;return new SqlValidation(true,q.sql(),List.of(),List.of(),"fingerprint");}
        public CompiledQuery compile(SqlQuery q,TrustedActor a,QueryContext c,InvocationControl i){throw new UnsupportedOperationException();}
        public QueryResult preview(SqlQuery q,TrustedActor a,QueryContext c,InvocationControl i){calls++;lastQuery=q;
            if(q.sql().equals("select decimal_fixture"))return new QueryResult(List.of(new QueryColumn("amount","金额","DECIMAL",false)),List.of(Map.of("amount",new java.math.BigDecimal("1234567890123456789.12345678"))),1,false,List.of());
            return new QueryResult(List.of(),List.of(),0,false,List.of());}
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
    @Test void adjacentNineteenDigitParametersSurviveHttpJsonRoundTrip()throws Exception {
        var mapper=new com.fasterxml.jackson.databind.ObjectMapper();
        for(String number:List.of("9007199254740993123","9007199254740993124")) {
            var query=new SqlQuery("select id from orders where id=:key",Map.of("key",new java.math.BigDecimal(number),"code","00123"),20);
            String outgoing=mapper.writeValueAsString(Map.of("datasetKeys",List.of("db.orders"),"query",query));
            // Browser JSON.parse/stringify sees strings in a typed number envelope, never the rounded JS number.
            String roundTrip=mapper.writeValueAsString(mapper.readValue(outgoing,Map.class));
            assertTrue(roundTrip.contains("\"$number\":\""+number+"\""));
            http.perform(post("/system/ai/scene-data/sql/preview").with(authentication(login("ai:scene:test"))).contentType("application/json").content(roundTrip)).andExpect(status().isOk());
            assertEquals(new java.math.BigDecimal(number),runtime.lastQuery.parameters().get("key"));assertEquals("00123",runtime.lastQuery.parameters().get("code"));
            // Non-sensitive synthetic parameter evidence can also be parsed by an actual ECMAScript JSON runtime.
            System.out.println("SQL_NUMBER_ENVELOPE "+mapper.writeValueAsString(mapper.readTree(outgoing).path("query").path("parameters")));
        }
    }
    @Test void publicRowsAreExactTextWhileInternalRowsRemainNumbers()throws Exception {
        var response=http.perform(post("/system/ai/scene-data/sql/preview").with(authentication(login("ai:scene:test"))).contentType("application/json")
                .content("{\"query\":{\"sql\":\"select decimal_fixture\",\"maxRows\":20}}" )).andExpect(status().isOk()).andReturn().getResponse();
        var tree=new com.fasterxml.jackson.databind.ObjectMapper().readTree(response.getContentAsString());
        assertEquals("1234567890123456789.12345678",tree.path("data").path("rows").get(0).path("amount").textValue());
        var result=runtime.preview(runtime.lastQuery,null,null,null);assertInstanceOf(java.math.BigDecimal.class,result.rows().get(0).get("amount"));
        assertTrue(new com.fasterxml.jackson.databind.ObjectMapper().writeValueAsString(result.rows()).contains("1234567890123456789.12345678"));
    }
}
