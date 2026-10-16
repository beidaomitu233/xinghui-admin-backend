package com.xinghuiTec.ai.template;

import org.junit.jupiter.api.Test;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import static org.mockito.Mockito.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;
import java.util.Map;

/** HTTP serialization/cache/error tests; full authorization chain is verified in the combined app. */
class TemplateResponsePolicyTest {
    @Test void optionsHttpUsesExactStringIdsAndMetadataOnlyEnvelope() throws Exception {
        var service = mock(TemplateDataService.class);
        var option = new TemplateDataService.TemplateOption("9007199254740993", "9007199254740994", 1, "摘要", java.util.List.of(
                new com.xinghuiTec.ai.contract.AiTemplateRuntime.Variable("note", com.xinghuiTec.ai.contract.AiTemplateRuntime.VariableType.STRING, true, false)), java.util.List.of(), true, java.util.List.of());
        when(service.options(1, 20, "摘要", "201")).thenReturn(new TemplateDataService.OptionsPage(java.util.List.of(option), "1", 1, 20));
        var mvc = MockMvcBuilders.standaloneSetup(new TemplateAdminController(service)).setControllerAdvice(new TemplateResponsePolicy()).build();
        mvc.perform(get("/system/ai/templates/options").param("keyword", "摘要").param("appId", "201"))
                .andExpect(status().isOk()).andExpect(header().string("Cache-Control", "no-store, private"))
                .andExpect(jsonPath("$.data.total").value("1")).andExpect(jsonPath("$.data.records[0].templateVersionId").value("9007199254740994"))
                .andExpect(jsonPath("$.data.records[0].inputVariables[0].hasDefault").value(false))
                .andExpect(jsonPath("$.data.records[0].inputVariables[0].defaultValue").doesNotExist())
                .andExpect(jsonPath("$.data.records[0].messages").doesNotExist());
    }
    @Test void definitionsAndDeniedErrorsAreNeverCached() throws Exception {
        var service = mock(TemplateDataService.class);
        when(service.get(false, 1)).thenReturn(Map.of("id", "9007199254740993"));
        when(service.get(false, 2)).thenThrow(new AccessDeniedException("private-business-body"));
        var mvc = MockMvcBuilders.standaloneSetup(new TemplateAdminController(service)).setControllerAdvice(new TemplateResponsePolicy()).build();
        mvc.perform(get("/system/ai/templates/1")).andExpect(status().isOk()).andExpect(header().string("Cache-Control", "no-store, private")).andExpect(jsonPath("$.data.id").value("9007199254740993"));
        mvc.perform(get("/system/ai/templates/2")).andExpect(status().isForbidden()).andExpect(jsonPath("$.code").value(309)).andExpect(jsonPath("$.message").value("FORBIDDEN")).andExpect(jsonPath("$.data").isEmpty()).andExpect(header().string("Cache-Control", "no-store, private")).andExpect(content().string(org.hamcrest.Matchers.not(org.hamcrest.Matchers.containsString("private-business-body"))));
    }
    @Test void malformedBodyIsSafeAndNotCached() throws Exception {
        var service = mock(TemplateDataService.class);
        var mvc = MockMvcBuilders.standaloneSetup(new DataResourceAdminController(service)).setControllerAdvice(new TemplateResponsePolicy()).build();
        mvc.perform(post("/system/ai/data-resources").contentType("application/json").content("{private-business-body"))
                .andExpect(status().isBadRequest()).andExpect(jsonPath("$.code").value(202)).andExpect(jsonPath("$.message").value("INVALID_INPUT")).andExpect(jsonPath("$.data").isEmpty()).andExpect(header().string("Cache-Control", "no-store, private")).andExpect(content().string(org.hamcrest.Matchers.not(org.hamcrest.Matchers.containsString("private-business-body"))));
        mvc.perform(post("/system/ai/data-resources").contentType("application/json").content("{\"name\":\"资源\",\"domain\":\"测试\",\"owner\":\"负责人\",\"source\":\"lowcode.record\",\"formId\":\"1\",\"fields\":[\"title\"],\"subjects\":[\"101\"],\"applications\":[\"201\"],\"timeoutMillis\":1000,\"url\":\"https://private-business-body\"}"))
                .andExpect(status().isBadRequest()).andExpect(content().string(org.hamcrest.Matchers.not(org.hamcrest.Matchers.containsString("private-business-body"))));
        verifyNoInteractions(service);
    }
    @Test void conflictMissingValidationAndServiceFailureKeepHttpAndBusinessCodesSeparate() throws Exception {
        var service = mock(TemplateDataService.class);
        when(service.get(false, 3)).thenThrow(new com.xinghuiTec.exception.ServiceException("private-business-body", com.xinghuiTec.emues.ResultCodeEnum.FAIL.getCode()));
        when(service.get(false, 4)).thenThrow(new com.xinghuiTec.exception.ServiceException("private-business-body", com.xinghuiTec.emues.ResultCodeEnum.SERVICE_ERROR.getCode()));
        when(service.get(false, 5)).thenThrow(new com.xinghuiTec.exception.ServiceException("private-business-body", com.xinghuiTec.emues.ResultCodeEnum.DATA_ERROR.getCode()));
        when(service.get(false, 6)).thenThrow(new IllegalStateException("private-business-body"));
        when(service.get(false, 7)).thenThrow(new com.xinghuiTec.ai.contract.AiRuntimeException(com.xinghuiTec.ai.contract.AiTypes.ErrorCode.RESOURCE_TIMEOUT));
        var mvc = MockMvcBuilders.standaloneSetup(new TemplateAdminController(service)).setControllerAdvice(new TemplateResponsePolicy()).build();
        int[][] cases = {{3,409,201}, {4,503,203}, {5,404,204}, {6,500,203}, {7,422,202}};
        for (var expected : cases) mvc.perform(get("/system/ai/templates/" + expected[0]))
                .andExpect(status().is(expected[1])).andExpect(jsonPath("$.code").value(expected[2])).andExpect(jsonPath("$.data").isEmpty())
                .andExpect(header().string("Cache-Control", "no-store, private"))
                .andExpect(content().string(org.hamcrest.Matchers.not(org.hamcrest.Matchers.containsString("private-business-body"))));
        mvc.perform(post("/system/ai/templates").contentType("text/plain").content("private-business-body"))
                .andExpect(status().isUnsupportedMediaType()).andExpect(jsonPath("$.code").value(202)).andExpect(jsonPath("$.data").isEmpty());
    }
}
