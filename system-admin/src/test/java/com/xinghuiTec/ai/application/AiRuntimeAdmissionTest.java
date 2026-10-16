package com.xinghuiTec.ai.application;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.xinghuiTec.ai.contract.*;
import com.xinghuiTec.ai.contract.AiProviderRuntime.*;
import com.xinghuiTec.ai.contract.AiTemplateRuntime.*;
import com.xinghuiTec.ai.contract.AiTypes.*;
import com.xinghuiTec.config.SecretCipher;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.support.StaticListableBeanFactory;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DataSourceTransactionManager;

import java.time.Duration;
import java.util.*;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.jupiter.api.Assertions.*;

/** Admission/SQL/async worker integration with test-only A/C/buffer doubles, no live provider. */
class AiRuntimeAdmissionTest {
    private final TrustedActor actor = new TrustedActor("101", "platform", Set.of("lowcode:record:query"), null);
    private JdbcTemplate jdbc;
    private AiRuntimeService runtime;
    private AiRequestStore requests;
    private TestTemplate template;
    private TestProvider provider;
    private long appId;
    private AiApplicationStore apps;
    private AiApplicationService versions;
    private final AtomicReference<String> master = new AtomicReference<>(Base64.getEncoder().encodeToString(new byte[32]));

    @BeforeEach
    void setup() throws Exception {
        var source = AiApplicationTestDatabase.create();
        jdbc = new JdbcTemplate(source);
        var manager = new DataSourceTransactionManager(source);
        var mapper = new ObjectMapper();
        apps = new AiApplicationStore(jdbc, mapper);
        requests = new AiRequestStore(jdbc);
        var journal = new AiRequestJournal(jdbc, requests, manager);
        var events = new TestEvents();
        var lifecycle = new AiRequestLifecycle(requests, events, manager);
        provider = new TestProvider(); template = new TestTemplate();
        var beans = new StaticListableBeanFactory(); beans.addBean("provider", provider); beans.addBean("template", template);
        var providerBeans = beans.getBeanProvider(AiProviderRuntime.class);
        var templateBeans = beans.getBeanProvider(AiTemplateRuntime.class);
        versions = new AiApplicationService(jdbc, mapper, apps, new JdbcAiApplicationReferences(jdbc, manager), providerBeans, templateBeans);
        var worker = new AiRequestExecutor(requests, apps, journal, lifecycle, new AiSharedModelCursor(jdbc), events,
                providerBeans, templateBeans, jdbc, manager);
        runtime = new AiRuntimeService(apps, versions, requests, journal, lifecycle, worker, templateBeans, jdbc, mapper, manager, new SecretCipher(master::get));
        var policy = new AiApplicationPolicy(AiApplicationPolicy.ModelMode.FIXED,
                List.of(new ModelBinding(new ModelVersionRef("simulated-model", "cv1"), KeyMode.AUTO, Set.of(), null, List.of())), false,
                new TemplateVersionRef("t1", "tv1"), Set.of("user_request"), new Requirements(OutputFormat.TEXT, true, false),
                AiApplicationPolicy.Scope.FRONTEND, Set.of(), Set.of("lowcode:record:query"), "1", 30, 2, 2, 128);
        appId = Long.parseLong(apps.create(new AiApplicationStore.Draft("test app", "simulation fixture", "lowcode.record.summary/1", policy), 101));
        var snapshot = new AiApplicationService.Snapshot(policy, List.of(), List.of(new AiApplicationService.TestEvidence("1", policy.models().get(0).model())));
        jdbc.update("INSERT INTO sys_ai_application_version(app_version_id,app_id,version_number,draft_revision,snapshot_json,tested_request_id,created_by)"
                + " VALUES(1001,?,1,1,?,1,101)", appId, mapper.writeValueAsString(snapshot));
        jdbc.update("UPDATE sys_ai_application SET published_version_id=1001,enabled=TRUE WHERE app_id=?", appId);
        jdbc.update("INSERT INTO sys_ai_application_cursor VALUES(1001,0)");
    }

    @AfterEach void stop() { if (runtime != null) runtime.stop(); }

