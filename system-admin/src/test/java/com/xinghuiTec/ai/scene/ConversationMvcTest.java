package com.xinghuiTec.ai.scene;

import com.xinghuiTec.ai.application.*;
import com.xinghuiTec.ai.conversation.*;
import com.xinghuiTec.domain.entity.*;
import com.xinghuiTec.security.PlatformAccessService;
import org.junit.jupiter.api.Test;
import org.springframework.http.converter.json.MappingJackson2HttpMessageConverter;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import java.util.*;
import static org.junit.jupiter.api.Assertions.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

/** Real bounded body filter + MVC + identity + database; simulated provider, not external acceptance. */
class ConversationMvcTest {
    @Test void startRefreshAndFollowupUseDatabaseBodyAndRejectForgedIdentityBeforeAnotherAdmission() throws Exception {
        try (var f = new SceneTestSupport()) {
            f.script(f.read("V20261018_240__conversation_history.sql"));
            var store = new ConversationStore(f.jdbc, f.requests, f.manager);
            f.runtime.conversations(store);
            f.lifecycle.conversations(store);
            f.service.conversations(store);
            var service = new ConversationService(store, f.service, f.runtime);
            var user = new SysUser();
            user.setUserId(101L);
            user.setTenantId("tenant_a");
            user.setStatus(1);
            var login = new LoginUser(user, new ArrayList<>(f.actor.authorities()));
            login.setTenantId("tenant_a");
            SecurityContextHolder.getContext().setAuthentication(new UsernamePasswordAuthenticationToken(login, null, login.getAuthorities()));
            var mvc = MockMvcBuilders.standaloneSetup(new ConversationController(service, new AiInvocationIdentity(),
                            new PlatformAccessService(false), new AiRuntimeBodyCodec()))
                    .setControllerAdvice(new SceneHttpErrors())
                    .setMessageConverters(new MappingJackson2HttpMessageConverter(f.mapper))
                    .addFilters(new AiRuntimeBoundaryFilter(f.mapper)).build();
            var scene = f.save(f.create(false), "Answer {{user_request}}", false);
            var first = new ConversationDtos.Start(scene.detail().scene().sceneKey(), ConversationDtos.Kind.BUSINESS,
                    null, "first actual user message", UUID.randomUUID().toString(), null);
            var response = mvc.perform(post("/ai/conversations").contentType("application/json").content(f.mapper.writeValueAsBytes(first)))
                    .andExpect(status().isAccepted()).andExpect(jsonPath("$.data.conversationId").isString()).andReturn();
            var accepted = f.mapper.readTree(response.getResponse().getContentAsString()).path("data");
            String conversation = accepted.path("conversationId").asText();
            f.terminal(accepted.path("requestId").asText());
            var history = mvc.perform(get("/ai/conversations/" + conversation)).andExpect(status().isOk())
                    .andExpect(header().string("Cache-Control", "no-store, private"))
                    .andExpect(jsonPath("$.data.messages[0].content").value("first actual user message"))
                    .andExpect(jsonPath("$.data.messages[1].content").value("controlled fixture result")).andReturn();
            int revision = f.mapper.readTree(history.getResponse().getContentAsString()).path("data").path("conversation").path("revision").asInt();
            var next = new ConversationDtos.Continue(revision, "followup user message", UUID.randomUUID().toString(), null);
            var followup = mvc.perform(post("/ai/conversations/" + conversation + "/messages").contentType("application/json").content(f.mapper.writeValueAsBytes(next)))
                    .andExpect(status().isAccepted()).andReturn();
            f.terminal(f.mapper.readTree(followup.getResponse().getContentAsString()).path("data").path("requestId").asText());
            mvc.perform(get("/ai/conversations/" + conversation)).andExpect(status().isOk())
                    .andExpect(jsonPath("$.data.messages.length()").value(4));
            mvc.perform(post("/ai/conversations").contentType("application/json").content("{\"actor\":\"forged\",\"message\":\"secret-input\"}"))
                    .andExpect(status().isBadRequest()).andExpect(jsonPath("$.message").value("INVALID_INPUT"));
            assertEquals(2, f.provider.calls.get());
            assertEquals(2, f.jdbc.queryForObject("SELECT COUNT(*) FROM sys_ai_request", Integer.class));
        } finally {
            SecurityContextHolder.clearContext();
        }
    }
}
