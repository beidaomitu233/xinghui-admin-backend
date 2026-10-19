package com.xinghuiTec.ai.application;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.xinghuiTec.ai.contract.*;
import com.xinghuiTec.ai.contract.AiProviderRuntime.*;
import com.xinghuiTec.ai.contract.AiTypes.*;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.support.StaticListableBeanFactory;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DataSourceTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

import java.time.Instant;
import java.util.*;
import java.util.function.BiFunction;

import static org.junit.jupiter.api.Assertions.*;

/** Real H2 policy/state transitions, explicitly simulated provider and template boundaries. */
class AiRequestExecutorTest {
    private final TrustedActor actor = new TrustedActor("101", "platform", Set.of("lowcode:record:query"), null);
    private final AiRequestStore.Owner owner = new AiRequestStore.Owner("101", "platform");
    private JdbcTemplate jdbc;
    private AiApplicationStore applications;
    private AiRequestStore requests;
    private AiRequestLifecycle lifecycle;
    private AiRequestExecutor executor;
    private AiRequestExecutor secondInstance;
    private TransactionTemplate transaction;
    private TestProvider provider;
    private TestTemplate template;
    private TestEvents events;
    private AiRequestJournal journal;

    @BeforeEach
    void setup() throws Exception {
        var source = AiApplicationTestDatabase.create();
        jdbc = new JdbcTemplate(source);
        var manager = new DataSourceTransactionManager(source);
        transaction = new TransactionTemplate(manager);
        applications = new AiApplicationStore(jdbc, new ObjectMapper());
        requests = new AiRequestStore(jdbc);
        events = new TestEvents();
        lifecycle = new AiRequestLifecycle(requests, events, manager);
        journal = new AiRequestJournal(jdbc, requests, manager);
        provider = new TestProvider(); template = new TestTemplate();
        var beans = new StaticListableBeanFactory();
        beans.addBean("provider", provider); beans.addBean("template", template);
        executor = new AiRequestExecutor(requests, applications, journal, lifecycle, new AiSharedModelCursor(jdbc), events,
                beans.getBeanProvider(AiProviderRuntime.class), beans.getBeanProvider(AiTemplateRuntime.class), jdbc, manager);
        secondInstance = new AiRequestExecutor(requests, applications, journal, lifecycle, new AiSharedModelCursor(jdbc), events,
                beans.getBeanProvider(AiProviderRuntime.class), beans.getBeanProvider(AiTemplateRuntime.class), jdbc, manager);
    }

    @Test
    void safeProviderTraceAndActualTargetReachTheAttemptJournal() {
        provider.add("m1", "k1");
        provider.action = (request, observer) -> {
            observer.onDelta("simulated summary");
            return new ExecutionOutcome(ExecutionStatus.SUCCEEDED, null, false, true, new Usage(1L, 1L, 2L), "gen-neutral-001", 1);
        };
        var policy = policy(AiApplicationPolicy.ModelMode.FIXED, false, 2, 64, binding("m1"));
        var work = work(application(policy), policy); executor.execute(work);
        var row = jdbc.queryForMap("SELECT * FROM sys_ai_request_attempt WHERE request_id=?", Long.parseLong(work.invocation().requestId()));
        assertEquals("gen-neutral-001", row.get("provider_request_id"));
        assertEquals("SIMULATED", row.get("provider_code")); assertEquals("test-model", row.get("model_code"));
        assertEquals("kv1", row.get("key_version")); assertEquals(64, row.get("output_token_limit"));
    }

    @Test
    void unavailablePrimaryUsesOnlyExplicitBackupAndRecordsFallbackEvenWithoutAPrimaryAttempt() {
        provider.add("m2", "k2");
        var policy = policy(AiApplicationPolicy.ModelMode.FIXED, true, 2, 64, binding("m1"), binding("m2"));
        var work = work(application(policy), policy); executor.execute(work);
        assertEquals(AiRequestStatus.SUCCEEDED, result(work).status());
        assertEquals(List.of("m2"), provider.calls.stream().map(call -> call.target().model().modelRef()).toList());
        assertEquals("FALLBACK_MODEL", jdbc.queryForObject("SELECT switch_reason FROM sys_ai_request_attempt WHERE request_id=?", String.class, Long.parseLong(work.invocation().requestId())));
    }

