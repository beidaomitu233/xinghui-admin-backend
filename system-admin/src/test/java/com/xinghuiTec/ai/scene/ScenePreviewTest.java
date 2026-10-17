package com.xinghuiTec.ai.scene;

import org.junit.jupiter.api.Test;
import java.util.Map;
import static org.junit.jupiter.api.Assertions.*;

class ScenePreviewTest {
    @Test void originalPromptRoleMatchesCompiledUserMessageAndPreviewShowsOnlyAuthorizedBusinessValues()throws Exception{
        try(var fixture=new SceneTestSupport()){
            String prompt="Read {{record_subject}}";
            var scene=fixture.save(fixture.create(true),prompt,true);
            var preview=fixture.service.preview(scene.detail().scene().sceneKey(),new SceneDtos.PreviewInput(1,
                    new SceneDtos.BusinessRef("lowcode.record","1:11"),Map.of("user_request","private-preview-input")),fixture.actor);
            assertEquals(1,preview.originalMessages().size());assertEquals("USER",preview.originalMessages().get(0).role());
            assertEquals(prompt,preview.originalMessages().get(0).content());
            assertEquals("SYSTEM",preview.renderedMessages().get(0).role());assertEquals("USER",preview.renderedMessages().get(1).role());
            assertTrue(preview.renderedMessages().stream().anyMatch(m->m.content().contains("visible subject")));
            assertEquals("private-preview-input",preview.inputSummary().get("user_request"));assertFalse(preview.resourceSummary().isEmpty());
            String serialized=fixture.mapper.writeValueAsString(preview);
            assertTrue(serialized.contains("visible subject"));assertTrue(serialized.contains("private-preview-input"));assertFalse(serialized.contains("must-never-enter-model"));
            assertEquals(0,fixture.provider.calls.get());assertEquals(0,fixture.jdbc.queryForObject("SELECT COUNT(*) FROM sys_ai_request",Integer.class));
        }
    }
}
