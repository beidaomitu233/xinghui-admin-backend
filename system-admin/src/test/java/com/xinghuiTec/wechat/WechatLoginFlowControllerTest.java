package com.xinghuiTec.wechat;

import com.xinghuiTec.businessconfig.BusinessConfigService;
import com.xinghuiTec.controller.WechatLoginFlowController;
import org.junit.jupiter.api.Test;
import org.springframework.mock.web.MockHttpServletRequest;
import java.util.Map;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

class WechatLoginFlowControllerTest {
    @Test void unconfiguredMpCallbackUsesFixedRelativeResultNeverRequestHost() {
        var flows=mock(WechatLoginFlowService.class);var configs=mock(WechatConfigService.class);var business=mock(BusinessConfigService.class);
        when(flows.callback(eq("mp"),any(),any(),any())).thenReturn("invalid");
        when(business.get("wechatMp")).thenReturn(new BusinessConfigService.View("wechatMp",false,Map.of("callbackUri",""),Map.of(),0,null,"DATABASE","DISABLED",true));
        var controller=new WechatLoginFlowController(flows,configs,business,false);
        var request=new MockHttpServletRequest();request.addHeader("Host","attacker.example");
        var response=controller.mpCallback("invalid",null,request);
        assertEquals(303,response.getStatusCode().value());
        assertEquals("/auth/wechat-result?status=invalid",response.getHeaders().getFirst("Location"));
    }
}
