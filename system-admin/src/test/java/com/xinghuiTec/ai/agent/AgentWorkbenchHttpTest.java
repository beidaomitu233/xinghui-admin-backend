package com.xinghuiTec.ai.agent;

import com.xinghuiTec.agent.persistence.JdbcAgentRepository;
import com.xinghuiTec.agent.runtime.AgentWorkbench;
import com.xinghuiTec.ai.application.*;
import com.xinghuiTec.ai.contract.AiTypes.TrustedActor;
import com.xinghuiTec.ai.scene.SceneManagementService;
import org.junit.jupiter.api.*;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import java.time.Instant;
import java.util.*;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

/** Real HTTP body/filter/controller boundary, synthetic stores; method security also runs in the application. */
class AgentWorkbenchHttpTest {
    AgentAdapterFixture fixture;JdbcAgentRepository repository;AgentWorkbench workbench;SceneManagementService scenes;MockMvc mvc;
    String session=UUID.randomUUID().toString(),run=UUID.randomUUID().toString();
    @BeforeEach void setup(){
        fixture=new AgentAdapterFixture();repository=mock(JdbcAgentRepository.class);workbench=mock(AgentWorkbench.class);scenes=mock(SceneManagementService.class);
        var identity=mock(AiInvocationIdentity.class);when(identity.current()).thenReturn(new TrustedActor("1","tenant_a",Set.of("ai:agent:invoke"),null));
        var controller=new AgentWorkbenchController(identity,fixture.access,new AiRuntimeBodyCodec(),repository,workbench,scenes);
        mvc=MockMvcBuilders.standaloneSetup(controller).addFilters(new AiRuntimeBoundaryFilter(fixture.json)).build();
    }
    @AfterEach void close(){fixture.close();}
    @Test void capabilityIndexUsesAuthenticatedActorAndIsReadOnly()throws Exception{
        var registry=mock(AgentResourceRegistry.class);
        when(registry.resources(any())).thenReturn(fixture.json.readTree("[{\"resource\":\"users\",\"name\":\"用户\",\"operations\":[\"query\"],\"readOnly\":true}]"));
        var identity=mock(AiInvocationIdentity.class);when(identity.current()).thenReturn(new TrustedActor("1","tenant_a",Set.of("ai:agent:invoke"),null));
        var controller=new AgentWorkbenchController(identity,fixture.access,new AiRuntimeBodyCodec(),repository,workbench,scenes);
        org.springframework.test.util.ReflectionTestUtils.setField(controller,"resources",registry);
        var http=MockMvcBuilders.standaloneSetup(controller).addFilters(new AiRuntimeBoundaryFilter(fixture.json)).build();
        http.perform(get("/system/ai/workbench/capabilities")).andExpect(status().isOk()).andExpect(jsonPath("$.data[0].operations[0]").value("query"));
        verify(registry).resources(argThat(call->call.actor().equals(fixture.a)));
        fixture.permissions.remove("ai:agent:invoke");http.perform(get("/system/ai/workbench/capabilities")).andExpect(status().isForbidden());
        verifyNoInteractions(repository,workbench,scenes);
    }
    @Test void admissionAndStatusNeverExposeInternalInputAndReadsDoNotExecuteAgain()throws Exception{
        var record=new JdbcAgentRepository.Run(run,session,"RUNNING","scene.agent","DO-NOT-RETURN-PRIVATE-INPUT",null,Instant.now().plusSeconds(60));
        when(workbench.submit(eq(fixture.a),eq(session),anyString(),eq("Synthetic task"),eq("scene.agent"),eq(List.of()),eq("EXECUTE"))).thenReturn(new JdbcAgentRepository.Admission(record,true));
        when(repository.run(fixture.a,run)).thenReturn(record);
        String body="{\"input\":\"Synthetic task\",\"sceneKey\":\"scene.agent\",\"clientRequestId\":\"fixture-request-1234\"}";
        var response=mvc.perform(post("/system/ai/workbench/sessions/"+session+"/runs").contentType(MediaType.APPLICATION_JSON).content(body))
                .andExpect(status().isAccepted()).andExpect(header().string("Cache-Control","no-store")).andExpect(jsonPath("$.data.run.id").value(run)).andReturn().getResponse();
        assertFalse(response.getContentAsString().contains("PRIVATE-INPUT"));
        for(int index=0;index<3;index++)mvc.perform(get("/system/ai/workbench/runs/"+run)).andExpect(status().isOk()).andExpect(jsonPath("$.data.status").value("RUNNING"));
        verify(workbench,times(1)).submit(any(),anyString(),anyString(),anyString(),anyString(),anyList(),anyString());
    }
    @Test void identitiesDuplicateJsonTrailingValuesCompressionAndBodyOverflowAreRejected()throws Exception{
        for(String body:List.of("{\"title\":\"QA\",\"tenantId\":\"tenant_b\"}","{\"title\":\"QA\",\"title\":\"duplicate\"}","{\"title\":\"QA\"}{}","null"))
            mvc.perform(post("/system/ai/workbench/sessions").contentType(MediaType.APPLICATION_JSON).content(body)).andExpect(status().isBadRequest());
        mvc.perform(post("/system/ai/workbench/sessions").contentType(MediaType.APPLICATION_JSON).header("Content-Encoding","gzip").content("{}"))
                .andExpect(status().isUnsupportedMediaType());
        mvc.perform(post("/system/ai/workbench/sessions").contentType(MediaType.APPLICATION_JSON).content("x".repeat(131073)))
                .andExpect(status().isPayloadTooLarge());
        verifyNoInteractions(repository,workbench,scenes);
    }
    @Test void currentPermissionRevocationStopsReadsAndWritesBeforeRepositoryAndCursorIsStrict()throws Exception{
        mvc.perform(get("/system/ai/workbench/sessions/"+session+"/events").param("after","9223372036854775808")).andExpect(status().isBadRequest());
        fixture.permissions.remove("ai:agent:invoke");
        mvc.perform(get("/system/ai/workbench/sessions")).andExpect(status().isForbidden());
        mvc.perform(post("/system/ai/workbench/sessions").contentType(MediaType.APPLICATION_JSON).content("{\"title\":\"Denied\"}"))
                .andExpect(status().isForbidden());
        verifyNoInteractions(repository,workbench,scenes);
    }
    @Test void springAssemblesWorkbenchAndCommonLogAccessWithoutReverseModuleDependencies(){
        try(var context=new org.springframework.context.annotation.AnnotationConfigApplicationContext()){
            var source=new org.springframework.jdbc.datasource.DriverManagerDataSource("jdbc:h2:mem:agent_wiring;MODE=MySQL","sa","");
            context.registerBean(javax.sql.DataSource.class,()->source);
            context.registerBean(org.springframework.jdbc.core.JdbcTemplate.class,()->new org.springframework.jdbc.core.JdbcTemplate(source));
            context.registerBean(org.springframework.transaction.PlatformTransactionManager.class,()->new org.springframework.jdbc.datasource.DataSourceTransactionManager(source));
            context.registerBean(com.fasterxml.jackson.databind.ObjectMapper.class,()->fixture.json);
            context.registerBean(AgentAccess.class,()->fixture.access);
            context.registerBean(com.xinghuiTec.config.SecretCipher.class,()->new com.xinghuiTec.config.SecretCipher(()->Base64.getEncoder().encodeToString(new byte[32])));
            context.registerBean(SceneManagementService.class,()->scenes);
            context.registerBean(com.xinghuiTec.ai.modelhub.ModelHubService.class,()->mock(com.xinghuiTec.ai.modelhub.ModelHubService.class));
            context.registerBean(com.xinghuiTec.ai.config.AiModelCatalogService.class,()->mock(com.xinghuiTec.ai.config.AiModelCatalogService.class));
            context.registerBean(AiSharedModelCursor.class,()->mock(AiSharedModelCursor.class));
            context.registerBean(com.xinghuiTec.ai.contract.AiProviderRuntime.class,()->mock(com.xinghuiTec.ai.contract.AiProviderRuntime.class));
            context.registerBean(AiRequestLogService.class,()->mock(AiRequestLogService.class));
            context.registerBean(AiRequestStore.class,()->mock(AiRequestStore.class));
            context.registerBean(AiRuntimeService.class,()->mock(AiRuntimeService.class));
            context.registerBean(com.xinghuiTec.ai.scene.SqlAssistanceService.class,()->mock(com.xinghuiTec.ai.scene.SqlAssistanceService.class));
            context.registerBean(com.xinghuiTec.ai.modelhub.ModelHubTestService.class,()->mock(com.xinghuiTec.ai.modelhub.ModelHubTestService.class));
            context.registerBean(io.agentscope.harness.agent.DistributedStore.class,()->mock(io.agentscope.harness.agent.DistributedStore.class));
            context.register(com.xinghuiTec.ai.hub.NativeAgentScopeExecution.class,com.xinghuiTec.ai.hub.AgentChanges.class,AgentWorkbenchConfiguration.class,AgentConfiguredModels.class,AgentImages.class,AgentResourceRegistry.class,AgentPayloadCipher.class,
                    AgentLogAccess.class,AiCallLogStore.class,AiCallLogService.class);
            context.refresh();
            assertNotNull(context.getBean(AgentWorkbench.class));assertNotNull(context.getBean(JdbcAgentRepository.class));
            assertSame(context.getBean(AgentLogAccess.class),org.springframework.test.util.ReflectionTestUtils.getField(context.getBean(AiCallLogService.class),"agentAccess"));
        }
    }
}
