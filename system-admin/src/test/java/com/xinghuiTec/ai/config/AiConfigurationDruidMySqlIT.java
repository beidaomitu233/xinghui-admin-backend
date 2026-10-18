package com.xinghuiTec.ai.config;

import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.JdbcTemplate;
import static org.junit.jupiter.api.Assertions.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

/** Runs after ReleaseServicesIT against its migrated disposable MySQL container. No conditional skip. */
class AiConfigurationDruidMySqlIT {
    @Test void migratedCatalogReceiptSurvivesServiceRestartAndBecomesStaleAfterKeyRotation()throws Exception {
        assertEquals("true",System.getenv("CI"),"Only the disposable CI services job may run this test");
        String url=System.getenv("DB_URL");assertNotNull(url);assertTrue(url.startsWith("jdbc:mysql://127.0.0.1:3306/xinghui_oss04_ci?"));
        assertEquals("oss04_ci",System.getenv("DB_USERNAME"));String password=System.getenv("DB_PASSWORD");assertNotNull(password);
        var upstream=new AiModelCatalogTest();upstream.start();
        try(var source=AiConfigurationDruidHttpTest.dataSource(url,"oss04_ci",password)) {
            source.setDriverClassName("com.mysql.cj.jdbc.Driver");
            var jdbc=new JdbcTemplate(source);assertEquals("xinghui_oss04_ci",jdbc.queryForObject("SELECT DATABASE()",String.class));
            assertTrue(jdbc.queryForObject("SELECT VERSION()",String.class).startsWith(System.getenv("OSS04_MYSQL_SERIES")+"."));
            assertEquals(1,jdbc.queryForObject("SELECT COUNT(*) FROM flyway_schema_history WHERE version='20261018.249' AND success=1",Integer.class));
            var manager=new org.springframework.jdbc.datasource.DataSourceTransactionManager(source);var tx=new org.springframework.transaction.support.TransactionTemplate(manager);
            var factory=new org.springframework.beans.factory.support.DefaultListableBeanFactory();
            factory.registerSingleton("references",new com.xinghuiTec.ai.contract.AiApplicationReferences(){
                public java.util.List<ApplicationReference> modelReferences(com.xinghuiTec.ai.contract.AiTypes.ModelVersionRef ref){return java.util.List.of();}
                public java.util.List<ApplicationReference> templateReferences(com.xinghuiTec.ai.contract.AiTypes.TemplateVersionRef ref){return java.util.List.of();}
                public java.util.List<ApplicationReference> resourceReferences(com.xinghuiTec.ai.contract.AiTypes.ResourceVersionRef ref){return java.util.List.of();}
                public <T>T withReferenceGuard(java.util.function.Supplier<T> action){return tx.execute(status->action.get());}
            });
            var json=new com.fasterxml.jackson.databind.ObjectMapper().findAndRegisterModules();
            var configs=new AiConfigurationService(jdbc,json,new com.xinghuiTec.config.SecretCipher(()->java.util.Base64.getEncoder().encodeToString(new byte[32])),new com.xinghuiTec.ai.model.AiModelConfigRules(),tx,factory.getBeanProvider(com.xinghuiTec.ai.contract.AiApplicationReferences.class),null,null);
            var catalogs=new AiModelCatalogService(configs,upstream.transport,new AiModelCatalogStore(jdbc,manager));
            var actor=new com.xinghuiTec.ai.contract.AiTypes.TrustedActor("1800000000000000249","000000",java.util.Set.of("ai:config:add","ai:config:edit","ai:config:query"),null);
            Long ownedConfig=null;String ownedScan=null;String nonce=java.util.UUID.randomUUID().toString();
            try {
                var scan=catalogs.draftScan(new AiModelCatalogDtos.DraftScan("openrouter",AiModelCatalogTest.KEY,"",1,20),actor);ownedScan=scan.catalogScanId();
                var view=configs.create(new AiConfigurationDtos.Create(new AiConfigurationDtos.DraftChange(0,"catalog-"+nonce,"ci","openrouter",OpenRouterTransport.ENDPOINT,"OPENAI_CHAT",15,
                        java.util.List.of(new AiConfigurationDtos.ModelInput(null,"vendor/new-model")),java.util.List.of(new AiConfigurationDtos.KeyInput(null,"Native","DB",null,AiModelCatalogTest.KEY))),null));ownedConfig=Long.parseLong(view.id());
                var key=view.draft().keys().get(0);catalogs.attach(new AiModelCatalogDtos.Attach(view.id(),key.keyRef(),view.revision(),scan.catalogScanId()),actor);
                int calls=upstream.calls.get();catalogs=new AiModelCatalogService(configs,upstream.transport,new AiModelCatalogStore(jdbc,manager));
                var restored=catalogs.stored(view.id(),null,"",1,20);assertEquals(AiModelCatalogDtos.CatalogState.FETCHED,restored.state());assertEquals(2,restored.catalog().total());assertEquals(scan.refreshedAt(),restored.catalog().refreshedAt());assertEquals(calls,upstream.calls.get());
                String wire=json.writeValueAsString(restored);assertFalse(wire.contains(AiModelCatalogTest.KEY));assertFalse(wire.contains("credentialFingerprint"));
                var changed=configs.save(ownedConfig,new AiConfigurationDtos.DraftChange(view.revision(),view.name(),view.environment(),view.providerCode(),view.draft().endpointUrl(),"OPENAI_CHAT",15,
                        view.draft().models().stream().map(model->new AiConfigurationDtos.ModelInput(model.modelRef(),model.modelCode())).toList(),java.util.List.of(new AiConfigurationDtos.KeyInput(key.keyRef(),key.label(),"DB",null,"replacement-native-controlled-key"))));
                var stale=catalogs.stored(changed.id(),null,"",1,20);assertEquals(AiModelCatalogDtos.CatalogState.STALE,stale.state());assertEquals(2,stale.catalog().total());assertFalse(stale.sources().get(0).current());
                assertNull(changed.activeVersion());assertTrue(changed.activePairs().isEmpty());assertEquals(calls,upstream.calls.get());
            } finally {
                if(ownedConfig!=null){
                    jdbc.update("DELETE FROM sys_ai_channel_catalog_binding WHERE config_id=?",ownedConfig);
                    jdbc.update("DELETE FROM sys_ai_configuration_version WHERE config_id=?",ownedConfig);
                    jdbc.update("DELETE FROM sys_ai_configuration_key_material WHERE key_ref IN (SELECT key_ref FROM sys_ai_configuration_key WHERE config_id=?)",ownedConfig);
                    jdbc.update("DELETE FROM sys_ai_configuration_key WHERE config_id=?",ownedConfig);
                    jdbc.update("DELETE FROM sys_ai_configuration_model WHERE config_id=?",ownedConfig);
                    jdbc.update("DELETE FROM sys_ai_configuration WHERE config_id=? AND config_name=?",ownedConfig,"catalog-"+nonce);
                }
                if(ownedScan!=null)jdbc.update("DELETE FROM sys_ai_channel_catalog_scan WHERE scan_id=?",ownedScan);
            }
        } finally {upstream.stop();}
    }
    @Test void migratedCiSchemaSupportsEmptyAndFilteredListsThroughRealMysqlWall() throws Exception {
        assertEquals("true",System.getenv("CI"),"Only the disposable CI services job may run this test");
        String url=System.getenv("DB_URL");
        assertNotNull(url,"Missing isolated DB_URL");
        assertTrue(url.startsWith("jdbc:mysql://127.0.0.1:3306/xinghui_oss04_ci?"),"Unexpected CI database target");
        assertEquals("oss04_ci",System.getenv("DB_USERNAME"));
        String password=System.getenv("DB_PASSWORD"); assertNotNull(password,"Missing isolated DB_PASSWORD");
        try(var source=AiConfigurationDruidHttpTest.dataSource(url,"oss04_ci",password)) {
            source.setDefaultReadOnly(true); source.setDriverClassName("com.mysql.cj.jdbc.Driver");
            var jdbc=new JdbcTemplate(source);
            assertEquals("xinghui_oss04_ci",jdbc.queryForObject("SELECT DATABASE()",String.class));
            String version=jdbc.queryForObject("SELECT VERSION()",String.class);
            assertTrue(version.startsWith(System.getenv("OSS04_MYSQL_SERIES")+"."),"Wrong MySQL matrix series");
            var blocked=assertThrows(org.springframework.jdbc.UncategorizedSQLException.class,()->jdbc.queryForObject("SELECT COUNT(*) FROM sys_ai_configuration WHERE 1=1",Long.class));
            assertTrue(blocked.getMostSpecificCause().getMessage().toLowerCase(java.util.Locale.ROOT).contains("sql injection violation"));
            var mvc=new AiConfigurationDruidHttpTest().http(source);
            mvc.perform(get("/system/ai/configurations").param("name","").param("providerCode","").param("environment","").param("status","").param("pageNum","1").param("pageSize","10"))
                    .andExpect(status().isOk()).andExpect(jsonPath("$.code").value(200)).andExpect(jsonPath("$.data.records").isArray());
            mvc.perform(get("/system/ai/configurations").param("name","ci-absent-configuration").param("providerCode","openrouter").param("environment","ci").param("status","DRAFT").param("pageNum","2").param("pageSize","3"))
                    .andExpect(status().isOk()).andExpect(jsonPath("$.data.records").isEmpty()).andExpect(jsonPath("$.data.pageNum").value(2));
            mvc.perform(get("/system/ai/configurations").param("pageNum","invalid"))
                    .andExpect(status().isBadRequest()).andExpect(jsonPath("$.code").value(202));
        }
    }
}
