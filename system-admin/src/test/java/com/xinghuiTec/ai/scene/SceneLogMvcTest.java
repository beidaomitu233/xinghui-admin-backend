package com.xinghuiTec.ai.scene;

import com.xinghuiTec.ai.application.AiInvocationIdentity;
import com.xinghuiTec.ai.contract.AiTypes.TrustedActor;
import com.xinghuiTec.controller.AiRequestLogController;
import com.xinghuiTec.domain.entity.*;
import com.xinghuiTec.security.PlatformAccessService;
import org.junit.jupiter.api.*;
import org.springframework.http.converter.json.MappingJackson2HttpMessageConverter;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.test.web.servlet.*;
import org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import java.sql.Timestamp;
import java.time.Instant;
import java.util.*;
import static org.junit.jupiter.api.Assertions.*;
import static org.hamcrest.Matchers.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

/** Real MVC constructor binding, identity and H2 log predicates; no full servlet security/filter-chain claim. */
class SceneLogMvcTest {
    private SceneTestSupport f;private MockMvc mvc;private String key,request,boundary;
    private final Instant from=Instant.parse("2026-10-16T14:52:00.000Z"),to=Instant.parse("2026-10-17T14:52:00.000Z");
    @BeforeEach void setup()throws Exception{
        f=new SceneTestSupport();var saved=f.save(f.create(false),"Prompt",false);key=saved.detail().scene().sceneKey();
        request=row(from,false,f.actor);boundary=row(to,false,f.actor);row(from,true,f.actor);
        row(from,false,new TrustedActor("102","tenant_b",Set.of("ai:scene:invoke"),null));
        login(f.actor.authorities());mvc=controller(false);
    }
    @AfterEach void close(){SecurityContextHolder.clearContext();if(f!=null)f.close();}
    private MockMvc controller(boolean tenantEnabled){return MockMvcBuilders.standaloneSetup(new AiRequestLogController(f.logs,new AiInvocationIdentity(),new PlatformAccessService(tenantEnabled)))
            .setMessageConverters(new MappingJackson2HttpMessageConverter(f.mapper)).build();}
    private void login(Set<String> authorities){
        var user=new SysUser();user.setUserId(101L);user.setTenantId("tenant_a");user.setStatus(1);
        var principal=new LoginUser(user,new ArrayList<>(authorities));principal.setTenantId("tenant_a");
        SecurityContextHolder.getContext().setAuthentication(new UsernamePasswordAuthenticationToken(principal,null,principal.getAuthorities()));
    }
    private String row(Instant time,boolean test,TrustedActor actor)throws Exception{
        var input=f.input(false,UUID.randomUUID().toString());
        var accepted=test?f.service.test(key,new SceneDtos.TestInput(1,0,true,input),actor):f.service.submit(key,input,actor);
        Instant until=Instant.now().plusSeconds(5);while(!f.runtime.status(Long.parseLong(accepted.requestId()),actor).status().terminal()){
            if(Instant.now().isAfter(until))fail("simulated worker timeout");Thread.sleep(5);
        }
        f.jdbc.update("UPDATE sys_ai_request SET create_time=? WHERE request_id=?",Timestamp.from(time),accepted.requestId());return accepted.requestId();
    }
    private MockHttpServletRequestBuilder query(String suffix){return get("/system/ai/requests"+suffix).param("scope","OWN").param("requestKind","BUSINESS")
            .param("sceneKey",key).param("modelRef","17").param("status","SUCCEEDED").param("from",from.toString()).param("to",to.toString()).param("pageNum","1").param("pageSize","20");}
    @Test void mvcBindsSharedFiltersForListStatisticsDetailAndExportAndExcludesExactUpperBoundary()throws Exception{
        mvc.perform(query("")).andExpect(status().isOk()).andExpect(header().string("Cache-Control","no-store, private"))
                .andExpect(jsonPath("$.data.total").value(1)).andExpect(jsonPath("$.data.pageNum").value(1)).andExpect(jsonPath("$.data.pageSize").value(20))
                .andExpect(jsonPath("$.data.records[0].request.requestId").value(request)).andExpect(jsonPath("$.data.records[0].request.sceneKey").value(key));
        mvc.perform(query("/statistics")).andExpect(status().isOk()).andExpect(jsonPath("$.data.requests").value(1)).andExpect(jsonPath("$.data.modelAttempts").value(1));
        mvc.perform(query("/"+request)).andExpect(status().isOk()).andExpect(jsonPath("$.data.request.request.requestId").value(request));
        mvc.perform(query("/export")).andExpect(status().isOk()).andExpect(jsonPath("$.data",hasSize(1))).andExpect(jsonPath("$.data[0].request.requestId").value(request));
        mvc.perform(query("/"+boundary)).andExpect(status().isNotFound()).andExpect(jsonPath("$.message").value("NOT_FOUND"));
        mvc.perform(get("/system/ai/requests")).andExpect(status().isOk()).andExpect(jsonPath("$.data.pageNum").value(1)).andExpect(jsonPath("$.data.pageSize").value(20));
        assertEquals(4,f.provider.calls.get());
    }
    @Test void mvcBindingAndFilterErrorsAreSafeAcrossEveryReadProjection()throws Exception{
        for(String suffix:List.of("","/statistics","/"+request,"/export")){
            mvc.perform(query(suffix).with(r->{r.setParameter("pageNum","private-invalid-page");return r;})).andExpect(status().isBadRequest()).andExpect(jsonPath("$.code").value(202))
                    .andExpect(jsonPath("$.message").value("INVALID_INPUT")).andExpect(header().string("Cache-Control","no-store, private"))
                    .andExpect(content().string(not(containsString("private-invalid-page"))));
            mvc.perform(query(suffix).param("tenantRef","other-tenant")).andExpect(status().isForbidden()).andExpect(jsonPath("$.message").value("FORBIDDEN"));
            mvc.perform(query(suffix).with(r->{r.setParameter("requestKind","UNKNOWN");return r;})).andExpect(status().isBadRequest()).andExpect(jsonPath("$.message").value("INVALID_INPUT"));
        }
        mvc.perform(get("/system/ai/requests").param("scope","UNKNOWN")).andExpect(status().isBadRequest()).andExpect(jsonPath("$.message").value("INVALID_INPUT"));
        mvc.perform(get("/system/ai/requests/statistics").param("from","private-invalid-instant")).andExpect(status().isBadRequest()).andExpect(jsonPath("$.message").value("INVALID_INPUT"))
                .andExpect(content().string(not(containsString("private-invalid-instant"))));
        assertEquals(4,f.provider.calls.get());
    }
    @Test void mvcReadActionsAndAuditCannotBroadenCurrentIdentity()throws Exception{
        login(Set.of("ai:scene:invoke","ai:request:list"));
        mvc.perform(query("")).andExpect(status().isOk()).andExpect(jsonPath("$.data.total").value(1));
        mvc.perform(query("/statistics")).andExpect(status().isOk());
        mvc.perform(query("/"+request)).andExpect(status().isForbidden()).andExpect(jsonPath("$.code").value(309));
        mvc.perform(query("/export")).andExpect(status().isForbidden()).andExpect(jsonPath("$.code").value(309));
        mvc.perform(get("/system/ai/requests").param("scope","AUDIT")).andExpect(status().isForbidden());
        login(f.actor.authorities());mvc=controller(true);
        mvc.perform(get("/system/ai/requests").param("scope","AUDIT")).andExpect(status().isForbidden());
        assertEquals(4,f.jdbc.queryForObject("SELECT COUNT(*) FROM sys_ai_request",Integer.class));assertEquals(4,f.provider.calls.get());
    }
}