    @Test
    void primaryRevokedBetweenSelectionAndAttemptCanOnlyUseAnExplicitBackup() {
        provider.add("m1", "k1"); provider.add("m2", "k2");
        for (boolean fallback : List.of(true, false)) {
            var checks = new java.util.concurrent.atomic.AtomicInteger();
            provider.calls.clear();
            provider.beforeResolve = binding -> {
                if (binding.model().modelRef().equals("m1") && checks.incrementAndGet() > 1)
                    throw new AiRuntimeException(ErrorCode.MODEL_UNAVAILABLE);
            };
            var policy = policy(AiApplicationPolicy.ModelMode.FIXED, fallback, 2, 64, binding("m1"), binding("m2"));
            var work = work(application(policy), policy); executor.execute(work);
            assertEquals(fallback ? AiRequestStatus.SUCCEEDED : AiRequestStatus.FAILED, result(work).status());
            assertEquals(fallback ? 1 : 0, provider.calls.size());
            if (fallback) assertEquals("m2", provider.calls.get(0).target().model().modelRef());
        }
    }

    @Test
    void savedRoundRobinOrderIsSharedAcrossWorkersAndNotWeightedByKeyCount() {
        provider.add("m1", "k1", "k2", "k3"); provider.add("m2", "k1"); provider.add("m3", "k1");
        var policy = policy(AiApplicationPolicy.ModelMode.ROUND_ROBIN, false, 3, 256, binding("m1"), binding("m2"), binding("m3"));
        long[] app = application(policy);
        for (int index = 0; index < 4; index++) {
            var work = work(app, policy);
            (index % 2 == 0 ? executor : secondInstance).execute(work);
            assertEquals(AiRequestStatus.SUCCEEDED, result(work).status());
        }
        assertEquals(List.of("m1", "m2", "m3", "m1"), provider.calls.stream().map(call -> call.target().model().modelRef()).toList());
    }

    @Test
    void sharedProviderKeyCanBeTriedForSecondModelWithoutLeakingExclusionsAcrossModels() {
        provider.add("m1", "shared"); provider.add("m2", "shared");
        provider.action = (request, observer) -> request.target().model().modelRef().equals("m1")
                ? retryable() : success(observer);
        var policy = policy(AiApplicationPolicy.ModelMode.FIXED, true, 3, 256, binding("m1"), binding("m2"));
        var work = work(application(policy), policy); executor.execute(work);
        assertEquals(AiRequestStatus.SUCCEEDED, result(work).status());
        assertEquals(List.of("m1", "m2"), provider.calls.stream().map(call -> call.target().model().modelRef()).toList());
        assertEquals(List.of("shared", "shared"), provider.calls.stream().map(call -> call.target().key().keyRef()).toList());
        assertNull(result(work).inputTokens());
        assertNull(result(work).outputTokens());
        assertFalse(result(work).usageKnown());
    }

    @Test
    void fixedKeyNeverUsesUnapprovedAlternateKey() {
        provider.add("m1", "fixed", "other"); provider.action = (request, observer) -> retryable();
        var fixed = new ModelBinding(new ModelVersionRef("m1", "cv1"), KeyMode.FIXED, Set.of(), new KeyRef("fixed", "kv1"), List.of());
        var policy = policy(AiApplicationPolicy.ModelMode.FIXED, false, 3, 256, fixed);
        var work = work(application(policy), policy); executor.execute(work);
        assertEquals(AiRequestStatus.FAILED, result(work).status());
        assertEquals(1, provider.calls.size());
        assertEquals("fixed", provider.calls.get(0).target().key().keyRef());
    }

