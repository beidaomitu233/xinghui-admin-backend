package com.xinghuiTec.ai.scene;

import com.xinghuiTec.ai.application.*;
import com.xinghuiTec.ai.config.*;
import com.xinghuiTec.ai.contract.*;
import com.xinghuiTec.ai.contract.AiSceneQueryRuntime.*;
import com.xinghuiTec.ai.contract.AiTypes.*;
import com.xinghuiTec.ai.conversation.ConversationStore;
import com.xinghuiTec.ai.model.*;
import com.xinghuiTec.config.SecretCipher;
import com.xinghuiTec.domain.entity.*;
import com.xinghuiTec.security.PlatformAccessService;
import org.junit.jupiter.api.*;
import org.springframework.context.annotation.*;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.config.annotation.method.configuration.EnableMethodSecurity;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import org.springframework.transaction.support.TransactionTemplate;
import org.springframework.test.util.ReflectionTestUtils;

import java.time.Instant;
import java.util.*;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

/** Actual executor/configured router/transport/ledger; C schema and AST verdicts are explicit contract fixtures. */
class SqlAssistanceServiceTest {
    private SceneTestSupport f;
    private SqlAssistanceHttpFixture http;
    private AiConfigurationTests probes;
    private SqlAssistanceService service;
    private QueryBoundary query;
    private TrustedActor actor;
    private SceneDtos.ModelRef model;
    private String scene;
    private SecretCipher cipher;

