package com.xinghuiTec.ai.config;

import com.xinghuiTec.ai.contract.*;
import org.junit.jupiter.api.*;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.*;
import static com.xinghuiTec.ai.config.AiConfigurationDtos.*;
import static com.xinghuiTec.ai.contract.AiProviderRuntime.*;
import static com.xinghuiTec.ai.contract.AiTypes.*;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

/** Real encrypted configuration/admission and catalog binding. Metadata alone never admits a key. */
class AiToolCapabilitiesTest {
    AiConfigurationServiceTest fixture;AiModelCatalogStore store;AiToolCapabilities capabilities;AiConfiguredProviderRuntime runtime;View view;
    @BeforeEach void setup()throws Exception {
        fixture=new AiConfigurationServiceTest();fixture.setup();
        String migration;
        try(var input=getClass().getResourceAsStream("/db/migration/V20261018_249__ai_channel_catalog_receipts.sql")){migration=new String(Objects.requireNonNull(input).readAllBytes(),StandardCharsets.UTF_8);}
        migration=migration.replaceAll("(?m)^--.*$","").replace(" COLLATE utf8mb4_bin","").replace(" ENGINE=InnoDB DEFAULT CHARSET=utf8mb4","");
        for(String statement:migration.split(";"))if(!statement.isBlank())fixture.jdbc.execute(statement);
        store=new AiModelCatalogStore(fixture.jdbc,new org.springframework.jdbc.datasource.DataSourceTransactionManager(Objects.requireNonNull(fixture.jdbc.getDataSource())));
        capabilities=new AiToolCapabilities(fixture.service,store);runtime=new AiConfiguredProviderRuntime(fixture.service,fixture.transport,capabilities);
        view=fixture.active();
    }
    @AfterEach void close(){if(fixture!=null)fixture.close();}
    AiModelCatalogStore.Receipt bind(int keyIndex,List<String> parameters) {
        var snapshot=view.draft();var key=snapshot.keys().get(keyIndex);
        String fingerprint=fixture.service.settingsFingerprint("ai:catalog-credential:v1",List.of(snapshot.providerCode(),snapshot.endpointUrl(),fixture.service.resolveSecret(key)));
        var receipt=store.create("controlled-owner",snapshot.providerCode(),snapshot.endpointUrl(),fingerprint,"USER_FILTERED",
                new AiModelCatalogTransport.Catalog(snapshot.models().stream().map(model->new AiModelCatalogDtos.Model(model.modelCode(),model.modelCode(),8192L,1024L,List.of("text"),List.of("text"),parameters)).toList(),2,0,Map.of()));
        store.bind(Long.parseLong(view.id()),key.keyRef(),receipt.id());return receipt;
    }
    ModelBinding binding(int modelIndex,int keyIndex) {
        var snapshot=view.draft();var key=snapshot.keys().get(keyIndex);
        return new ModelBinding(new ModelVersionRef(snapshot.models().get(modelIndex).modelRef(),view.activeVersion()),KeyMode.FIXED,Set.of(),new KeyRef(key.keyRef(),key.keyVersion()),List.of());
    }
    @Test void onlyAdmittedKeysWithExactOfficialToolMetadataBecomeAvailable() {
        var requirements=new Requirements(OutputFormat.TEXT,true,true);
        assertTrue(runtime.availableModels(requirements).isEmpty());bind(0,List.of("tools"));
        var available=runtime.availableModels(requirements);assertEquals(2,available.size());assertTrue(available.stream().allMatch(model->model.usableKeyCount()==1&&model.capabilities().tools()));
        assertTrue(runtime.validateBindings(List.of(binding(0,0)),requirements).valid());
        assertFalse(runtime.validateBindings(List.of(binding(0,1)),requirements).valid());
        // Key B was rejected for model B in the controlled normal inference test; metadata cannot admit it.
        bind(1,List.of("tools"));assertEquals(List.of(2,1),runtime.availableModels(requirements).stream().map(AvailableModel::usableKeyCount).toList());
    }
    @Test void savedMetadataSurvivesTicketExpiryButNotWrongEndpointCredentialOrNonToolReceipts() {
        var snapshot=view.draft();var key=snapshot.keys().get(0);var model=snapshot.models().get(0);long id=Long.parseLong(view.id());
        bind(0,List.of("response_format"));assertFalse(capabilities.forKey(id,snapshot,model,key).tools());
        var receipt=bind(0,List.of("tools"));assertTrue(capabilities.forKey(id,snapshot,model,key).tools());
        fixture.jdbc.update("UPDATE sys_ai_channel_catalog_scan SET expires_at_ms=? WHERE scan_id=?",Instant.now().minusSeconds(1).toEpochMilli(),receipt.id());assertTrue(capabilities.forKey(id,snapshot,model,key).tools());
        receipt=bind(0,List.of("tools"));fixture.jdbc.update("UPDATE sys_ai_channel_catalog_scan SET credential_fingerprint='different' WHERE scan_id=?",receipt.id());assertFalse(capabilities.forKey(id,snapshot,model,key).tools());
        receipt=bind(0,List.of("tools"));fixture.jdbc.update("UPDATE sys_ai_channel_catalog_scan SET catalog_endpoint_url='https://other.example/models' WHERE scan_id=?",receipt.id());assertFalse(capabilities.forKey(id,snapshot,model,key).tools());
    }
    @Test void toolAttemptRechecksProofEmergencyDisableAndExactCredentialBeforeTransport() {
        var chosen=binding(0,0);var target=runtime.resolveTargets(chosen,Set.of(),fixture.control).get(0);
        var request=new ToolExecutionRequest(new Invocation(new TrustedActor("1","000000",Set.of(),null),"agent","1",new BusinessRef("agent-session","fixture"),UUID.randomUUID().toString()),"tool-attempt",target,"{\"messages\":[{\"role\":\"user\",\"content\":\"fixture\"}]}",128);
        assertEquals(ErrorCode.CAPABILITY_UNSUPPORTED,assertThrows(AiRuntimeException.class,()->runtime.executeTools(request,frame->{},fixture.control)).code());
        bind(0,List.of("tools"));
        when(fixture.transport.callTools(anyString(),anyString(),anyString(),anyInt(),anyString(),anyString(),anyInt(),any(),any())).thenReturn(new ExecutionOutcome(ExecutionStatus.SUCCEEDED,null,false,true,new Usage(1L,1L,2L),"fixture",1));
        assertEquals(ExecutionStatus.SUCCEEDED,runtime.executeTools(request,frame->{},fixture.control).status());
        fixture.jdbc.update("UPDATE sys_ai_configuration SET status='DISABLED' WHERE config_id=?",Long.parseLong(view.id()));
        assertEquals(ErrorCode.MODEL_UNAVAILABLE,assertThrows(AiRuntimeException.class,()->runtime.executeTools(request,frame->{},fixture.control)).code());
        verify(fixture.transport,times(1)).callTools(anyString(),anyString(),anyString(),anyInt(),eq("controlled-test-key-a"),anyString(),anyInt(),any(),any());
    }
}