    @Test
    void attemptCountAndConservativeTokenReservationsHaveOneGlobalBudget() {
        provider.add("m1", "k1", "k2", "k3"); provider.action = (request, observer) -> retryable();
        var policy = policy(AiApplicationPolicy.ModelMode.FIXED, false, 2, 7, binding("m1"));
        var work = work(application(policy), policy); executor.execute(work);
        assertEquals(2, provider.calls.size());
        assertEquals(List.of(3, 4), provider.calls.stream().map(ExecutionRequest::maxTokens).toList());
        assertEquals(2, result(work).attemptCount());
        assertEquals(AiRequestStatus.FAILED, result(work).status());
        assertNull(result(work).outputTokens());
    }

    @Test
    void partialOutputFailureDoesNotSwitchKeyOrAppendASecondGeneration() {
        provider.add("m1", "k1", "k2");
        provider.action = (request, observer) -> {
            observer.onDelta("simulated partial");
            return new ExecutionOutcome(ExecutionStatus.FAILED, ErrorCode.PROVIDER_UNAVAILABLE, false, true, null, null, 1);
        };
        var policy = policy(AiApplicationPolicy.ModelMode.FIXED, false, 3, 256, binding("m1"));
        var work = work(application(policy), policy); executor.execute(work);
        assertEquals(1, provider.calls.size());
        assertEquals(AiRequestStatus.INCOMPLETE, result(work).status());
        assertTrue(result(work).outputStarted());
        assertFalse(events.after(work.invocation().requestId(), 0).stream().anyMatch(event -> event.name() == AiRuntimeEvents.Name.COMPLETED));
    }

    @Test
    void invalidInputAndContentRefusalStopEvenIfProviderIncorrectlyCallsThemRetryable() {
        provider.add("m1", "k1", "k2");
        var policy = policy(AiApplicationPolicy.ModelMode.FIXED, false, 3, 256, binding("m1"));
        long[] app = application(policy);
        for (ErrorCode code : List.of(ErrorCode.INVALID_INPUT, ErrorCode.CONTENT_REJECTED)) {
            provider.action = (request, observer) -> new ExecutionOutcome(ExecutionStatus.FAILED, code, true, false, null, null, 1);
            int before = provider.calls.size();
            var work = work(app, policy); executor.execute(work);
            assertEquals(before + 1, provider.calls.size());
            assertEquals(code.name(), result(work).errorCode());
        }
    }

    @Test
    void revokedResourceAndUnavailableBufferStopBeforeProviderTransport() {
        provider.add("m1", "k1");
        var policy = policy(AiApplicationPolicy.ModelMode.FIXED, false, 3, 256, binding("m1"));
        long[] app = application(policy);
        template.error = ErrorCode.RESOURCE_FORBIDDEN;
        var revoked = work(app, policy); executor.execute(revoked);
        assertEquals(ErrorCode.RESOURCE_FORBIDDEN.name(), result(revoked).errorCode());
        assertTrue(provider.calls.isEmpty());
        template.error = null;
        var unavailable = work(app, policy); events.unavailable = true; executor.execute(unavailable);
        assertEquals(ErrorCode.DEPENDENCY_UNAVAILABLE.name(), result(unavailable).errorCode());
        assertTrue(provider.calls.isEmpty());
    }

    @Test
    void cancellationAndDuplicateDispatchCannotLaunchAnotherAttemptOrOverwriteTerminalState() {
        provider.add("m1", "k1", "k2");
        var policy = policy(AiApplicationPolicy.ModelMode.FIXED, false, 3, 256, binding("m1"));
        long[] app = application(policy);
        var cancelled = work(app, policy);
        provider.action = (request, observer) -> {
            lifecycle.finish(Long.parseLong(request.invocation().requestId()), owner, AiRequestStatus.CANCELLED, ErrorCode.CANCELLED, null);
            observer.onDelta("must not escape cancellation");
            return success(observer);
        };
        executor.execute(cancelled);
        assertEquals(AiRequestStatus.CANCELLED, result(cancelled).status());
        assertEquals(1, provider.calls.size());
        secondInstance.execute(cancelled);
        assertEquals(AiRequestStatus.CANCELLED, result(cancelled).status());
        assertEquals(1, provider.calls.size());
    }

