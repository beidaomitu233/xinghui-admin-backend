package com.xinghuiTec.ai.agent;

import com.xinghuiTec.agent.api.*;
import com.xinghuiTec.agent.persistence.JdbcAgentRepository;
import com.xinghuiTec.ai.hub.*;
import com.xinghuiTec.config.SecretCipher;
import com.fasterxml.jackson.databind.JsonNode;
import org.junit.jupiter.api.*;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.*;
import org.springframework.jdbc.datasource.init.ResourceDatabasePopulator;
import org.springframework.core.io.ClassPathResource;
import java.time.*;
import java.util.*;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicBoolean;
import static org.assertj.core.api.Assertions.*;

class HubChangesTest {
    AgentAdapterFixture fixture;JdbcTemplate jdbc;JdbcAgentRepository repository;AgentChanges changes;AgentCall call;
    AtomicInteger writes=new AtomicInteger();
    AtomicBoolean failWrite=new AtomicBoolean();
    java.util.concurrent.atomic.AtomicReference<JsonNode> snapshot=new java.util.concurrent.atomic.AtomicReference<>();
    @BeforeEach void setup(){
        fixture=new AgentAdapterFixture();fixture.permissions.add("lowcode:record:add");
        var ds=new DriverManagerDataSource("jdbc:h2:mem:"+UUID.randomUUID()+";MODE=MySQL;DB_CLOSE_DELAY=-1","sa","");
        new ResourceDatabasePopulator(new ClassPathResource("db/migration/V20261024_400__agent_workbench.sql"),new ClassPathResource("db/migration/V20261025_430__agent_images.sql"),new ClassPathResource("db/migration/V20261025_500__ai_hub_gateway.sql"),new ClassPathResource("db/migration/V20261026_501__ai_hub_audit_targets.sql")).execute(ds);
        jdbc=new JdbcTemplate(ds);var tx=new DataSourceTransactionManager(ds);
        jdbc.execute("CREATE TABLE business_ledger(id INT)");
        var codec=new AgentPayloadCipher(new SecretCipher(()->Base64.getEncoder().encodeToString(new byte[32])));
        var provider=new AgentResourceProvider(){
            public String namespace(){return "forms";}
            public void requireReadable(AgentActor actor,String r){fixture.access.current(actor).require("ai:agent:invoke");}
            public JsonNode catalog(AgentCall c){return fixture.json.createArrayNode().addObject().put("resource","forms");}
            public JsonNode describe(AgentCall c,String r){requireReadable(c.actor(),r);var node=fixture.json.createObjectNode().put("name","测试商品");var operations=node.putArray("operations").add("query");if(fixture.permissions.contains("lowcode:record:add"))operations.add("create");return node;}
            public JsonNode query(AgentCall c,String r,JsonNode a){requireReadable(c.actor(),r);return snapshot.get()==null?fixture.json.createObjectNode().put("count",writes.get()):snapshot.get().deepCopy();}
            public JsonNode mutate(AgentCall c,String r,JsonNode a){requireReadable(c.actor(),r);fixture.access.current(c.actor()).require("lowcode:record:add");jdbc.update("INSERT INTO business_ledger(id) VALUES(42)");if(failWrite.get())throw new AgentProblem("BUSINESS_VALIDATION","QA库存已变化");writes.incrementAndGet();return fixture.json.createObjectNode().put("id","42").put("created",true);}
        };
        var registry=new AgentResourceRegistry(List.of(provider),fixture.access,fixture.json);
        repository=new JdbcAgentRepository(jdbc,tx,codec,registry,fixture.json);changes=new AgentChanges(jdbc,tx,codec,registry,fixture.access,repository,fixture.json);
        var session=repository.create(fixture.a,"QA变更");var run=repository.admit(fixture.a,session.id(),"qa-request-00000001","新增QA商品","qa.agent",Duration.ofMinutes(2)).run();repository.claim(fixture.a,run.id());
        call=new AgentCall(fixture.a,session.id(),run.id(),"tool-call",run.deadline(),()->false);
    }
    @AfterEach void close(){fixture.close();}
    String preview(){var input=fixture.json.createObjectNode().put("operation","create");input.putObject("data").put("name","QA_PRIVATE_ITEM").put("password","QA_PRIVATE_SECRET");return changes.preview(call,"call-1","forms:1",input).path("changeId").asText();}
    @Test void confirmedBusinessActionIsPublishedOnlyAfterCommitWithoutPrivatePayload(){
        var logService=org.mockito.Mockito.mock(com.xinghuiTec.service.SysOperLogService.class);
        org.springframework.test.util.ReflectionTestUtils.setField(changes,"operationLogs",new AgentOperationLogs(logService));
        String id=preview();changes.decide(fixture.a,id,true);
        var capture=org.mockito.ArgumentCaptor.forClass(com.xinghuiTec.domain.entity.SysOperLog.class);
        org.mockito.Mockito.verify(logService).saveOperLog(capture.capture());
        var log=capture.getValue();com.xinghuiTec.aop.BusinessLogDetails.enrich(log);
        assertThat(log.getOperationSource()).isEqualTo("AI 中台");assertThat(log.getBusinessTarget()).contains("业务表单 #1","42");
        assertThat(log.toString()).doesNotContain("QA_PRIVATE_ITEM","QA_PRIVATE_SECRET");
        changes.decide(fixture.a,id,true);org.mockito.Mockito.verifyNoMoreInteractions(logService);
    }
    @Test void previewDoesNotWriteAndOnlyOwnerCanConfirmExactlyOnce(){
        String id=preview();assertThat(writes).hasValue(0);
        assertThatThrownBy(()->changes.decide(fixture.b,id,true)).isInstanceOf(AgentProblem.class);assertThat(writes).hasValue(0);
        assertThat(changes.decide(fixture.a,id,true).path("status").asText()).isEqualTo("EXECUTED");
        changes.decide(fixture.a,id,true);assertThat(writes).hasValue(1);
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM sys_agent_tool_audit WHERE operation='confirm_execute'",Integer.class)).isEqualTo(1);
        assertThat(jdbc.queryForObject("SELECT target_ids_json FROM sys_agent_tool_audit WHERE operation='confirm_execute'",String.class)).isEqualTo("[\"42\"]");
    }
    @Test void missingOperationExplainsTheToolContractInsteadOfClaimingPermissionDenial(){
        var malformed=fixture.json.createObjectNode().put("name","QA杯子").put("price",39);
        assertThatThrownBy(()->changes.preview(call,"missing-operation","forms:1",malformed))
                .isInstanceOfSatisfying(AgentProblem.class,p->{assertThat(p.code()).isEqualTo("INVALID_ARGUMENTS");assertThat(p.getMessage()).contains("operation","mutation");});
        assertThat(writes).hasValue(0);
        assertThat(changes.list(fixture.a,call.sessionId())).isEmpty();
    }
    @Test void retryingAProposalWithANewToolIdReusesThePendingCard(){
        String first=preview();
        var original=fixture.json.createObjectNode().put("operation","create");
        original.putObject("data").put("name","QA_PRIVATE_ITEM").put("password","QA_PRIVATE_SECRET");
        assertThat(changes.preview(call,"retry-tool-call","forms:1",original).path("changeId").asText()).isEqualTo(first);
        assertThat(changes.list(fixture.a,call.sessionId())).hasSize(1);
        assertThat(writes).hasValue(0);
        var other=original.deepCopy();((com.fasterxml.jackson.databind.node.ObjectNode)other).put("id","different");
        assertThatThrownBy(()->changes.preview(call,"different-tool-call","forms:1",other)).isInstanceOfSatisfying(AgentProblem.class,p->assertThat(p.code()).isEqualTo("CHANGE_PENDING"));
    }
    @Test void permissionRevocationAndExpiredPreviewNeverReachBusinessWrite(){
        String id=preview();fixture.permissions.remove("lowcode:record:add");
        assertThatThrownBy(()->changes.decide(fixture.a,id,true)).isInstanceOf(AgentProblem.class);assertThat(writes).hasValue(0);
        fixture.permissions.add("lowcode:record:add");jdbc.update("UPDATE sys_agent_change SET expires_at=?",java.sql.Timestamp.from(Instant.now().minusSeconds(1)));
        assertThatThrownBy(()->changes.decide(fixture.a,id,true)).isInstanceOf(AgentProblem.class);assertThat(writes).hasValue(0);
    }
    @Test void rejectKeepsBusinessUnchangedAndRawStorageIsEncrypted(){
        String id=preview();changes.decide(fixture.a,id,false);
        assertThat(writes).hasValue(0);assertThat(changes.list(fixture.a,call.sessionId()).get(0).status()).isEqualTo("REJECTED");
        assertThat(changes.list(fixture.a,call.sessionId()).get(0).preview().toString()).doesNotContain("QA_PRIVATE_SECRET");
        jdbc.queryForList("SELECT arguments_payload,preview_payload FROM sys_agent_change").forEach(row->assertThat(row.toString()).doesNotContain("QA_PRIVATE_ITEM","QA_PRIVATE_SECRET"));
    }
    @Test void waitingToolResumesWithActualReceiptWithoutHoldingTransaction()throws Exception{
        String id=preview();var pool=Executors.newSingleThreadExecutor();
        try{
            var waiting=pool.submit(()->changes.awaitDecision(call,id));
            assertThat(writes).hasValue(0);
            assertThat(changes.decide(fixture.a,id,true).path("status").asText()).isEqualTo("EXECUTED");
            var receipt=waiting.get(3,TimeUnit.SECONDS);
            assertThat(receipt.path("result").path("created").asBoolean()).isTrue();
            assertThat(receipt.path("result").path("id").asText()).isEqualTo("42");
            assertThat(repository.run(fixture.a,call.runId()).status()).isEqualTo("RUNNING");
            assertThat(writes).hasValue(1);
        }finally{pool.shutdownNow();}
    }
    @Test void rejectionReturnsToAgentWithoutBusinessSideEffect(){String id=preview();changes.decide(fixture.a,id,false);assertThat(changes.awaitDecision(call,id).path("status").asText()).isEqualTo("REJECTED");assertThat(writes).hasValue(0);}
    @Test void cancellationStopsWaitAndDisablesPendingDecision(){
        String id=preview();repository.cancel(fixture.a,call.runId());
        assertThatThrownBy(()->changes.awaitDecision(call,id)).isInstanceOf(AgentProblem.class);
        assertThatThrownBy(()->changes.decide(fixture.a,id,true)).isInstanceOf(AgentProblem.class);
        assertThat(changes.list(fixture.a,call.sessionId()).get(0).status()).isEqualTo("CANCELLED");assertThat(writes).hasValue(0);
    }
    @Test void failedBusinessTransactionRollsBackAndReturnsReceiptToWaitingAgent(){
        String id=preview();failWrite.set(true);
        assertThat(changes.decide(fixture.a,id,true).path("status").asText()).isEqualTo("FAILED");
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM business_ledger",Integer.class)).isZero();
        var receipt=changes.awaitDecision(call,id);assertThat(receipt.path("result").path("code").asText()).isEqualTo("BUSINESS_VALIDATION");
        changes.decide(fixture.a,id,true);assertThat(writes).hasValue(0);
    }
    @Test void changedSnapshotRequiresNewPreviewInsteadOfOverwriting(){
        var input=fixture.json.createObjectNode().put("operation","create").put("id","9");
        String id=changes.preview(call,"stale-1","forms:1",input).path("changeId").asText();writes.set(2);
        assertThat(changes.decide(fixture.a,id,true).path("status").asText()).isEqualTo("FAILED");
        assertThat(changes.awaitDecision(call,id).path("result").path("code").asText()).isEqualTo("VERSION_CONFLICT");assertThat(writes).hasValue(2);
    }
    @Test void storedSnapshotAcceptsEquivalentNativeDecimalAndLongNodesButRejectsChangedValues(){
        var original=fixture.json.createObjectNode().put("id",7L).put("revision",0L);
        original.putObject("data").put("price",new java.math.BigDecimal("39.00"));
        original.putObject("schema").put("min",new java.math.BigDecimal("0"));snapshot.set(original);
        var input=fixture.json.createObjectNode().put("operation","create").put("id","7");
        String first=changes.preview(call,"decimal-snapshot","forms:1",input).path("changeId").asText();
        assertThat(changes.decide(fixture.a,first,true).path("status").asText()).isEqualTo("EXECUTED");assertThat(writes).hasValue(1);
        String second=changes.preview(call,"changed-price","forms:1",input).path("changeId").asText();
        original.withObject("data").put("price",new java.math.BigDecimal("39.01"));
        assertThat(changes.decide(fixture.a,second,true).path("status").asText()).isEqualTo("FAILED");
        assertThat(changes.awaitDecision(call,second).path("result").path("code").asText()).isEqualTo("VERSION_CONFLICT");assertThat(writes).hasValue(1);
    }
}
