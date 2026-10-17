package com.xinghuiTec.ai.scene;

import org.junit.jupiter.api.Test;
import java.util.Map;
import static org.junit.jupiter.api.Assertions.*;

class ScenePreviewTest {
    @Test void originalPromptRoleMatchesCompiledUserMessageAndPreviewKeepsAllRuntimeValuesMasked()throws Exception{
        try(var fixture=new SceneTestSupport()){
            String prompt="Read {{record_subject}}";
            var scene=fixture.save(fixture.create(true),prompt,true);
            var preview=fixture.service.preview(scene.detail().scene().sceneKey(),new SceneDtos.PreviewInput(1,
                    new SceneDtos.BusinessRef("lowcode.record","1:11"),Map.of("user_request","private-preview-input")),fixture.actor);
            assertEquals(1,preview.originalMessages().size());assertEquals("USER",preview.originalMessages().get(0).role());
            assertEquals(prompt,preview.originalMessages().get(0).content());
            assertEquals("SYSTEM",preview.renderedMessages().get(0).role());assertEquals("USER",preview.renderedMessages().get(1).role());
            assertTrue(preview.renderedMessages().stream().allMatch(m->"[已脱敏]".equals(m.content())));
            assertEquals("[已脱敏]",preview.inputSummary().get("user_request"));assertFalse(preview.resourceSummary().isEmpty());
            assertTrue(preview.resourceSummary().values().stream().allMatch("[已脱敏]"::equals));
            String serialized=fixture.mapper.writeValueAsString(preview);
            for(String secret:new String[]{"visible subject","must-never-enter-model","private-preview-input"})assertFalse(serialized.contains(secret));
            assertEquals(0,fixture.provider.calls.get());assertEquals(0,fixture.jdbc.queryForObject("SELECT COUNT(*) FROM sys_ai_request",Integer.class));
        }
    }
}