    @Test void telemetrySurvivesSuccessEmptyOutputAndPartialOutputReclassification(){
        var captured=org.mockito.Mockito.spy(journal);var beans=new StaticListableBeanFactory();beans.addBean("provider",provider);beans.addBean("template",template);
        var instrumented=new AiRequestExecutor(requests,applications,captured,lifecycle,new AiSharedModelCursor(jdbc),events,
                beans.getBeanProvider(AiProviderRuntime.class),beans.getBeanProvider(AiTemplateRuntime.class),jdbc,transaction.getTransactionManager());
        provider.add("m1","k1");var measured=AiCallLogServiceTest.telemetry();
        for(int mode=0;mode<3;mode++){
            final int variant=mode;provider.action=(request,observer)->{
                if(variant!=1)observer.onDelta("controlled output");
                return new ExecutionOutcome(variant==2?ExecutionStatus.FAILED:ExecutionStatus.SUCCEEDED,variant==2?ErrorCode.INVALID_RESPONSE:null,false,false,null,"fixture-id",27,measured);
            };
            var policy=policy(AiApplicationPolicy.ModelMode.FIXED,false,1,64,binding("m1"));instrumented.execute(work(application(policy),policy));
        }
        var outcomes=org.mockito.ArgumentCaptor.forClass(ExecutionOutcome.class);
        org.mockito.Mockito.verify(captured,org.mockito.Mockito.times(3)).complete(org.mockito.ArgumentMatchers.anyLong(),org.mockito.ArgumentMatchers.anyString(),outcomes.capture());
        assertEquals(List.of(ExecutionStatus.SUCCEEDED,ExecutionStatus.FAILED,ExecutionStatus.INCOMPLETE),outcomes.getAllValues().stream().map(ExecutionOutcome::status).toList());
        outcomes.getAllValues().forEach(outcome->assertSame(measured,outcome.telemetry()));
    }