    @Test
    void ordinaryInputOnlyApplicationTestsPublishesAndRunsWithPinnedIdentityAndIdempotency() {
        var source = apps.get(appId).draft();
        var policy = new AiApplicationPolicy(source.modelMode(), source.models(), source.modelFallback(), source.template(), source.inputNames(),
                source.requirements(), source.scope(), Set.of("101"), Set.of(), null, source.timeoutSeconds(), source.maxAttempts(), source.maxConcurrent(), source.maxTokens());
        long genericId = Long.parseLong(apps.create(new AiApplicationStore.Draft("ordinary", "rewrite input", "business.rewrite", policy), 101));
        var submission = new AiRuntimeService.Submission(new BusinessRef("business.rewrite", "job-ABC:17"), Map.of("user_request", "ordinary neutral fixture"), UUID.randomUUID().toString());
        var test = runtime.test(genericId, 0, 0, submission, actor);
        assertEquals(AiRequestStatus.SUCCEEDED, await(test).status());
        assertEquals("draft:1", template.lastInvocation.appVersionId());
        var published = versions.publish(genericId, 0, List.of(test.requestId()), actor);
        var replayInput = new AiRuntimeService.Submission(submission.businessRef(), submission.inputs(), UUID.randomUUID().toString());
        var business = runtime.submit(genericId, replayInput, actor);
        assertEquals(AiRequestStatus.SUCCEEDED, await(business).status());
        assertEquals(published.appVersionId(), business.appVersionId());
        assertSame(actor, template.lastInvocation.actor());
        assertEquals("job-ABC:17", template.lastInvocation.businessRef().id());
        assertNull(template.lastInvocation.actor().servicePrincipalRef());
        assertEquals(2, provider.calls.get());
        // The same business nonce returns the same request; no additional template/model invocation.
        assertEquals(business.requestId(), runtime.submit(genericId, replayInput, actor).requestId());
        assertEquals(2, provider.calls.get());
        var other = new TrustedActor("102", "platform", actor.authorities(), null);
        assertEquals(ErrorCode.FORBIDDEN, assertThrows(AiRuntimeException.class, () -> runtime.submit(genericId, replayInput, other)).code());
        assertEquals(ErrorCode.NOT_FOUND, assertThrows(AiRuntimeException.class, () -> runtime.status(Long.parseLong(business.requestId()), other)).code());
        template.failure = ErrorCode.RESOURCE_FORBIDDEN;
        assertEquals(ErrorCode.RESOURCE_FORBIDDEN, assertThrows(AiRuntimeService.AdmissionFailure.class,
                () -> runtime.submit(genericId, new AiRuntimeService.Submission(submission.businessRef(), submission.inputs(), UUID.randomUUID().toString()), actor)).code());
        assertEquals(2, provider.calls.get()); // generic reference never bypasses C's explicit denial
    }

    @Test
    void backgroundConfigurationTestUsesRealHumanSubjectWithoutImpersonatingService() throws Exception {
        configureBackgroundFixture();
        var accepted = runtime.test(appId, 0, 0, input("neutral", UUID.randomUUID().toString()), actor);
        assertEquals(AiRequestStore.Kind.TEST, accepted.kind());
        assertNull(template.lastInvocation.actor().servicePrincipalRef());
        assertEquals(actor.subjectId(), template.lastInvocation.actor().subjectId());
        assertEquals(AiRequestStatus.SUCCEEDED, await(accepted).status());
        assertDoesNotThrow(() -> runtime.authorizeEvents(runtime.status(Long.parseLong(accepted.requestId()), actor), actor));
    }

    @Test
    void backgroundBusinessCallsStillRejectHumanSubjects() throws Exception {
        configureBackgroundFixture();
        assertEquals(ErrorCode.FORBIDDEN, assertThrows(AiRuntimeException.class,
                () -> runtime.submit(appId, input("neutral", UUID.randomUUID().toString()), actor)).code());
        assertEquals(0, template.renders.get()); assertEquals(0, provider.calls.get());
        assertEquals(0, jdbc.queryForObject("SELECT COUNT(*) FROM sys_ai_request", Integer.class));
    }

    @Test
    void configurationTestNeverBypassesAllowedSubjects() throws Exception {
        configureBackgroundFixture();
        var adminOnly = new TrustedActor("999", "platform", Set.of("ai:application:test", "ai:application:publish"), null);
        assertEquals(ErrorCode.FORBIDDEN, assertThrows(AiRuntimeException.class,
                () -> runtime.test(appId, 0, 0, input("neutral", UUID.randomUUID().toString()), adminOnly)).code());
        assertEquals(0, template.renders.get()); assertEquals(0, provider.calls.get());
    }

