package com.xinghuiTec.ai.prompt;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.xinghuiTec.ai.prompt.AiPromptSceneRules.VariableDefinition;
import com.xinghuiTec.ai.prompt.AiPromptSceneRules.VariableType;
import com.xinghuiTec.exception.ServiceException;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DriverManagerDataSource;

import java.util.List;
import java.util.Map;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.*;

class AiPromptSceneServiceTest {
    private JdbcTemplate jdbc;
    private AiPromptSceneService service;
    private long activeModel;

    @BeforeEach
    void setUp() {
        var source = new DriverManagerDataSource(
                "jdbc:h2:mem:ai_prompt_" + UUID.randomUUID() + ";MODE=MySQL;DB_CLOSE_DELAY=-1", "sa", "");
        jdbc = new JdbcTemplate(source);
        jdbc.execute("""
                CREATE TABLE sys_ai_model_config (
                  model_id BIGINT AUTO_INCREMENT PRIMARY KEY,
                  provider_code VARCHAR(64) NOT NULL,
                  model_code VARCHAR(128) NOT NULL,
                  enabled BOOLEAN NOT NULL,
                  revision INT NOT NULL DEFAULT 0,
                  CONSTRAINT uk_model UNIQUE(provider_code,model_code)
                )
                """);
        jdbc.execute("""
                CREATE TABLE sys_ai_prompt_scene (
                  scene_id BIGINT AUTO_INCREMENT PRIMARY KEY,
                  app_key VARCHAR(64) NOT NULL,
                  scene_key VARCHAR(96) NOT NULL,
                  draft_model_id BIGINT NOT NULL,
                  draft_prompt CLOB NOT NULL,
                  draft_variables_json CLOB NOT NULL,
                  draft_revision INT NOT NULL DEFAULT 1,
                  published_model_id BIGINT,
                  published_prompt CLOB,
                  published_variables_json CLOB,
                  published_revision INT,
                  enabled BOOLEAN NOT NULL DEFAULT FALSE,
                  revision INT NOT NULL DEFAULT 0,
                  create_time TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP,
                  update_time TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP,
                  CONSTRAINT uk_app_scene UNIQUE(app_key,scene_key),
                  CONSTRAINT fk_draft_model FOREIGN KEY(draft_model_id) REFERENCES sys_ai_model_config(model_id),
                  CONSTRAINT fk_published_model FOREIGN KEY(published_model_id) REFERENCES sys_ai_model_config(model_id)
                )
                """);
        jdbc.update("INSERT INTO sys_ai_model_config(provider_code,model_code,enabled) VALUES ('openai','active',TRUE)");
        activeModel = jdbc.queryForObject("SELECT model_id FROM sys_ai_model_config", Long.class);
        jdbc.update("INSERT INTO sys_ai_model_config(provider_code,model_code,enabled) VALUES ('test','disabled',FALSE)");
        service = new AiPromptSceneService(jdbc, new ObjectMapper(), new AiPromptSceneRules());
    }

    @Test
    void draftEditsNeverChangePublishedSnapshotUntilASecondPublish() {
        long id = service.create(create("sample_alpha", "summarize", activeModel,
                "Summarize {{recordName}}", List.of(nameVariable("Sample record"))));
        var created = service.get(id);
        assertFalse(created.enabled());
        assertFalse(created.published());
        assertNull(created.publishedRevision());
        assertEquals(1, created.draftRevision());

        var published = service.publish(id, new AiPromptSceneService.RevisionRequest(created.revision()));
        assertTrue(published.published());
        assertEquals(1, published.publishedRevision());
        assertEquals(published.revision(), 1);
        var enabled = service.setEnabled(id, new AiPromptSceneService.StatusRequest(true, published.revision()));
        assertTrue(enabled.enabled());
        assertEquals("Summarize {{recordName}}",
                service.publishedForUse("sample_alpha", "summarize").promptTemplate());

        var edited = service.updateDraft(id, new AiPromptSceneService.DraftRequest(activeModel,
                "Create a short note about {{recordName}}", List.of(nameVariable("Neutral sample")), enabled.revision()));
        assertEquals(2, edited.draftRevision());
        assertEquals(1, edited.publishedRevision());
        assertTrue(edited.published());
        assertEquals("Summarize {{recordName}}", edited.publishedPrompt());
        assertEquals("Summarize {{recordName}}",
                service.publishedForUse("sample_alpha", "summarize").promptTemplate());

        var republished = service.publish(id, new AiPromptSceneService.RevisionRequest(edited.revision()));
        assertEquals(2, republished.publishedRevision());
        assertEquals("Create a short note about {{recordName}}", republished.publishedPrompt());
        assertEquals("Create a short note about {{recordName}}",
                service.publishedForUse("sample_alpha", "summarize").promptTemplate());
    }