    private AiApplicationPolicy policy(AiApplicationPolicy.ModelMode mode, boolean fallback, int attempts, int tokens, ModelBinding... bindings) {
        return new AiApplicationPolicy(mode, List.of(bindings), fallback, new TemplateVersionRef("t1", "tv1"), Set.of("user_request"),
                new Requirements(OutputFormat.TEXT, true, false), AiApplicationPolicy.Scope.FRONTEND,
                Set.of(), Set.of("lowcode:record:query"), "1", 30, attempts, 2, tokens);
    }
    private ModelBinding binding(String model) {
        return new ModelBinding(new ModelVersionRef(model, "cv1"), KeyMode.AUTO, Set.of(), null, List.of());
    }
    private long[] application(AiApplicationPolicy policy) {
        long app = Long.parseLong(applications.create(new AiApplicationStore.Draft("simulated", "test fixture", "lowcode.record.summary/1", policy), 101));
        long version = 1000 + app;
        jdbc.update("INSERT INTO sys_ai_application_version(app_version_id,app_id,version_number,draft_revision,snapshot_json,tested_request_id,created_by)"
                + " VALUES(?,?,1,1,'{}',1,101)", version, app);
        jdbc.update("UPDATE sys_ai_application SET enabled=TRUE,published_version_id=? WHERE app_id=?", version, app);
        jdbc.update("INSERT INTO sys_ai_application_cursor VALUES(?,0)", version);
        return new long[] {app, version};
    }
    private AiRequestExecutor.Work work(long[] app, AiApplicationPolicy policy) {
        Instant now = Instant.now();
        var accepted = transaction.execute(status -> requests.accept(new AiRequestStore.Admission(app[0], app[1], null,
                AiRequestStore.Kind.BUSINESS, owner, "lowcode.record", "1:7", UUID.randomUUID().toString(), "a".repeat(64), now.plusSeconds(30)), now));
        lifecycle.accepted(Long.parseLong(accepted.request().requestId()), owner);
        return new AiRequestExecutor.Work(new Invocation(actor, Long.toString(app[0]), Long.toString(app[1]),
                new BusinessRef("lowcode.record", "1:7"), accepted.request().requestId()), policy,
                List.of(new AiMessage(MessageRole.USER, "neutral simulated fixture")), List.of(), Set.of("user_request"), false);
    }
    private AiRequestStore.Request result(AiRequestExecutor.Work work) {
        return requests.owned(Long.parseLong(work.invocation().requestId()), owner);
    }
    private static ExecutionOutcome retryable() {
        return new ExecutionOutcome(ExecutionStatus.FAILED, ErrorCode.RATE_LIMITED, true, false, null, null, 1);
    }
    private static ExecutionOutcome success(StreamObserver observer) {
        observer.onDelta("simulated summary");
        return new ExecutionOutcome(ExecutionStatus.SUCCEEDED, null, false, true, new Usage(1L, 1L, 2L), null, 1);
    }
    private static final class TestProvider implements AiProviderRuntime {
        final Map<String, List<Target>> models = new HashMap<>();
        final List<ExecutionRequest> calls = new ArrayList<>();
        BiFunction<ExecutionRequest, StreamObserver, ExecutionOutcome> action = (request, observer) -> success(observer);
        java.util.function.Consumer<ModelBinding> beforeResolve = ignored -> {};
        void add(String model, String... keys) {
            models.put(model, Arrays.stream(keys).map(key -> new Target(new ModelVersionRef(model, "cv1"), new KeyRef(key, "kv1"),
                    "SIMULATED", "test-model", "masked-fixture", new Capabilities(Set.of(OutputFormat.TEXT), true, false))).toList());
        }
        @Override public List<AvailableModel> availableModels(Requirements requirements) { throw new AssertionError(); }
        @Override public Validation validateBindings(List<ModelBinding> bindings, Requirements requirements) { return new Validation(List.of()); }
        @Override public List<Target> resolveTargets(ModelBinding binding, Set<KeyRef> tried, InvocationControl control) {
            beforeResolve.accept(binding);
            return models.getOrDefault(binding.model().modelRef(), List.of()).stream().filter(target -> !tried.contains(target.key())).toList();
        }
        @Override public ExecutionOutcome execute(ExecutionRequest request, StreamObserver observer, InvocationControl control) {
            calls.add(request); return action.apply(request, observer);
        }
    }
    private static final class TestTemplate implements AiTemplateRuntime {
        ErrorCode error;
        @Override public PublishedTemplate publishedSnapshot(TemplateVersionRef version, Invocation invocation, InvocationControl control) {
            return new PublishedTemplate(version, List.of(), List.of());
        }
        @Override public Validation validateBinding(TemplateVersionRef version, Set<String> inputs, Invocation invocation, InvocationControl control) {
            return new Validation(error == null ? List.of() : List.of(new Violation("resource", error)));
        }
        @Override public RenderedTemplate renderPublished(RenderRequest request, InvocationControl control) { throw new AssertionError(); }
    }
    private static final class TestEvents implements AiRuntimeEvents {
        final Map<String, List<Event>> rows = new HashMap<>();
        boolean unavailable;
        @Override public Event append(Event event) {
            var events = rows.computeIfAbsent(event.requestId(), ignored -> new ArrayList<>());
            Event sequenced = event.sequenced(events.size() + 1L); events.add(sequenced); return sequenced;
        }
        @Override public List<Event> after(String id, long sequence) {
            if (unavailable) throw new AiRuntimeException(ErrorCode.DEPENDENCY_UNAVAILABLE);
            return rows.getOrDefault(id, List.of()).stream().filter(event -> event.sequence() > sequence).toList();
        }
    }
}
