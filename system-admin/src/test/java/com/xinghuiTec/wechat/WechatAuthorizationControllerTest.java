package com.xinghuiTec.wechat;

import com.xinghuiTec.controller.WechatAuthorizationController;
import jakarta.servlet.http.Cookie;
import org.junit.jupiter.api.Test;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import java.util.function.Function;
import static org.mockito.Mockito.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

class WechatAuthorizationControllerTest {
    @Test void anonymousBoundariesCookieAndCallbackNeverCreateLoginState() throws Exception {
        var configs=mock(WechatConfigService.class); var states=mock(WechatStateStore.class);
        var config=new WechatConfigService.Config(true,"wx123","PATH","https://login.example.com/api/auth/wechat/callback",4,true);
        when(configs.get()).thenReturn(config);
        when(configs.currentSecret()).thenReturn("unit-wechat-secret");
        when(configs.withCurrent(any())).thenAnswer(call -> ((Function<WechatConfigService.Config, ?>)call.getArgument(0)).apply(config));
        when(states.available()).thenReturn(true);
        var mvc=MockMvcBuilders.standaloneSetup(new WechatAuthorizationController(configs,states,true)).build();
        mvc.perform(get("/auth/wechat/capability")).andExpect(status().isOk())
                .andExpect(jsonPath("$.data.available").value(true)).andExpect(jsonPath("$.data.clientId").doesNotExist())
                .andExpect(jsonPath("$.data.secretEnvName").doesNotExist());
        mvc.perform(post("/auth/wechat/authorize").header("Origin","https://attacker.example"))
                .andExpect(status().isForbidden()); verify(states,never()).create(any(),any(),anyInt(),any());
        mvc.perform(post("/auth/wechat/authorize").header("Origin","https://login.example.com").content("{\"returnUrl\":\"https://attacker.example\"}"))
                .andExpect(status().isBadRequest());
        mvc.perform(post("/auth/wechat/authorize").header("Origin","https://login.example.com").content("   "))
                .andExpect(status().isBadRequest());
        mvc.perform(post("/auth/wechat/authorize").header("Origin","https://login.example.com").header("Transfer-Encoding","chunked"))
                .andExpect(status().isBadRequest());
        mvc.perform(post("/auth/wechat/authorize").header("Origin","https://login.example.com"))
                .andExpect(status().isOk()).andExpect(header().string("Cache-Control","no-store"))
                .andExpect(header().string("Set-Cookie",org.hamcrest.Matchers.allOf(
                        org.hamcrest.Matchers.containsString("HttpOnly"),org.hamcrest.Matchers.containsString("Secure"),
                        org.hamcrest.Matchers.containsString("SameSite=Lax"),org.hamcrest.Matchers.containsString("Path=/"))))
                .andExpect(jsonPath("$.data.expiresInSeconds").value(300));
        String state=WechatStateStore.random(),binding=WechatStateStore.random();
        when(states.consume(state,binding,4)).thenReturn(true,false);
        mvc.perform(get("/auth/wechat/callback").param("state",state).param("code","not-an-identity")
                .cookie(new Cookie(WechatAuthorizationController.COOKIE,binding)))
                .andExpect(status().isSeeOther()).andExpect(header().string("Location","https://login.example.com/#/auth/wechat-result?status=pending"))
                .andExpect(header().doesNotExist("Set-Cookie")).andExpect(header().string("Referrer-Policy","no-referrer"));
        mvc.perform(get("/auth/wechat/callback").param("state",state).param("code","not-an-identity")
                .cookie(new Cookie(WechatAuthorizationController.COOKIE,binding)))
                .andExpect(header().string("Location","https://login.example.com/#/auth/wechat-result?status=invalid"));
    }
}
