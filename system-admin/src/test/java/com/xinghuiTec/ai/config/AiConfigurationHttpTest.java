package com.xinghuiTec.ai.config;

import com.xinghuiTec.controller.AiConfigurationController;
import org.junit.jupiter.api.Test;
import org.springframework.http.MediaType;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

class AiConfigurationHttpTest {
    @Test void separatesHttpStatusFromExistingBusinessCodesAndNeverReturnsExceptionCause() throws Exception {
        var configs=mock(AiConfigurationService.class);
        var mvc=MockMvcBuilders.standaloneSetup(new AiConfigurationController(configs,mock(AiConfigurationTests.class))).build();
        mvc.perform(post("/system/ai/configurations").contentType(MediaType.APPLICATION_JSON).content("{"))
                .andExpect(status().isBadRequest()).andExpect(jsonPath("$.code").value(202)).andExpect(jsonPath("$.message").value("AI_CONFIG:INVALID_INPUT")).andExpect(jsonPath("$.data").doesNotExist());
        when(configs.get(1)).thenThrow(new AccessDeniedException("private exception body"));
        mvc.perform(get("/system/ai/configurations/1")).andExpect(status().isForbidden()).andExpect(jsonPath("$.code").value(309)).andExpect(jsonPath("$.message").value("AI_CONFIG:FORBIDDEN"));
        when(configs.activate(eq(1L),any())).thenThrow(new AiConfigurationException(409,"配置已变化"));
        mvc.perform(post("/system/ai/configurations/1/activate").contentType(MediaType.APPLICATION_JSON).content("{\"revision\":0,\"configVersion\":\"1\",\"onlyAvailable\":false}"))
                .andExpect(status().isConflict()).andExpect(jsonPath("$.code").value(201));
        when(configs.get(2)).thenThrow(new IllegalStateException("private key or provider body"));
        mvc.perform(get("/system/ai/configurations/2")).andExpect(status().isServiceUnavailable()).andExpect(jsonPath("$.code").value(203)).andExpect(jsonPath("$.message").value("AI_CONFIG:DEPENDENCY_UNAVAILABLE"));
        when(configs.get(3)).thenThrow(new AiConfigurationException(404,"配置不存在"));
        mvc.perform(get("/system/ai/configurations/3")).andExpect(status().isNotFound()).andExpect(jsonPath("$.code").value(204));
    }
}