    @Test
    void differentApplicationsKeepSeparateKeysAndListingFiltersWork() {
        long first = service.create(create("sample_alpha", "summarize", activeModel,
                "{{recordName}}", List.of(nameVariable("Alpha"))));
        service.create(create("sample_alpha", "classify", activeModel,
                "{{recordName}}", List.of(nameVariable("Alpha"))));
        service.create(create("sample_beta", "summarize", activeModel,
                "{{recordName}}", List.of(nameVariable("Beta"))));

        var all = service.page(1, 20, null, null, null, null);
        assertEquals(3, all.total());
        assertEquals(2, service.page(1, 20, "sample_alpha", null, null, null).total());
        assertEquals(first, service.page(1, 10, "sample_alpha", "summarize", false, false)
                .records().get(0).id());
        assertThrows(ServiceException.class, () -> service.create(create("sample_alpha", "summarize",
                activeModel, "{{recordName}}", List.of(nameVariable("Duplicate")))));
    }

    @Test
    void publishAndPreviewRejectUnknownVariablesAndStaleRevisions() {
        service.create(create("sample_beta", "other_scene", activeModel,
                "Use {{otherSceneValue}}", List.of(new VariableDefinition("otherSceneValue", VariableType.STRING,
                        "Value declared only in another app", false, "Neutral"))));
        long id = service.create(create("sample_alpha", "summarize", activeModel,
                "Private draft sentinel {{otherSceneValue}}", List.of(nameVariable("Neutral"))));
        var draft = service.get(id);
        ServiceException badPublish = assertThrows(ServiceException.class,
                () -> service.publish(id, new AiPromptSceneService.RevisionRequest(draft.revision())));
        assertTrue(badPublish.getMessage().contains("未在当前场景声明"));
        assertFalse(badPublish.getMessage().contains("Private draft sentinel"));
        ServiceException badPreview = assertThrows(ServiceException.class,
                () -> service.preview(id, new AiPromptSceneService.PreviewRequest(draft.revision(), Map.of())));
        assertTrue(badPreview.getMessage().contains("未在当前场景声明"));

        var corrected = service.updateDraft(id, new AiPromptSceneService.DraftRequest(activeModel,
                "Summarize {{recordName}}", List.of(nameVariable("Neutral")), draft.revision()));
        assertThrows(ServiceException.class, () -> service.publish(id,
                new AiPromptSceneService.RevisionRequest(draft.revision())));
        assertEquals("Summarize Custom sample", service.preview(id,
                new AiPromptSceneService.PreviewRequest(corrected.revision(), Map.of("recordName", "Custom sample")))
                .prompt());
    }

    @Test
    void onlyEnabledModelsCanBeSelectedAndDisablingPublishedModelBlocksUse() {
        long disabledModel = jdbc.queryForObject(
                "SELECT model_id FROM sys_ai_model_config WHERE model_code='disabled'", Long.class);
        assertThrows(ServiceException.class, () -> service.create(create("sample_alpha", "disabled_model",
                disabledModel, "{{recordName}}", List.of(nameVariable("Neutral")))));

        long id = service.create(create("sample_alpha", "summarize", activeModel,
                "Summarize {{recordName}}", List.of(nameVariable("Neutral"))));
        var created = service.get(id);
        var published = service.publish(id, new AiPromptSceneService.RevisionRequest(created.revision()));
        var enabled = service.setEnabled(id, new AiPromptSceneService.StatusRequest(true, published.revision()));

        jdbc.update("UPDATE sys_ai_model_config SET enabled=FALSE WHERE model_id=?", activeModel);
        var summary = service.page(1, 10, "sample_alpha", "summarize", null, true).records().get(0);
        assertFalse(summary.publishedModelEnabled());
        assertThrows(ServiceException.class, () -> service.publishedForUse("sample_alpha", "summarize"));
        assertThrows(ServiceException.class, () -> service.preview(id,
                new AiPromptSceneService.PreviewRequest(enabled.revision(), Map.of())));
        assertThrows(ServiceException.class, () -> service.setEnabled(id,
                new AiPromptSceneService.StatusRequest(true, enabled.revision())));
    }

    @Test
    void activationNeedsPublicationAndDeletionNeedsDisabledSceneAndCurrentRevision() {
        long id = service.create(create("sample_alpha", "summarize", activeModel,
                "{{recordName}}", List.of(nameVariable("Neutral"))));
        assertThrows(ServiceException.class,
                () -> service.setEnabled(id, new AiPromptSceneService.StatusRequest(true, 0)));
        var published = service.publish(id, new AiPromptSceneService.RevisionRequest(0));
        var enabled = service.setEnabled(id, new AiPromptSceneService.StatusRequest(true, published.revision()));
        assertThrows(ServiceException.class, () -> service.delete(id, enabled.revision()));
        var disabled = service.setEnabled(id, new AiPromptSceneService.StatusRequest(false, enabled.revision()));
        assertThrows(ServiceException.class, () -> service.delete(id, enabled.revision()));
        service.delete(id, disabled.revision());
        assertThrows(ServiceException.class, () -> service.get(id));
    }

    private AiPromptSceneService.CreateRequest create(String appKey, String sceneKey, long modelId,
                                                       String prompt, List<VariableDefinition> variables) {
        return new AiPromptSceneService.CreateRequest(appKey, sceneKey, modelId, prompt, variables);
    }

    private VariableDefinition nameVariable(String example) {
        return new VariableDefinition("recordName", VariableType.STRING, "Neutral record name", false, example);
    }
}
