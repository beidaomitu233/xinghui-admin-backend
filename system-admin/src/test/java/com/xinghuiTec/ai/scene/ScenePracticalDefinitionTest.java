package com.xinghuiTec.ai.scene;

import com.xinghuiTec.ai.contract.AiSceneDataRuntime.*;
import com.xinghuiTec.ai.contract.AiTypes.TrustedActor;
import org.junit.jupiter.api.Test;
import java.util.*;
import static org.junit.jupiter.api.Assertions.*;

class ScenePracticalDefinitionTest {
    @Test void historicalOutputInstructionsMergeExactlyOnceWithoutOverwritingOldSnapshots() throws Exception {
        try (var f = new SceneTestSupport()) {
            var scene = f.create(false);
            var input = f.definition("Prompt", false);
            String instructions = "  保留原指令\n第二行  ";
            var saved = f.management.save(scene.scene().sceneKey(), new SceneDtos.Save(0, UUID.randomUUID().toString(),
                    new SceneDtos.Definition(input.name(), input.purpose(), input.promptText(), instructions, input.dataObjects(), input.modelPolicy())), f.actor);
            String expected = "Prompt\n\n输出要求：\n" + instructions;
            assertEquals(expected, saved.detail().definition().promptText());
            assertEquals("", saved.detail().definition().outputRequirement());
            var first = f.scenes.version(saved.detail().scene().currentVersionId());
            assertEquals("", first.compiled().editor().outputInstructions());
            var again = f.management.save(scene.scene().sceneKey(), new SceneDtos.Save(1, UUID.randomUUID().toString(), saved.detail().definition()), f.actor);
            assertEquals(expected, again.detail().definition().promptText());
            assertEquals(expected, f.scenes.version(first.id()).definition().promptText());
            assertEquals(0, f.provider.calls.get());
        }
    }

    @Test void bindingSnapshotIsSavedAndActualTypedPreviewReadsCurrentAuthorizedData() throws Exception {
        try (var f = new SceneTestSupport()) {
            f.jdbc.update("INSERT INTO sys_user VALUES(201,'000000',1,0)");
            f.jdbc.execute("CREATE TABLE business_report(id BIGINT PRIMARY KEY,amount DECIMAL(12,2),memo VARCHAR(80),tenant_id VARCHAR(64),is_deleted INT DEFAULT 0)");
            f.jdbc.update("INSERT INTO business_report VALUES(1,12.50,NULL,'000000',0)");
            var admin = new TrustedActor("201", "000000", Set.of("ai:scene:invoke", "ai:scene:query", "ai:scene:edit", "ai:data:database:read"), null);
            var scene = f.management.create(new SceneDtos.Create("Report", "", SceneDtos.Kind.GENERAL, null, UUID.randomUUID().toString()), admin);
            var binding = new DataBinding("报表", "报表", "db.business_report", ReadMode.TABLE, List.of(), List.of(), 10, null, null);
            var saved = f.management.save(scene.scene().sceneKey(), new SceneDtos.Save(0, UUID.randomUUID().toString(),
                    new SceneDtos.Definition("Report", "", "Read {{报表}}", "", List.of(), f.definition("unused", false).modelPolicy(), List.of(binding))), admin);
            assertEquals(1, saved.detail().definition().dataBindings().size());
            assertEquals(1, f.scenes.version(saved.detail().scene().currentVersionId()).compiled().bindings().size());
            f.jdbc.update("UPDATE business_report SET amount=23.75 WHERE id=1");
            f.jdbc.execute("ALTER TABLE business_report ADD future_column VARCHAR(80) DEFAULT 'newly-added-value'");
            var preview = f.service.preview(scene.scene().sceneKey(), new SceneDtos.PreviewInput(1, null, Map.of()), admin);
            var rows = (List<?>) preview.dataValues().get("报表");
            assertEquals(1, rows.size());
            var row = (Map<?, ?>) rows.get(0);
            assertTrue(row.get("amount") instanceof Number);
            assertEquals("23.75", row.get("amount").toString());
            assertNull(row.get("memo"));
            assertFalse(row.containsKey("future_column"));
            assertFalse(preview.renderedMessages().stream().anyMatch(m -> m.content().contains("newly-added-value")));
            assertEquals(1, preview.reads().get(0).rowCount());
            assertTrue(preview.renderedMessages().stream().anyMatch(m -> m.content().contains("23.75")));
            assertEquals(0, f.provider.calls.get());
        }
    }
}