    private void configureBackgroundFixture() throws Exception {
        // Test setup only; no production mutation of an immutable released version.
        var mapper = new ObjectMapper();
        var draft = (com.fasterxml.jackson.databind.node.ObjectNode) mapper.readTree(
                jdbc.queryForObject("SELECT draft_json FROM sys_ai_application WHERE app_id=?", String.class, appId));
        draft.put("scope", "BACKGROUND");
        jdbc.update("UPDATE sys_ai_application SET draft_json=? WHERE app_id=?", mapper.writeValueAsString(draft), appId);
        var version = (com.fasterxml.jackson.databind.node.ObjectNode) mapper.readTree(
                jdbc.queryForObject("SELECT snapshot_json FROM sys_ai_application_version WHERE app_version_id=1001", String.class));
        ((com.fasterxml.jackson.databind.node.ObjectNode) version.get("policy")).put("scope", "BACKGROUND");
        jdbc.update("UPDATE sys_ai_application_version SET snapshot_json=? WHERE app_version_id=1001", mapper.writeValueAsString(version));
    }

    @Test
    void missingMasterFailsOnlyTheInvocationBeforeAnyAdmissionOrProviderCall() {
        master.set(null);
        assertEquals(ErrorCode.CREDENTIAL_UNAVAILABLE, assertThrows(AiRuntimeException.class,
                () -> runtime.submit(appId, input("neutral", UUID.randomUUID().toString()), actor)).code());
        assertEquals(0, provider.calls.get());
        assertEquals(0, template.renders.get());
        assertEquals(0, jdbc.queryForObject("SELECT COUNT(*) FROM sys_ai_request", Integer.class));
    }

    @Test
    void acceptedIdAndPinnedVersionSurviveReplayWithoutRenderingOrDispatchingAgain() {
        var input = input("private input fixture", UUID.randomUUID().toString());
        var accepted = runtime.submit(appId, input, actor);
        assertEquals(AiRequestStatus.QUEUED, accepted.status());
        assertEquals("1001", accepted.appVersionId());
        assertEquals(AiRequestStatus.SUCCEEDED, await(accepted).status());
        var replay = runtime.submit(appId, input, actor);
        assertEquals(accepted.requestId(), replay.requestId());
        assertEquals(1, template.renders.get());
        assertEquals(1, provider.calls.get());
        assertEquals("101", template.lastInvocation.actor().subjectId());
        assertEquals("1:7", template.lastInvocation.businessRef().id());
        assertFalse(jdbc.queryForMap("SELECT * FROM sys_ai_request WHERE request_id=?", Long.parseLong(accepted.requestId()))
                .values().stream().anyMatch(value -> value != null && value.toString().contains("private input fixture")));
    }

    @Test
    void missingRequiredInputOrForbiddenResourceRecordsFailureAndMakesZeroProviderCalls() {
        for (ErrorCode code : List.of(ErrorCode.MISSING_VARIABLE, ErrorCode.RESOURCE_FORBIDDEN, ErrorCode.RESOURCE_TIMEOUT)) {
            template.failure = code;
            var failure = assertThrows(AiRuntimeService.AdmissionFailure.class,
                    () -> runtime.submit(appId, input("neutral", UUID.randomUUID().toString()), actor));
            assertEquals(code, failure.code());
            assertTrue(failure.requestId().matches("[1-9][0-9]*"));
            assertEquals(AiRequestStatus.FAILED, runtime.status(Long.parseLong(failure.requestId()), actor).status());
        }
        assertEquals(0, provider.calls.get());
    }

    @Test
    void trueActorAndBusinessReferenceAreRequiredBeforeAnyDataLookup() {
        var forbidden = new TrustedActor("999", "platform", Set.of("ai:application:publish"), null);
        assertEquals(ErrorCode.FORBIDDEN, assertThrows(AiRuntimeException.class,
                () -> runtime.submit(appId, input("neutral", UUID.randomUUID().toString()), forbidden)).code());
        var wrongRecord = new AiRuntimeService.Submission(new BusinessRef("lowcode.record", "2:7"), Map.of("user_request", "neutral"), UUID.randomUUID().toString());
        assertEquals(ErrorCode.INVALID_INPUT, assertThrows(AiRuntimeException.class,
                () -> runtime.submit(appId, wrongRecord, actor)).code());
        assertEquals(0, template.renders.get());
        assertEquals(0, jdbc.queryForObject("SELECT COUNT(*) FROM sys_ai_request", Integer.class));
    }

    @Test
    void conflictingNonceIsRejectedAndCannotLaunchAnotherGeneration() {
        String nonce = UUID.randomUUID().toString();
        var accepted = runtime.submit(appId, input("first", nonce), actor); await(accepted);
        assertEquals(ErrorCode.VERSION_CONFLICT, assertThrows(AiRuntimeException.class,
                () -> runtime.submit(appId, input("changed", nonce), actor)).code());
        assertEquals(1, template.renders.get());
        assertEquals(1, provider.calls.get());
    }

