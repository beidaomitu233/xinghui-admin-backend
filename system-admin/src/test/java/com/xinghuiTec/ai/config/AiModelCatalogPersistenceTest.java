package com.xinghuiTec.ai.config;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.xinghuiTec.ai.contract.*;
import com.xinghuiTec.ai.contract.AiTypes.*;
import com.xinghuiTec.ai.model.AiModelConfigRules;
import com.xinghuiTec.config.SecretCipher;
import org.junit.jupiter.api.*;
import org.springframework.beans.factory.support.DefaultListableBeanFactory;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.*;
import org.springframework.transaction.support.TransactionTemplate;
import java.nio.charset.StandardCharsets;
import java.time.*;
import java.util.*;
import static com.xinghuiTec.ai.config.AiModelCatalogDtos.*;
import static org.junit.jupiter.api.Assertions.*;

/** Real encrypted configuration snapshots + H2 persistence + loopback directory. Never vendor proof. */
class AiModelCatalogPersistenceTest {
    AiModelCatalogTest upstream;JdbcTemplate jdbc;DataSourceTransactionManager manager;AiConfigurationService configs;AiModelCatalogStore store;AiModelCatalogService catalogs;
    final TrustedActor actor=new TrustedActor("101","000000",Set.of("ai:config:add","ai:config:edit","ai:config:query","ai:config:test"),null);
    @BeforeEach void setup()throws Exception {
        upstream=new AiModelCatalogTest();upstream.start();
        var source=new DriverManagerDataSource("jdbc:h2:mem:catalog_"+UUID.randomUUID()+";MODE=MySQL;DB_CLOSE_DELAY=-1","sa","");jdbc=new JdbcTemplate(source);manager=new DataSourceTransactionManager(source);
        apply("V20261016_180__ai_config_v04.sql",true);apply("V20261018_245__ai_key_health.sql",false);apply("V20261018_249__ai_channel_catalog_receipts.sql",false);
        var tx=new TransactionTemplate(manager);var factory=new DefaultListableBeanFactory();
        factory.registerSingleton("references",new AiApplicationReferences(){public List<ApplicationReference> modelReferences(ModelVersionRef ref){return List.of();}public List<ApplicationReference> templateReferences(TemplateVersionRef ref){return List.of();}public List<ApplicationReference> resourceReferences(ResourceVersionRef ref){return List.of();}public <T>T withReferenceGuard(java.util.function.Supplier<T> operation){return tx.execute(status->operation.get());}});
        configs=new AiConfigurationService(jdbc,new ObjectMapper().findAndRegisterModules(),new SecretCipher(()->Base64.getEncoder().encodeToString(new byte[32])),new AiModelConfigRules(),tx,factory.getBeanProvider(AiApplicationReferences.class),null,null);
        store=new AiModelCatalogStore(jdbc,manager);catalogs=new AiModelCatalogService(configs,upstream.transport,store);
    }
    void apply(String name,boolean truncate)throws Exception {
        String sql;try(var input=getClass().getResourceAsStream("/db/migration/"+name)){sql=new String(Objects.requireNonNull(input).readAllBytes(),StandardCharsets.UTF_8);}
        if(truncate)sql=sql.substring(0,sql.indexOf("-- Existing configurations"));
        sql=sql.replaceAll("(?m)^--.*$","").replace(" COLLATE utf8mb4_bin","").replace(" ENGINE=InnoDB DEFAULT CHARSET=utf8mb4","");
        for(String statement:sql.split(";"))if(!statement.isBlank())jdbc.execute(statement);
    }
    @AfterEach void close(){if(upstream!=null)upstream.stop();if(jdbc!=null)jdbc.execute("SHUTDOWN");}
    AiConfigurationDtos.View channel(String provider,String...materials){
        var keys=new ArrayList<AiConfigurationDtos.KeyInput>();for(int i=0;i<materials.length;i++)keys.add(new AiConfigurationDtos.KeyInput(null,"Key "+i,"DB",null,materials[i]));
        return configs.create(new AiConfigurationDtos.Create(new AiConfigurationDtos.DraftChange(0,"Channel","test",provider,AiProviderPresets.require(provider).chatEndpoint(),"OPENAI_CHAT",15,List.of(new AiConfigurationDtos.ModelInput(null,"vendor/new-model")),keys),null));
    }
    Page draft(String provider,String secret){return catalogs.draftScan(new DraftScan(provider,secret,"",1,20),actor);}
    StoredCatalog attach(AiConfigurationDtos.View channel,int key,Page scan){return catalogs.attach(new Attach(channel.id(),channel.draft().keys().get(key).keyRef(),channel.revision(),scan.catalogScanId()),actor);}
    AiConfigurationDtos.View saveKey(AiConfigurationDtos.View channel,String replacement){
        var key=channel.draft().keys().get(0);
        return configs.save(Long.parseLong(channel.id()),new AiConfigurationDtos.DraftChange(channel.revision(),"Renamed","test",channel.providerCode(),channel.draft().endpointUrl(),"OPENAI_CHAT",15,
                channel.draft().models().stream().map(m->new AiConfigurationDtos.ModelInput(m.modelRef(),m.modelCode())).toList(),List.of(new AiConfigurationDtos.KeyInput(key.keyRef(),key.label(),key.mode(),key.envName(),replacement))));
    }
    @Test void draftReceiptSurvivesChannelSaveAndRestartWithoutNetworkOrCredentialEcho()throws Exception {
        var scan=draft("openrouter",AiModelCatalogTest.KEY);assertNotNull(scan.catalogScanId());
        var channel=channel("openrouter",AiModelCatalogTest.KEY);var attached=attach(channel,0,scan);assertEquals(CatalogState.FETCHED,attached.state());
        int network=upstream.calls.get();catalogs=new AiModelCatalogService(configs,upstream.transport,new AiModelCatalogStore(jdbc,manager));
        var loaded=catalogs.stored(channel.id(),null,"新模型",1,20);assertEquals(1,loaded.catalog().total());assertEquals(network,upstream.calls.get());
        assertEquals(scan.refreshedAt(),loaded.catalog().refreshedAt());assertFalse(loaded.catalog().generationVerified());assertTrue(loaded.sources().get(0).current());
        assertEquals("https://openrouter.ai/api/v1/models/user",loaded.sources().get(0).catalogEndpoint());
        String wire=new ObjectMapper().findAndRegisterModules().writeValueAsString(loaded);assertFalse(wire.contains(AiModelCatalogTest.KEY));assertFalse(wire.contains("credentialFingerprint"));
        assertFalse(jdbc.queryForList("SELECT * FROM sys_ai_channel_catalog_scan").toString().contains(AiModelCatalogTest.KEY));
        assertNull(configs.get(Long.parseLong(channel.id())).activeVersion());assertTrue(configs.get(Long.parseLong(channel.id())).activePairs().isEmpty());
    }
    @Test void savedScanAutomaticallyPersistsItsExactKeyAndNeverChangesModelAdmission(){
        var channel=channel("openai",AiModelCatalogTest.KEY);var key=channel.draft().keys().get(0);
        var result=catalogs.scan(new Scan(channel.id(),key.keyRef(),channel.revision(),"",1,20),actor);assertNotNull(result.catalogScanId());
        assertEquals(CatalogState.FETCHED,catalogs.stored(channel.id(),null,"",1,20).state());assertEquals(1,upstream.calls.get());
        assertTrue(configs.get(Long.parseLong(channel.id())).activePairs().isEmpty());
    }
    @Test void perKeyReceiptsMergeOnlyCurrentScopeAndExposePartialState(){
        var channel=channel("openrouter",AiModelCatalogTest.KEY,"second-controlled-key");
        upstream.body="{\"data\":[{\"id\":\"vendor/key-a-only\"}]}";
        var first=catalogs.scan(new Scan(channel.id(),channel.draft().keys().get(0).keyRef(),channel.revision(),"",1,20),actor);
        assertEquals(CatalogState.PARTIAL,catalogs.stored(channel.id(),null,"",1,20).state());
        upstream.body="{\"data\":[{\"id\":\"vendor/key-b-only\"}]}";
        catalogs.scan(new Scan(channel.id(),channel.draft().keys().get(1).keyRef(),channel.revision(),"",1,20),actor);
        var merged=catalogs.stored(channel.id(),null,"",1,20);assertEquals(CatalogState.FETCHED,merged.state());assertEquals(2,merged.sources().size());assertEquals(2,merged.catalog().total());
        var one=catalogs.stored(channel.id(),channel.draft().keys().get(0).keyRef(),"",1,20);assertEquals(1,one.catalog().total());assertEquals("vendor/key-a-only",one.catalog().records().get(0).code());
        assertNotNull(first.catalogScanId());assertEquals(2,upstream.calls.get());
    }
    @Test void opaqueTicketsCannotCrossActorsCredentialsProvidersOrRevision(){
        var scan=draft("openrouter",AiModelCatalogTest.KEY);var channel=channel("openrouter",AiModelCatalogTest.KEY);var key=channel.draft().keys().get(0);
        var other=new TrustedActor("102","000000",actor.authorities(),null);
        assertEquals(403,assertThrows(AiModelCatalogException.class,()->catalogs.attach(new Attach(channel.id(),key.keyRef(),channel.revision(),scan.catalogScanId()),other)).status());
        assertEquals(403,assertThrows(AiModelCatalogException.class,()->catalogs.savedScan(scan.catalogScanId(),other,"",1,20)).status());
        assertEquals(409,assertThrows(AiModelCatalogException.class,()->catalogs.attach(new Attach(channel.id(),key.keyRef(),channel.revision()+1,scan.catalogScanId()),actor)).status());
        var different=channel("openrouter","different-controlled-key");assertEquals(409,assertThrows(AiModelCatalogException.class,()->attach(different,0,scan)).status());
        var differentProvider=channel("deepseek",AiModelCatalogTest.KEY);assertEquals(409,assertThrows(AiModelCatalogException.class,()->attach(differentProvider,0,scan)).status());
        assertEquals(0,jdbc.queryForObject("SELECT COUNT(*) FROM sys_ai_channel_catalog_binding",Integer.class));
    }
    @Test void keyRotationMarksHistoricalMetadataStaleButRenameDoesNotInvalidateIt(){
        var scan=draft("openrouter",AiModelCatalogTest.KEY);var channel=channel("openrouter",AiModelCatalogTest.KEY);attach(channel,0,scan);
        channel=saveKey(channel,"");assertEquals(CatalogState.FETCHED,catalogs.stored(channel.id(),null,"",1,20).state());
        channel=saveKey(channel,"replacement-controlled-key");var loaded=catalogs.stored(channel.id(),null,"",1,20);
        assertEquals(CatalogState.STALE,loaded.state());assertEquals(2,loaded.catalog().total());assertFalse(loaded.sources().get(0).current());
        var changed=channel;assertEquals(409,assertThrows(AiModelCatalogException.class,()->attach(changed,0,scan)).status());assertEquals(1,upstream.calls.get());
    }
    @Test void receiptPagingAndBoundCatalogExpiryNeverNeedAnotherProviderRequest(){
        var scan=draft("openrouter",AiModelCatalogTest.KEY);int network=upstream.calls.get();
        assertEquals(1,catalogs.savedScan(scan.catalogScanId(),actor,"新模型",1,1).total());assertEquals(network,upstream.calls.get());
        var channel=channel("openrouter",AiModelCatalogTest.KEY);attach(channel,0,scan);
        assertThrows(org.springframework.dao.DataIntegrityViolationException.class,()->jdbc.update("DELETE FROM sys_ai_channel_catalog_scan WHERE scan_id=?",scan.catalogScanId()));
        var unbound=draft("openrouter",AiModelCatalogTest.KEY);
        jdbc.update("UPDATE sys_ai_channel_catalog_scan SET expires_at_ms=0");
        assertEquals(410,assertThrows(AiModelCatalogException.class,()->catalogs.savedScan(unbound.catalogScanId(),actor,"",1,20)).status());
        store.expireUnattached();assertEquals(1,jdbc.queryForObject("SELECT COUNT(*) FROM sys_ai_channel_catalog_scan",Integer.class));
        assertEquals(CatalogState.FETCHED,catalogs.stored(channel.id(),null,"",1,20).state());
    }
    @Test void channelRevisionChangingDuringNetworkFetchCannotPersistWrongScope(){
        var channel=channel("openrouter",AiModelCatalogTest.KEY);
        upstream.callback=()->jdbc.update("UPDATE sys_ai_configuration SET revision=revision+1 WHERE config_id=?",channel.id());
        var error=assertThrows(AiModelCatalogException.class,()->catalogs.scan(new Scan(channel.id(),channel.draft().keys().get(0).keyRef(),channel.revision(),"",1,20),actor));
        assertEquals("AI_MODEL_CATALOG:CONFIG_CHANGED",error.getMessage());assertEquals(0,jdbc.queryForObject("SELECT COUNT(*) FROM sys_ai_channel_catalog_scan",Integer.class));
    }
    @Test void notFetchedChannelIsExplicitAndReadsNeverInventDirectoryModels(){
        var channel=channel("siliconflow",AiModelCatalogTest.KEY);var loaded=catalogs.stored(channel.id(),null,"",1,20);
        assertEquals(CatalogState.NOT_FETCHED,loaded.state());assertEquals(List.of(),loaded.catalog().records());assertNull(loaded.catalog().refreshedAt());assertEquals(0,upstream.calls.get());
        assertEquals(404,assertThrows(AiModelCatalogException.class,()->catalogs.stored(channel.id(),"999999","",1,20)).status());
    }
}
