package com.xinghuiTec.ai.model;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.xinghuiTec.exception.ServiceException;
import com.xinghuiTec.ai.model.AiModelConfigService.CreateRequest;
import com.xinghuiTec.ai.model.AiModelConfigService.UpdateRequest;
import com.xinghuiTec.ai.model.AiModelConfigService.StatusRequest;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DriverManagerDataSource;

import static org.junit.jupiter.api.Assertions.*;

class AiModelConfigServiceTest {
    private JdbcTemplate jdbc;
    private AiModelConfigService service;

    @BeforeEach
    void setUp() {
        var source = new DriverManagerDataSource(
                "jdbc:h2:mem:ai_model_" + java.util.UUID.randomUUID() + ";MODE=MySQL;DB_CLOSE_DELAY=-1", "sa", "");
        jdbc = new JdbcTemplate(source);
        jdbc.execute("""
                CREATE TABLE sys_ai_model_config (
                  model_id BIGINT AUTO_INCREMENT PRIMARY KEY,
                  provider_code VARCHAR(64) NOT NULL,
                  model_code VARCHAR(128) NOT NULL,
                  endpoint_url VARCHAR(2048) NOT NULL,
                  credential_env_name VARCHAR(128),
                  credential_mode VARCHAR(16) NOT NULL DEFAULT 'ENV',
                  credential_ciphertext TEXT,
                  timeout_seconds SMALLINT NOT NULL,
                  enabled BOOLEAN NOT NULL,
                  revision INT NOT NULL DEFAULT 0,
                  create_time TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP,
                  update_time TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP,
                  CONSTRAINT uk_ai_model_provider_code UNIQUE(provider_code, model_code)
                )
                """);
        service = new AiModelConfigService(jdbc, new AiModelConfigRules());
    }

    @Test
    void createsListsFiltersUpdatesAndReturnsOnlyTheReferenceName() throws Exception {
        long first = service.create(request("openai", "gpt-4.1-mini", "OPENAI_API_KEY", true));
        long second = service.create(request("anthropic", "claude-3-5-sonnet", null, true));

        var page = service.page(1, 1, null, null, true);
        assertEquals(2, page.total());
        assertEquals(1, page.records().size());
        assertEquals(second, page.records().get(0).id());
        assertEquals("OPENAI_API_KEY", service.page(1, 10, "openai", "gpt-4.1-mini", true)
                .records().get(0).credentialEnvName());
        assertEquals(0, service.page(1, 10, "openai", null, false).total());

        String json = new ObjectMapper().findAndRegisterModules().writeValueAsString(service.get(first));
        assertTrue(json.contains("OPENAI_API_KEY"));
        assertFalse(json.contains("apiKey"));
        assertFalse(json.contains("secretValue"));

        var updated = service.update(first, new UpdateRequest("openai", "gpt-4.1-mini",
                "https://api.example.com/v2", "OPENAI_API_KEY", 45, true, 0));
        assertEquals(1, updated.revision());
        assertEquals("https://api.example.com/v2", updated.endpointUrl());
        assertFalse(service.setEnabled(first, new StatusRequest(false, 1)).enabled());
    }

    @Test
    void rejectsDuplicateKeyBadPagingAndStaleUpdates() {
        long id = service.create(request("openai", "gpt-4.1-mini", null, true));
        assertThrows(ServiceException.class,
                () -> service.create(request("openai", "gpt-4.1-mini", null, false)));
        assertThrows(ServiceException.class, () -> service.page(0, 20, null, null, null));
        assertThrows(ServiceException.class, () -> service.page(1, 101, null, null, null));
        assertThrows(ServiceException.class, () -> service.page(1, 20, "OPENAI", null, null));

        service.update(id, new UpdateRequest("openai", "gpt-4.1-mini", "https://api.example.com/v2",
                null, 30, true, 0));
        assertThrows(ServiceException.class, () -> service.setEnabled(id, new StatusRequest(false, 0)));
        assertEquals(1, service.get(id).revision());
    }

    @Test
    void enabledModelsCannotBeDeletedAndFutureSceneReferencesAreDatabaseRestricted() {
        long enabledId = service.create(request("openai", "enabled-model", null, true));
        assertThrows(ServiceException.class, () -> service.delete(enabledId, 0));

        long disabledId = service.create(request("openai", "disabled-model", null, false));
        jdbc.execute("CREATE TABLE ai02_scene_model_ref (model_id BIGINT NOT NULL, "
                + "CONSTRAINT fk_ai02_scene_model FOREIGN KEY(model_id) "
                + "REFERENCES sys_ai_model_config(model_id) ON DELETE RESTRICT)");
        jdbc.update("INSERT INTO ai02_scene_model_ref(model_id) VALUES (?)", disabledId);
        ServiceException error = assertThrows(ServiceException.class, () -> service.delete(disabledId, 0));
        assertTrue(error.getMessage().contains("已被场景引用"));
        assertEquals(disabledId, service.get(disabledId).id());
        assertTrue(jdbc.queryForObject("SELECT COUNT(*) FROM ai02_scene_model_ref", Integer.class) == 1);
    }

    @Test
    void disabledUnreferencedModelCanBeDeletedWithMatchingRevision() {
        long id = service.create(request("openai", "disabled-model", null, false));
        service.delete(id, 0);
        assertThrows(ServiceException.class, () -> service.get(id));
        assertThrows(ServiceException.class, () -> service.delete(-1, 0));
    }

    @Test
    void databaseCredentialsAreAuthenticatedMaskedAndExplicitlyCleared() throws Exception {
        long id=service.create(request("openrouter","unit/model:free",null,true));
        var cipher=new com.xinghuiTec.config.SecretCipher(()->"AAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAA=");
        var credentials=new AiModelCredentialService(jdbc,cipher,service);
        var view=credentials.save(id,new AiModelCredentialService.Change("DB","unit-key",false,0));
        assertTrue(view.credentialConfigured());
        assertEquals("DB",view.credentialMode());
        assertFalse(new ObjectMapper().findAndRegisterModules().writeValueAsString(view).contains("unit-key"));
        String encrypted=jdbc.queryForObject("SELECT credential_ciphertext FROM sys_ai_model_config WHERE model_id=?",String.class,id);
        assertFalse(encrypted.contains("unit-key"));
        assertThrows(ServiceException.class,()->cipher.decrypt("ai:model:"+(id+1),encrypted));
        credentials.save(id,new AiModelCredentialService.Change("DB","",false,1));
        assertEquals("unit-key",credentials.resolve(id));
        assertThrows(ServiceException.class,()->credentials.save(id,new AiModelCredentialService.Change("DB","stale",false,1)));
        credentials.save(id,new AiModelCredentialService.Change("ENV",null,false,2));
        assertThrows(ServiceException.class,()->credentials.resolve(id));
        credentials.save(id,new AiModelCredentialService.Change("DB",null,false,3));
        assertEquals("unit-key",credentials.resolve(id));
        credentials.save(id,new AiModelCredentialService.Change("DB",null,true,4));
        assertFalse(service.get(id).credentialConfigured());
        assertThrows(ServiceException.class,()->credentials.resolve(id));
    }

    private CreateRequest request(String provider, String model, String credentialEnvName, boolean enabled) {
        return new CreateRequest(provider, model, "https://api.example.com/v1", credentialEnvName, 30, enabled);
    }
}