    @Test
    void testUsesServerDraftTagAndNeverReturnsAFabricatedPublishedVersionId() {
        var accepted = runtime.test(appId, 0, 0, input("neutral", UUID.randomUUID().toString()), actor);
        assertNull(accepted.appVersionId());
        assertEquals(1, accepted.draftRevision());
        assertEquals(AiRequestStore.Kind.TEST, accepted.kind());
        assertEquals("draft:1", template.lastInvocation.appVersionId());
        assertEquals(AiRequestStatus.SUCCEEDED, await(accepted).status());
    }

    @Test
    void crossTenantAndOtherOwnerCannotReadOrCancelTheOriginalRun() {
        var accepted = runtime.submit(appId, input("neutral", UUID.randomUUID().toString()), actor);
        await(accepted);
        for (TrustedActor other : List.of(new TrustedActor("999", "platform", actor.authorities(), null),
                new TrustedActor("101", "other-tenant", actor.authorities(), null))) {
            assertEquals(ErrorCode.NOT_FOUND, assertThrows(AiRuntimeException.class,
                    () -> runtime.status(Long.parseLong(accepted.requestId()), other)).code());
            assertEquals(ErrorCode.NOT_FOUND, assertThrows(AiRuntimeException.class,
                    () -> runtime.cancel(Long.parseLong(accepted.requestId()), other)).code());
        }
        assertEquals(AiRequestStatus.SUCCEEDED, runtime.status(Long.parseLong(accepted.requestId()), actor).status());
        assertEquals(1, provider.calls.get());
    }

    private AiRuntimeService.Submission input(String text, String nonce) {
        return new AiRuntimeService.Submission(new BusinessRef("lowcode.record", "1:7"), Map.of("user_request", text), nonce);
    }
    private AiRequestStore.Request await(AiRequestStore.Request accepted) {
        return assertTimeoutPreemptively(Duration.ofSeconds(5), () -> {
            AiRequestStore.Request result;
            do { result = runtime.status(Long.parseLong(accepted.requestId()), actor); if (!result.status().terminal()) Thread.sleep(5); }
            while (!result.status().terminal());
            return result;
        });
    }
    private static final class TestTemplate implements AiTemplateRuntime {
        final AtomicInteger renders = new AtomicInteger();
        volatile ErrorCode failure;
        volatile Invocation lastInvocation;
        @Override public PublishedTemplate publishedSnapshot(TemplateVersionRef version, Invocation invocation, InvocationControl control) {
            return new PublishedTemplate(version, List.of(), List.of());
        }
        @Override public Validation validateBinding(TemplateVersionRef version, Set<String> inputs, Invocation invocation, InvocationControl control) { return new Validation(List.of()); }
        @Override public RenderedTemplate renderPublished(RenderRequest request, InvocationControl control) {
            renders.incrementAndGet(); lastInvocation = request.invocation();
            if (failure != null) throw new AiRuntimeException(failure);
            return new RenderedTemplate(request.version(), List.of(new AiMessage(MessageRole.USER, request.inputs().values().get("user_request").toString())), List.of(), List.of());
        }
    }
    private static final class TestProvider implements AiProviderRuntime {
        final AtomicInteger calls = new AtomicInteger();
        @Override public List<AvailableModel> availableModels(Requirements requirements) { throw new AssertionError(); }
        @Override public Validation validateBindings(List<ModelBinding> models, Requirements requirements) { return new Validation(List.of()); }
        @Override public List<Target> resolveTargets(ModelBinding binding, Set<KeyRef> tried, InvocationControl control) {
            return List.of(new Target(binding.model(), new KeyRef("simulated-key", "kv1"), "SIMULATED", "simulation", "masked-test", new Capabilities(Set.of(OutputFormat.TEXT), true, false)));
        }
        @Override public ExecutionOutcome execute(ExecutionRequest request, StreamObserver observer, InvocationControl control) {
            calls.incrementAndGet(); observer.onDelta("simulated output");
            return new ExecutionOutcome(ExecutionStatus.SUCCEEDED, null, false, true, new Usage(1L, 1L, 2L), null, 1);
        }
    }
    private static final class TestEvents implements AiRuntimeEvents {
        final Map<String, List<Event>> rows = new HashMap<>();
        @Override public synchronized Event append(Event event) {
            var values = rows.computeIfAbsent(event.requestId(), ignored -> new ArrayList<>());
            Event result = event.sequenced(values.size() + 1L); values.add(result); return result;
        }
        @Override public synchronized List<Event> after(String id, long sequence) {
            return rows.getOrDefault(id, List.of()).stream().filter(value -> value.sequence() > sequence).toList();
        }
    }
}
