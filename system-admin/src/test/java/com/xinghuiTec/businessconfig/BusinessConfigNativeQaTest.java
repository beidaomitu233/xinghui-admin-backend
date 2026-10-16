package com.xinghuiTec.businessconfig;

import com.xinghuiTec.ai.model.*;
import com.xinghuiTec.config.SecretCipher;
import org.flywaydb.core.Flyway;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import static org.junit.jupiter.api.Assertions.*;

/** Explicit opt-in. Assigned empty schema only; never contacts a provider. */
@EnabledIfEnvironmentVariable(named="CFG02_NATIVE_ENABLED",matches="true")
class BusinessConfigNativeQaTest {
    @Test void migrateAssignedEmptySchemaAndImportEncryptedCredentials() throws Exception {
        String keyFile=System.getenv("CFG02_PROVIDER_KEY_FILE");
        assertNotNull(keyFile,"CFG02_PROVIDER_KEY_FILE is required before connecting to QA resources");
        assertFalse(keyFile.isBlank(),"CFG02_PROVIDER_KEY_FILE is required");
        String key=Files.readString(Path.of(keyFile)).trim();
        assertFalse(key.isBlank(),"Key file must contain a key");
        var source=new DriverManagerDataSource("jdbc:mysql://127.0.0.1:13318/cfg02_empty_20261012?useSSL=false&allowPublicKeyRetrieval=true&serverTimezone=Asia/Shanghai","qa_ci",System.getenv("CFG02_DB_PASSWORD"));
        var jdbc=new JdbcTemplate(source);
        assertEquals("cfg02_empty_20261012",jdbc.queryForObject("SELECT DATABASE()",String.class));
        assertEquals(13318,jdbc.queryForObject("SELECT @@port",Integer.class));
        var flyway=Flyway.configure().dataSource(source).locations("classpath:db/migration").baselineOnMigrate(false).cleanDisabled(true).load();
        flyway.migrate();flyway.validate();
        assertEquals(19,jdbc.queryForObject("SELECT COUNT(*) FROM flyway_schema_history WHERE success=1",Integer.class));
        assertEquals(0,jdbc.queryForObject("SELECT COUNT(*) FROM sys_menu WHERE perms IN ('system:wechat:query','system:wechat:edit')",Integer.class));
        assertEquals(3,jdbc.queryForObject("SELECT COUNT(*) FROM sys_menu WHERE perms IN ('system:businessConfig:query','system:businessConfig:edit','ai:model:test')",Integer.class));
        var cipher=new SecretCipher();
        assertTrue(cipher.ready(),"Private deployment master key must be loaded");
        var models=new AiModelConfigService(jdbc,new AiModelConfigRules());
        var credentials=new AiModelCredentialService(jdbc,cipher,models);
        for(String code:List.of("poolside/laguna-s-2.1:free","nvidia/nemotron-3.5-lightning:free")) {
            var existing=jdbc.query("SELECT model_id FROM sys_ai_model_config WHERE provider_code='openrouter' AND model_code=?",(rs,row)->rs.getLong(1),code);
            long id=existing.isEmpty()?models.create(new AiModelConfigService.CreateRequest("openrouter",code,"https://openrouter.ai/api/v1",null,60,true)):existing.get(0);
            credentials.save(id,new AiModelCredentialService.Change("DB",key,false,models.get(id).revision()));
            String stored=jdbc.queryForObject("SELECT credential_ciphertext FROM sys_ai_model_config WHERE model_id=?",String.class,id);
            assertTrue(stored.startsWith("v1."));
            assertFalse(stored.contains(key),"Database must contain ciphertext only");
            assertTrue(models.get(id).credentialConfigured());
            var reopened=new AiModelCredentialService(new JdbcTemplate(source),new SecretCipher(),new AiModelConfigService(new JdbcTemplate(source),new AiModelConfigRules()));
            assertTrue(key.equals(reopened.resolve(id)),"Reopened service must decrypt saved credentials");
        }
        System.out.println("CFG02_NATIVE: migrations=19; encryptedModels=2; reopenedCredentialRead=PASS; providerRequests=0");
    }
}