    @BeforeEach void setup() throws Exception {
        f=new SceneTestSupport();f.script(f.read("V20261018_240__conversation_history.sql"));
        f.script(f.read("V20261016_180__ai_config_v04.sql").split("-- Existing configurations")[0]);
        f.script(f.read("V20261018_245__ai_key_health.sql"));f.script(f.read("V20261018_247__sql_assistance_safe_results.sql"));
        var rights=new HashSet<>(f.actor.authorities());rights.add("ai:scene:test");actor=new TrustedActor("101","tenant_a",rights,null);
        cipher=new SecretCipher(()->Base64.getEncoder().encodeToString(new byte[32]));
        var configs=new AiConfigurationService(f.jdbc,f.mapper,cipher,new AiModelConfigRules(),new TransactionTemplate(f.manager),f.beans.getBeanProvider(AiApplicationReferences.class),mock(AiModelConfigService.class),mock(AiModelCredentialService.class));
        http=new SqlAssistanceHttpFixture(f.mapper);probes=new AiConfigurationTests(configs,http.transport,new TransactionTemplate(f.manager));
        var created=configs.create(new AiConfigurationDtos.Create(new AiConfigurationDtos.DraftChange(0,"Controlled","qa","openrouter",OpenRouterTransport.ENDPOINT,"OPENAI_CHAT",120,
                List.of(new AiConfigurationDtos.ModelInput(null,"unit/sql-helper")),List.of(new AiConfigurationDtos.KeyInput(null,"Primary","DB",null,"controlled-primary"),new AiConfigurationDtos.KeyInput(null,"Backup","DB",null,"controlled-secondary"))),null));
        long id=Long.parseLong(created.id());var probe=probes.start(id,new AiConfigurationDtos.TestStart(created.revision(),created.draft().configVersion(),true));Instant limit=Instant.now().plusSeconds(4);
        while((List.of("QUEUED","RUNNING").contains(probes.get(id,Long.parseLong(probe.id())).status()) || f.jdbc.queryForObject("SELECT running_test_id FROM sys_ai_configuration WHERE config_id=?",Long.class,id)!=null) && Instant.now().isBefore(limit))Thread.sleep(5);
        var active=configs.activate(id,new AiConfigurationDtos.Activate(created.revision(),created.draft().configVersion(),false));
        model=new SceneDtos.ModelRef(active.draft().models().get(0).modelRef(),active.activeVersion());
        f.beans.addBean("provider",new AiConfiguredProviderRuntime(configs,http.transport));
        query=new QueryBoundary();http.schemaChecks=()->query.schemas.get();f.beans.addBean("query",query);f.service.queryRuntime(f.beans.getBeanProvider(AiSceneQueryRuntime.class));
        var conversations=new ConversationStore(f.jdbc,f.requests,f.manager);f.runtime.conversations(conversations);f.lifecycle.conversations(conversations);
        scene=f.management.create(new SceneDtos.Create("Assistance","",SceneDtos.Kind.GENERAL,null,UUID.randomUUID().toString()),actor).scene().sceneKey();
        service=newService();
    }
    private SqlAssistanceService newService(){return new SqlAssistanceService(f.jdbc,f.mapper,cipher,f.references,f.management,f.applications,f.requests,f.lifecycle,f.runtime,f.beans.getBeanProvider(AiSceneQueryRuntime.class),f.manager);}
    @AfterEach void close(){SecurityContextHolder.clearContext();if(http!=null)http.release();if(probes!=null)probes.close();if(f!=null)f.close();}
    private SqlAssistanceDtos.Start input(){return new SqlAssistanceDtos.Start(scene,0,"查询主题为Alpha的条目",List.of("lowcode.1"),null,model,"schema-v1",true,UUID.randomUUID().toString(),20);}
    private SqlAssistanceDtos.View finished(String id)throws Exception {
        Instant limit=Instant.now().plusSeconds(5);
        while(Instant.now().isBefore(limit)) {
            var view=service.get(Long.parseLong(id),actor);
            if(!Set.of(SqlAssistanceDtos.Status.QUEUED,SqlAssistanceDtos.Status.RUNNING,SqlAssistanceDtos.Status.VALIDATING).contains(view.status()))return view;
            Thread.sleep(5);
        }
        throw new AssertionError("controlled SQL assistance did not finish");
    }
    @Test void approvedCandidateIsDurableButHiddenPromptAndRawStreamNeverBecomePublic()throws Exception {
        var accepted=service.start(input(),actor);var result=finished(accepted.requestId());
        assertEquals(SqlAssistanceDtos.Status.SUCCEEDED,result.status());assertEquals("Alpha",result.query().parameters().get("term"));
        assertTrue(http.systemMessage.contains("SCHEMA_PRIVATE_SENTINEL"));assertTrue(http.systemMessage.contains("SQL_ASSIST_GUARD_"));assertFalse(http.systemMessage.contains("tenant_a"));
        var publicEvents=(AiRuntimeEvents)ReflectionTestUtils.getField(f.lifecycle,"events");
        assertTrue(publicEvents.after(accepted.requestId(),0).stream().noneMatch(e->e.name()==AiRuntimeEvents.Name.DELTA));
        assertEquals(ErrorCode.NOT_FOUND,assertThrows(AiRuntimeException.class,()->f.runtime.status(Long.parseLong(accepted.requestId()),actor)).code());
        assertEquals(ErrorCode.NOT_FOUND,assertThrows(AiRuntimeException.class,()->f.runtime.authorizeEvents(f.requests.owned(Long.parseLong(accepted.requestId()),new AiRequestStore.Owner(actor.subjectId(),actor.tenantId())),actor)).code());
        assertEquals(0,f.jdbc.queryForObject("SELECT COUNT(*) FROM sys_ai_conversation",Integer.class));assertNull(f.management.detail(scene,actor).definition());
        String stored=f.jdbc.queryForMap("SELECT * FROM sys_ai_sql_assistance WHERE request_id=?",accepted.requestId()).toString();
        assertFalse(stored.contains("SCHEMA_PRIVATE_SENTINEL"));assertFalse(stored.contains("SQL_ASSIST_GUARD_"));
        assertFalse(f.jdbc.queryForObject("SELECT snapshot_json FROM sys_ai_application_version ORDER BY app_version_id DESC LIMIT 1",String.class).contains("SCHEMA_PRIVATE_SENTINEL"));
        assertEquals(result.query(),newService().get(Long.parseLong(accepted.requestId()),actor).query());assertEquals(1,http.generations.get());
    }
    @Test void exactNumericCandidatesSurviveGenerationPersistenceAndPublicJsonWithoutGuessingText()throws Exception {
        for(String numbers:List.of("{\"$number\":\"1234567890123456789\"},\"next\":{\"$number\":\"1234567890123456790\"}","1234567890123456789,\"next\":1234567890123456790")) {
            http.output="{\"sql\":\"SELECT f.subject FROM form_1 f WHERE f.subject = :term\",\"parameters\":{\"term\":"+numbers+",\"text\":\"00123\",\"flag\":true,\"empty\":null}}";
            var result=finished(service.start(input(),actor).requestId());
            assertEquals(SqlAssistanceDtos.Status.SUCCEEDED,result.status());
            assertEquals(new java.math.BigDecimal("1234567890123456789"),result.query().parameters().get("term"));
            assertEquals(new java.math.BigDecimal("1234567890123456790").stripTrailingZeros(),result.query().parameters().get("next"));
            assertEquals("00123",result.query().parameters().get("text"));assertEquals(true,result.query().parameters().get("flag"));assertNull(result.query().parameters().get("empty"));
            var json=f.mapper.readTree(f.mapper.writeValueAsString(result));
            assertEquals("1234567890123456789",json.at("/query/parameters/term/$number").asText());
            assertEquals("1234567890123456790",json.at("/query/parameters/next/$number").asText());
            assertEquals(result.query(),newService().get(Long.parseLong(result.requestId()),actor).query());
        }
        assertTrue(http.systemMessage.contains("$number"));
    }
    @Test void rejectedAstVerdictNeverReturnsOrStoresTheSqlCandidate()throws Exception {
        query.reject=true;var result=finished(service.start(input(),actor).requestId());
        assertEquals(SqlAssistanceDtos.Status.FAILED,result.status());assertEquals("SQL_VALIDATION_FAILED",result.errorCode());assertNull(result.query());
        assertEquals(QueryIssue.INVALID_SQL,result.diagnostics().get(0).code());assertNull(f.jdbc.queryForObject("SELECT approved_query_json FROM sys_ai_sql_assistance",String.class));
    }
    @Test void hiddenPromptInsideOtherwiseValidSqlParameterIsRejectedBeforeAstPublication()throws Exception {
        http.echoInParameter=true;var result=finished(service.start(input(),actor).requestId());
        assertEquals(SqlAssistanceDtos.Status.FAILED,result.status());assertEquals("INVALID_SQL_GENERATION",result.errorCode());assertNull(result.query());
        assertEquals(0,query.validations.get());assertFalse(f.mapper.writeValueAsString(result).contains("SCHEMA_PRIVATE_SENTINEL"));
    }
    @Test void permissionRevocationWhileUpstreamIsPendingHidesResultAndPreventsApproval()throws Exception {
        http.hold=true;var accepted=service.start(input(),actor);assertTrue(http.entered.await(2,TimeUnit.SECONDS));query.deny=true;
        assertEquals(ErrorCode.RESOURCE_FORBIDDEN,assertThrows(AiRuntimeException.class,()->service.get(Long.parseLong(accepted.requestId()),actor)).code());
        http.release();Instant limit=Instant.now().plusSeconds(3);
        while("PENDING".equals(f.jdbc.queryForObject("SELECT validation_state FROM sys_ai_sql_assistance",String.class))&&Instant.now().isBefore(limit))Thread.sleep(5);
        query.deny=false;var result=finished(accepted.requestId());assertEquals(SqlAssistanceDtos.Status.FAILED,result.status());assertNull(result.query());
    }
    @Test void replayDoesNotSpendAgainAndChangedNonceContentConflicts()throws Exception {
        var input=input();var first=service.start(input,actor);finished(first.requestId());assertEquals(first.requestId(),service.start(input,actor).requestId());assertEquals(1,http.generations.get());
        var altered=new SqlAssistanceDtos.Start(scene,0,"另一描述",input.datasetKeys(),null,model,input.expectedSchemaFingerprint(),true,input.clientMutationId(),20);
        assertEquals("IDEMPOTENCY_CONFLICT",assertThrows(SceneFailure.class,()->service.start(altered,actor)).getMessage());
        assertEquals(1,f.jdbc.queryForObject("SELECT COUNT(*) FROM sys_ai_sql_assistance",Integer.class));
    }
    @Test void pollingAndCancelAreOwnerTenantScopedAndCancellationStopsPrivateUpstream()throws Exception {
        http.hold=true;var accepted=service.start(input(),actor);assertTrue(http.entered.await(2,TimeUnit.SECONDS));long id=Long.parseLong(accepted.requestId());
        for(var other:List.of(new TrustedActor("102","tenant_a",actor.authorities(),null),new TrustedActor("101","tenant_b",actor.authorities(),null))) {
            assertEquals(404,assertThrows(SceneFailure.class,()->service.get(id,other)).status());assertEquals(404,assertThrows(SceneFailure.class,()->service.cancel(id,other)).status());
        }
        assertEquals(SqlAssistanceDtos.Status.CANCELLED,service.cancel(id,actor).status());Instant until=Instant.now().plusSeconds(2);
        while(!http.pending.isCancelled()&&Instant.now().isBefore(until))Thread.sleep(5);assertTrue(http.pending.isCancelled());assertEquals(1,http.generations.get());
    }
    @Test void schemaChangeAfterApprovalRequiresReviewInsteadOfReturningOldCandidate()throws Exception {
        var result=finished(service.start(input(),actor).requestId());query.fingerprint="schema-v2";
        assertEquals("SCHEMA_CHANGED",assertThrows(SceneFailure.class,()->service.get(Long.parseLong(result.requestId()),actor)).getMessage());assertEquals(1,http.generations.get());
    }
    @Test void realExecutorFallbackRechecksSchemaBeforeEachAttemptAndKeepsLedger()throws Exception {
        http.primaryStatus=503;var result=finished(service.start(input(),actor).requestId());assertEquals(SqlAssistanceDtos.Status.SUCCEEDED,result.status());assertEquals(2,http.generations.get());
        assertTrue(http.schemaChecksAtAttempt.get(0)>=2);assertTrue(http.schemaChecksAtAttempt.get(1)>http.schemaChecksAtAttempt.get(0));
        assertEquals(List.of("FIRST","NEXT_KEY"),f.jdbc.query("SELECT switch_reason FROM sys_ai_request_attempt ORDER BY attempt_number",(r,n)->r.getString(1)));
        assertTrue(f.jdbc.queryForObject("SELECT SUM(output_token_limit) FROM sys_ai_request_attempt",Long.class)<=1024);
        http.secondaryStatus=503;var failed=finished(service.start(input(),actor).requestId());assertEquals(SqlAssistanceDtos.Status.FAILED,failed.status());assertEquals("PROVIDER_UNAVAILABLE",failed.errorCode());assertNull(failed.query());
    }
    @Test void partialProviderOutputNeverBecomesCandidateOrTriggersSplicedFallback()throws Exception {
        http.partial=true;var result=finished(service.start(input(),actor).requestId());assertEquals(SqlAssistanceDtos.Status.INCOMPLETE,result.status());assertNull(result.query());assertEquals(1,http.generations.get());assertEquals(0,query.validations.get());
    }
    @Test void fixedQueryFailuresMapToSafeHttpCodesWithoutProviderCalls() {
        for(var issue:List.of(QueryIssue.DATASET_FORBIDDEN,QueryIssue.SCHEMA_CHANGED,QueryIssue.READ_ONLY_UNAVAILABLE,QueryIssue.QUERY_TIMEOUT)) {
            query.issue=issue;var failure=assertThrows(SceneFailure.class,()->service.start(input(),actor));
            assertEquals(new com.xinghuiTec.ai.query.SqlQueryException(issue).status(),failure.status());assertEquals("AI_QUERY_"+issue.name(),failure.getMessage());
        }
        assertEquals(0,http.generations.get());
    }
    @Configuration @EnableMethodSecurity static class Security {}
    private void login(boolean testRight) {
        var user=new SysUser();user.setUserId(101L);user.setTenantId("tenant_a");var rights=new ArrayList<>(actor.authorities());if(!testRight)rights.remove("ai:scene:test");
        var login=new LoginUser(user,rights);login.setTenantId("tenant_a");SecurityContextHolder.getContext().setAuthentication(new UsernamePasswordAuthenticationToken(login,null,login.getAuthorities()));
    }
    @Test void actualHttpRejectsMissingPermissionIdentityAndSystemPromptFields()throws Exception {
        try(var context=new AnnotationConfigApplicationContext()) {
            context.register(Security.class);context.registerBean("platformAccess",PlatformAccessService.class,()->new PlatformAccessService(false));
            context.registerBean(SqlAssistanceController.class,()->new SqlAssistanceController(service,new AiInvocationIdentity(),new AiRuntimeBodyCodec()));context.refresh();
            var mvc=MockMvcBuilders.standaloneSetup(context.getBean(SqlAssistanceController.class)).setControllerAdvice(new SceneHttpErrors()).addFilters(new AiRuntimeBoundaryFilter(f.mapper)).build();
            String body=f.mapper.writeValueAsString(input());login(false);
            mvc.perform(post("/ai/scenes/sql-assistance").contentType("application/json").content(body)).andExpect(status().isForbidden());login(true);
            for(String forbidden:List.of("actor","tenant","owner","scope","systemPrompt")) mvc.perform(post("/ai/scenes/sql-assistance").contentType("application/json").content(body.substring(0,body.length()-1)+",\""+forbidden+"\":\"forged\"}")).andExpect(status().isBadRequest());
            var withoutConsent=(com.fasterxml.jackson.databind.node.ObjectNode)f.mapper.readTree(body);withoutConsent.put("confirmedCosts",false);
            mvc.perform(post("/ai/scenes/sql-assistance").contentType("application/json").content(withoutConsent.toString())).andExpect(status().isBadRequest());
            assertEquals(0,http.generations.get());
            var accepted=mvc.perform(post("/ai/scenes/sql-assistance").contentType("application/json").content(body)).andExpect(status().isAccepted()).andExpect(jsonPath("$.data.requestId").isString()).andReturn();
            finished(f.mapper.readTree(accepted.getResponse().getContentAsString()).path("data").path("requestId").asText());
        }
    }
    /** Authorization/AST seam fixture, not proof of C's parser or native database execution. */
    private static final class QueryBoundary implements AiSceneQueryRuntime {
        final AtomicInteger schemas=new AtomicInteger(),validations=new AtomicInteger();volatile boolean deny,reject;volatile QueryIssue issue;volatile String fingerprint="schema-v1";
        final List<QueryColumn> columns=List.of(new QueryColumn("subject","主题","VARCHAR",true));
        public AuthorizedSchema authorizedSchema(TrustedActor actor,QueryContext context,InvocationControl control){control.check();schemas.incrementAndGet();if(issue!=null)throw new com.xinghuiTec.ai.query.SqlQueryException(issue);if(deny)throw new AiRuntimeException(ErrorCode.RESOURCE_FORBIDDEN);return new AuthorizedSchema(List.of(new DatasetSchema("lowcode.1","form_1","主题数据","SCHEMA_PRIVATE_SENTINEL",ScopeKind.TENANT,"仅当前租户可读数据",columns,fingerprint)),List.of("single SELECT","WHERE","named parameters"),List.of("current_record"),"仅当前租户可读数据",fingerprint);}
        public SqlValidation validateGeneratedSql(SqlQuery sql,TrustedActor actor,QueryContext context,InvocationControl control){control.check();validations.incrementAndGet();return new SqlValidation(!reject,reject?null:sql.sql(),columns,reject?List.of(new QueryDiagnostic("sql",QueryIssue.INVALID_SQL)):List.of(),"query-specific-fingerprint");}
        public CompiledQuery compile(SqlQuery q,TrustedActor a,QueryContext c,InvocationControl i){throw new AssertionError("generation must not compile or execute saved references");}
        public QueryResult preview(SqlQuery q,TrustedActor a,QueryContext c,InvocationControl i){throw new AssertionError("generation must not execute SQL");}
        public QueryResult read(CompiledQuery q,TrustedActor a,QueryContext c,InvocationControl i){throw new AssertionError("generation must not execute SQL");}
        public void authorize(CompiledQuery q,TrustedActor a,QueryContext c,InvocationControl i){throw new AssertionError("generation uses current schema metadata only");}
    }
}
