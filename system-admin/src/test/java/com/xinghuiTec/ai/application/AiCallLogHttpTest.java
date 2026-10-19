package com.xinghuiTec.ai.application;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.xinghuiTec.ai.contract.AiRuntimeException;
import com.xinghuiTec.ai.contract.AiTypes.*;
import com.xinghuiTec.controller.AiCallLogController;
import com.xinghuiTec.security.PlatformAccessService;
import org.junit.jupiter.api.Test;
import org.springframework.http.converter.json.MappingJackson2HttpMessageConverter;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import java.util.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

/** Real MVC constructor-bound query and safe envelopes; method security has its own test. */
class AiCallLogHttpTest {
    @Test void queryBindingEnumsNoCacheAndErrorEnvelopesAreStable()throws Exception{
        var service=mock(AiCallLogService.class);var identity=mock(AiInvocationIdentity.class);var platform=mock(PlatformAccessService.class);
        var actor=new TrustedActor("101","tenant-a",Set.of("ai:request:list"),null);when(identity.current()).thenReturn(actor);
        when(service.page(eq(actor),eq(false),any(),eq(2),eq(10))).thenReturn(new AiCallLogService.Page(List.of(),0,2,10));
        var mvc=MockMvcBuilders.standaloneSetup(new AiCallLogController(service,identity,platform))
                .setMessageConverters(new MappingJackson2HttpMessageConverter(new ObjectMapper().findAndRegisterModules())).build();
        mvc.perform(get("/system/ai/call-logs").param("scope","OWN").param("status","SUCCEEDED").param("entryType","REQUEST_ATTEMPT").param("providerCode","openrouter")
                .param("from","2026-10-01T00:00:00Z").param("to","2026-10-19T00:00:00Z").param("pageNum","2").param("pageSize","10"))
                .andExpect(status().isOk()).andExpect(header().string("Cache-Control","no-store, private")).andExpect(jsonPath("$.data.pageNum").value(2));
        verify(service).page(eq(actor),eq(false),argThat(f->f.entryType()==AiCallLogService.EntryType.REQUEST_ATTEMPT&&"openrouter".equals(f.providerCode())&&f.from().toString().equals("2026-10-01T00:00:00Z")),eq(2),eq(10));
        for(String invalid:new String[]{"scope","status","entryType"})mvc.perform(get("/system/ai/call-logs").param(invalid,"UNKNOWN"))
                .andExpect(status().isBadRequest()).andExpect(jsonPath("$.message").value("INVALID_INPUT"));
        mvc.perform(get("/system/ai/call-logs").param("from","yesterday")).andExpect(status().isBadRequest());
        when(service.statistics(eq(actor),eq(false),any())).thenThrow(new AiRuntimeException(ErrorCode.FORBIDDEN));
        mvc.perform(get("/system/ai/call-logs/statistics")).andExpect(status().isForbidden()).andExpect(jsonPath("$.message").value("FORBIDDEN"));
    }
    @Test void productionLongSerializationKeepsCanonicalZeroAndLargeCountsExact()throws Exception{
        var service=mock(AiCallLogService.class);var identity=mock(AiInvocationIdentity.class);var platform=mock(PlatformAccessService.class);
        var actor=new TrustedActor("101","tenant-a",Set.of("ai:request:list"),null);when(identity.current()).thenReturn(actor);
        when(service.page(eq(actor),eq(false),any(),eq(1),eq(20))).thenReturn(new AiCallLogService.Page(List.of(Map.of("callId","fixture","inputTokens",0L,"outputTokens",Long.MAX_VALUE,"reportedCost","0.00000000000000001")),1,1,20));
        var builder=org.springframework.http.converter.json.Jackson2ObjectMapperBuilder.json();new com.xinghuiTec.config.JacksonConfig().jackson2ObjectMapperBuilderCustomizer().customize(builder);
        var mvc=MockMvcBuilders.standaloneSetup(new AiCallLogController(service,identity,platform)).setMessageConverters(new MappingJackson2HttpMessageConverter(builder.build())).build();
        mvc.perform(get("/system/ai/call-logs")).andExpect(status().isOk()).andExpect(jsonPath("$.data.total").value("1"))
                .andExpect(jsonPath("$.data.records[0].inputTokens").value("0")).andExpect(jsonPath("$.data.records[0].outputTokens").value("9223372036854775807"))
                .andExpect(jsonPath("$.data.records[0].reportedCost").value("0.00000000000000001"));
    }
}
