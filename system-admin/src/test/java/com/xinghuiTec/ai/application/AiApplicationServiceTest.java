package com.xinghuiTec.ai.application;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.xinghuiTec.ai.contract.*;
import com.xinghuiTec.ai.contract.AiTypes.*;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.support.StaticListableBeanFactory;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DataSourceTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

import java.sql.Timestamp;
import java.time.Instant;
import java.util.List;
import java.util.Set;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.*;

/** Provider/template mocks verify application logic only, not a real model or business flow. */
class AiApplicationServiceTest {
    private static final TrustedActor ACTOR = new TrustedActor("101", "platform", Set.of("lowcode:record:query"), null);
    private JdbcTemplate jdbc;
    private AiApplicationStore store;
    private AiRequestStore requests;
    private TransactionTemplate transaction;
    private AiApplicationService service;
    private TestProvider provider;
    private TestTemplate template;
    private JdbcAiApplicationReferences references;

    @BeforeEach
    void setup() throws Exception {
        var source = AiApplicationTestDatabase.create();
        jdbc = new JdbcTemplate(source);
        var manager = new DataSourceTransactionManager(source);
        transaction = new TransactionTemplate(manager);
        references = new JdbcAiApplicationReferences(jdbc, manager);
        store = new AiApplicationStore(jdbc, new ObjectMapper());
        requests = new AiRequestStore(jdbc);
        provider = new TestProvider();
        template = new TestTemplate();
        var beans = new StaticListableBeanFactory();
        beans.addBean("provider", provider);
        beans.addBean("template", template);
        service = new AiApplicationService(jdbc, new ObjectMapper(), store, references,
                beans.getBeanProvider(AiProviderRuntime.class), beans.getBeanProvider(AiTemplateRuntime.class));
    }

    @Test
    void testEvidenceReturnsOnlyCurrentExactCandidateMetadataWithoutBusinessOrIdentityFields() throws Exception {
        var policy = policy(List.of(binding("m1")), false, false);
        long id = create(policy);
        String correct = successfulTest(id, 1, "m1", "cv1");
        successfulTest(id, 1, "m1", "old-config");
        var evidence = service.testEvidence(id);
        assertEquals(1, evidence.size()); assertEquals(correct, evidence.get(0).requestId());
        var json = new ObjectMapper().findAndRegisterModules().valueToTree(evidence.get(0));
        var fields = new java.util.HashSet<String>(); json.fieldNames().forEachRemaining(fields::add);
        assertEquals(Set.of("requestId", "draftRevision", "model", "status", "finishedAt"), fields);
        store.updateDraft(id, 0, draft(policy));
        assertTrue(service.testEvidence(id).isEmpty());
    }

    @Test
    void healthChecksPinnedOnlineDependenciesWithoutRenderingExecutingOrCreatingRequests() {
        long id = create(policy(List.of(binding("m1")), false, false));
        assertEquals("UNPUBLISHED", service.health(id, ACTOR).state());
        String test = successfulTest(id, 1, "m1", "cv1");
        var online = service.publish(id, 0, List.of(test), ACTOR);
        assertEquals("AVAILABLE", service.health(id, ACTOR).state());
        provider.validation = new Validation(List.of(new Violation("models[0]", ErrorCode.MODEL_UNAVAILABLE)));
        var unavailable = service.health(id, ACTOR);
        assertEquals("UNAVAILABLE", unavailable.state());
        assertEquals(ErrorCode.MODEL_UNAVAILABLE, unavailable.reason());
        assertEquals(online.appVersionId(), unavailable.appVersionId());
        service.setEnabled(id, store.get(id).revision(), false, ACTOR);
        assertEquals("DISABLED", service.health(id, ACTOR).state());
        assertEquals(1, jdbc.queryForObject("SELECT COUNT(*) FROM sys_ai_request", Integer.class));
        assertEquals(1, jdbc.queryForObject("SELECT COUNT(*) FROM sys_ai_request_attempt", Integer.class));
    }

