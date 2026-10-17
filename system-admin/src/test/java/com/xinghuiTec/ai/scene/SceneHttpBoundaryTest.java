package com.xinghuiTec.ai.scene;

import com.xinghuiTec.ai.application.*;
import com.xinghuiTec.ai.contract.AiRuntimeException;
import org.junit.jupiter.api.*;
import org.springframework.mock.web.*;
import java.nio.charset.StandardCharsets;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicBoolean;
import static org.junit.jupiter.api.Assertions.*;

class SceneHttpBoundaryTest {
    @Test void longChineseConfigurationPassesRealBoundedFilterCodecAndStaticSaveWithoutProvider()throws Exception{
        try(var fixture=new SceneTestSupport()){
            var scene=fixture.create(false);String prompt="中".repeat(12000);
            var input=new SceneDtos.Save(0,UUID.randomUUID().toString(),fixture.definition(prompt,false));byte[] bytes=fixture.mapper.writeValueAsBytes(input);
            assertTrue(bytes.length>32768);var request=new MockHttpServletRequest("PUT","/system/ai/scenes/"+scene.scene().sceneKey());
            request.setServletPath(request.getRequestURI());request.setContentType("application/json");request.setContent(bytes);var response=new MockHttpServletResponse();
            var codec=new AiRuntimeBodyCodec();new AiRuntimeBoundaryFilter(fixture.mapper).doFilter(request,response,(in,out)->{
                var parsed=codec.readSceneConfiguration((byte[])in.getAttribute(AiRuntimeBoundaryFilter.BODY),SceneDtos.Save.class);
                var saved=fixture.management.save(scene.scene().sceneKey(),parsed,fixture.actor);assertEquals(prompt,saved.detail().definition().promptText());
            });
            assertEquals(200,response.getStatus());assertEquals("no-store",response.getHeader("Cache-Control"));assertNotNull(response.getHeader("X-Request-ID"));assertEquals(0,fixture.provider.calls.get());
            assertThrows(AiRuntimeException.class,()->codec.read(bytes,SceneDtos.Save.class));
        }
    }
    @Test void duplicateUnknownTrailingAndOversizeConfigAreRejectedAndBusinessLimitsStaySmall()throws Exception{
        var codec=new AiRuntimeBodyCodec();
        for(String json:new String[]{"{\"expectedRevision\":0,\"expectedRevision\":1}","{\"expectedRevision\":0,\"actor\":\"forged\"}","{} {}"})
            assertThrows(AiRuntimeException.class,()->codec.readSceneConfiguration(json.getBytes(StandardCharsets.UTF_8),SceneDtos.Save.class));
        var request=new MockHttpServletRequest("PUT","/system/ai/scenes/scene.example");request.setServletPath(request.getRequestURI());request.setContentType("application/json");request.setContent(new byte[131073]);
        var response=new MockHttpServletResponse();var called=new AtomicBoolean();new AiRuntimeBoundaryFilter(new com.fasterxml.jackson.databind.ObjectMapper()).doFilter(request,response,(in,out)->called.set(true));
        assertEquals(413,response.getStatus());assertFalse(called.get());
        try(var fixture=new SceneTestSupport()){
            var scene=fixture.save(fixture.create(false),"Prompt",false);
            var input=new SceneDtos.Submission(null,java.util.Map.of("user_request","x".repeat(2049)),UUID.randomUUID().toString());
            assertThrows(AiRuntimeService.AdmissionFailure.class,()->fixture.service.submit(scene.detail().scene().sceneKey(),input,fixture.actor));assertEquals(0,fixture.provider.calls.get());
        }
    }
}