    @Test
    void publishesImmutableSnapshotsAndRollbackOnlyChangesNewRequestEntry() {
        long id = create(policy(List.of(binding("m1")), false, false));
        String test1 = successfulTest(id, 1, "m1", "cv1");
        var version1 = service.publish(id, 0, List.of(test1), ACTOR);
        var requestOwner = new AiRequestStore.Owner("101", "platform");
        Instant acceptedAt = Instant.now();
        var inFlight = transaction.execute(status -> requests.accept(new AiRequestStore.Admission(id,
                Long.parseLong(version1.appVersionId()), null, AiRequestStore.Kind.BUSINESS, requestOwner,
                "lowcode.record", "1:7", UUID.randomUUID().toString(), "b".repeat(64), acceptedAt.plusSeconds(30)), acceptedAt));
        assertEquals(1, version1.versionNumber());
        assertEquals("draft:1", template.invocations.get(0).appVersionId());
        assertEquals(1, references.modelReferences(new ModelVersionRef("m1", "cv1")).size());
        var app = store.get(id);
        var edited = store.updateDraft(id, app.revision(), draft(policy(List.of(binding("m2")), false, false)));
        assertEquals(version1.appVersionId(), edited.publishedVersionId());
        String test2 = successfulTest(id, 2, "m2", "cv1");
        var version2 = service.publish(id, edited.revision(), List.of(test2), ACTOR);
        assertEquals("m1", service.version(id, Long.parseLong(version1.appVersionId()), false)
                .snapshot().policy().models().get(0).model().modelRef());
        assertTrue(references.modelReferences(new ModelVersionRef("m1", "cv1")).isEmpty());
        assertEquals(version2.appVersionId(), references.modelReferences(new ModelVersionRef("m2", "cv1"))
                .get(0).appVersionId());
        var rolledBack = service.rollback(id, store.get(id).revision(), Long.parseLong(version1.appVersionId()), ACTOR);
        assertEquals(version1.appVersionId(), rolledBack.publishedVersionId());
        assertEquals(2, service.versions(id).size());
        assertEquals(2, jdbc.queryForObject("SELECT COUNT(*) FROM sys_ai_application_cursor", Integer.class));
        assertEquals(version2.appVersionId(), service.version(id, Long.parseLong(version2.appVersionId()), false).appVersionId());
        assertEquals(version1.appVersionId(), requests.owned(Long.parseLong(inFlight.request().requestId()), requestOwner).appVersionId());
    }

    @Test
    void everyCandidateRequiresExactConfigVersionAndCurrentDraftSuccessEvidence() {
        long id = create(policy(List.of(binding("m1"), binding("m2")), true, false));
        String m1 = successfulTest(id, 1, "m1", "cv1");
        assertEquals(ErrorCode.MODEL_UNAVAILABLE, assertThrows(AiRuntimeException.class,
                () -> service.publish(id, 0, List.of(m1), ACTOR)).code());
        assertNull(store.get(id).publishedVersionId());
        String wrongVersion = successfulTest(id, 1, "m2", "cv0");
        assertThrows(AiRuntimeException.class, () -> service.publish(id, 0, List.of(m1, wrongVersion), ACTOR));
        String m2 = successfulTest(id, 1, "m2", "cv1");
        var published = service.publish(id, 0, List.of(m1, m2), ACTOR);
        assertEquals(Set.of(m1, m2), published.snapshot().tests().stream()
                .map(AiApplicationService.TestEvidence::requestId).collect(java.util.stream.Collectors.toSet()));
    }

    @Test
    void staleTestsAndStaleWritesDoNotOverwriteDraftOrPublishedVersion() {
        var policy = policy(List.of(binding("m1")), false, false);
        long id = create(policy);
        String oldTest = successfulTest(id, 1, "m1", "cv1");
        var edited = store.updateDraft(id, 0, draft(policy));
        assertThrows(AiRuntimeException.class, () -> store.updateDraft(id, 0, draft(policy)));
        assertEquals(2, store.get(id).draftRevision());
        assertThrows(AiRuntimeException.class, () -> service.publish(id, edited.revision(), List.of(oldTest), ACTOR));
        assertTrue(service.versions(id).isEmpty());
    }

    @Test
    void dependencyFailureRetainsPreviousOnlineSnapshotAndReferenceSet() {
        long id = create(policy(List.of(binding("m1")), false, false));
        String test = successfulTest(id, 1, "m1", "cv1");
        var original = service.publish(id, 0, List.of(test), ACTOR);
        var updated = store.updateDraft(id, store.get(id).revision(), draft(policy(List.of(binding("m2")), false, false)));
        String newTest = successfulTest(id, 2, "m2", "cv1");
        provider.validation = new Validation(List.of(new Violation("models[0]", ErrorCode.MODEL_UNAVAILABLE)));
        assertThrows(AiRuntimeException.class, () -> service.publish(id, updated.revision(), List.of(newTest), ACTOR));
        assertEquals(original.appVersionId(), store.get(id).publishedVersionId());
        assertTrue(store.get(id).enabled());
        assertEquals(1, service.versions(id).size());
        assertEquals(1, references.modelReferences(new ModelVersionRef("m1", "cv1")).size());
        assertTrue(references.modelReferences(new ModelVersionRef("m2", "cv1")).isEmpty());
    }

    @Test
    void missingRuntimeFailsClosedAndDuplicateLiveEntryIsRejected() {
        long id = create(policy(List.of(binding("m1")), false, false));
        String test = successfulTest(id, 1, "m1", "cv1");
        var empty = new StaticListableBeanFactory();
        var unavailable = new AiApplicationService(jdbc, new ObjectMapper(), store, references,
                empty.getBeanProvider(AiProviderRuntime.class), empty.getBeanProvider(AiTemplateRuntime.class));
        assertEquals(ErrorCode.DEPENDENCY_UNAVAILABLE, assertThrows(AiRuntimeException.class,
                () -> unavailable.publish(id, 0, List.of(test), ACTOR)).code());
        service.publish(id, 0, List.of(test), ACTOR);
        long other = create(policy(List.of(binding("m1")), false, false));
        String otherTest = successfulTest(other, 1, "m1", "cv1");
        assertEquals(ErrorCode.VERSION_CONFLICT, assertThrows(AiRuntimeException.class,
                () -> service.publish(other, 0, List.of(otherTest), ACTOR)).code());
        assertNull(store.get(other).publishedVersionId());
    }

    @Test
    void explicitSubjectScopeFallbackAndToolsAreEnforced() {
        var policy = policy(List.of(binding("m1"), binding("m2")), true, false);
        assertTrue(policy.permits(ACTOR));
        assertFalse(policy.permits(new TrustedActor("999", "platform", Set.of("ai:application:publish"), null)));
        assertFalse(policy.permits(new TrustedActor("101", "platform", ACTOR.authorities(), "job:1")));
        assertEquals(List.of(0, 1), policy.attemptModelOrder(0));
        assertThrows(IllegalArgumentException.class, () -> policy.attemptModelOrder(1));
        assertThrows(IllegalArgumentException.class, () -> policy(List.of(binding("m1")), false, true).requireComplete());
        var incomplete = policy(List.of(), false, false);
        assertThrows(IllegalArgumentException.class, incomplete::requireComplete);
        assertNotNull(store.create(draft(incomplete), 101));
    }

    private long create(AiApplicationPolicy policy) {
        return Long.parseLong(store.create(draft(policy), 101));
    }

    private AiApplicationStore.Draft draft(AiApplicationPolicy policy) {
        return new AiApplicationStore.Draft("summary", "record summary", "lowcode.record.summary/1", policy);
    }

    private AiProviderRuntime.ModelBinding binding(String model) {
        return new AiProviderRuntime.ModelBinding(new ModelVersionRef(model, "cv1"),
                AiProviderRuntime.KeyMode.AUTO, Set.of(), null, List.of());
    }

    private AiApplicationPolicy policy(List<AiProviderRuntime.ModelBinding> bindings, boolean fallback, boolean tools) {
        return new AiApplicationPolicy(AiApplicationPolicy.ModelMode.FIXED, bindings, fallback,
                new TemplateVersionRef("template1", "tv1"), Set.of("user_request"),
                new Requirements(OutputFormat.TEXT, true, tools), AiApplicationPolicy.Scope.FRONTEND,
                Set.of(), Set.of("lowcode:record:query"), "1", 30, 3, 2, 256);
    }

    private String successfulTest(long appId, int revision, String model, String config) {
        Instant now = Instant.now();
        var admission = new AiRequestStore.Admission(appId, null, revision, AiRequestStore.Kind.TEST,
                new AiRequestStore.Owner("101", "platform"), "lowcode.record", "1:7",
                UUID.randomUUID().toString(), "a".repeat(64), now.plusSeconds(30));
        var accepted = transaction.execute(status -> requests.accept(admission, now));
        long id = Long.parseLong(accepted.request().requestId());
        assertTrue(requests.start(id, now));
        jdbc.update("INSERT INTO sys_ai_request_attempt(request_id,attempt_number,config_version,model_ref,key_ref,"
                        + "masked_key_label,switch_reason,status,create_time,finish_time) VALUES(?,1,?,?,"
                        + "'key1','masked-label','FIRST','SUCCEEDED',?,?)", id, config, model,
                Timestamp.from(now), Timestamp.from(now));
        assertTrue(requests.finish(id, AiRequestStatus.SUCCEEDED, null, null, null, now.plusMillis(1)));
        return Long.toString(id);
    }

    // Deliberately test-local doubles. None is installed as a production Spring bean or calls a model.
    private static final class TestProvider implements AiProviderRuntime {
        Validation validation = new Validation(List.of());
        @Override public List<AvailableModel> availableModels(Requirements requirements) { throw new AssertionError(); }
        @Override public Validation validateBindings(List<ModelBinding> bindings, Requirements requirements) { return validation; }
        @Override public List<Target> resolveTargets(ModelBinding binding, Set<KeyRef> tried, InvocationControl control) { throw new AssertionError(); }
        @Override public ExecutionOutcome execute(ExecutionRequest request, StreamObserver observer, InvocationControl control) { throw new AssertionError(); }
    }

    private static final class TestTemplate implements AiTemplateRuntime {
        final List<Invocation> invocations = new java.util.ArrayList<>();
        @Override public PublishedTemplate publishedSnapshot(TemplateVersionRef version, Invocation invocation, InvocationControl control) {
            invocations.add(invocation);
            return new PublishedTemplate(version, List.of(), List.of(new ResourceVersionRef("resource1", "rv1")));
        }
        @Override public Validation validateBinding(TemplateVersionRef version, Set<String> inputs, Invocation invocation, InvocationControl control) {
            return new Validation(List.of());
        }
        @Override public RenderedTemplate renderPublished(RenderRequest request, InvocationControl control) { throw new AssertionError(); }
    }
}
