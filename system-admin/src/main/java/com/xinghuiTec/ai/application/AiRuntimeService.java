package com.xinghuiTec.ai.application;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.SerializationFeature;
import com.xinghuiTec.ai.contract.*;
import com.xinghuiTec.ai.contract.AiTypes.*;
import com.xinghuiTec.config.SecretCipher;
import com.xinghuiTec.exception.ServiceException;
import jakarta.annotation.PreDestroy;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicInteger;

/** Admits a durable ID, authorizes/renders through C, then dispatches one bounded asynchronous worker. */
@Service
public class AiRuntimeService {
    public record Submission(BusinessRef businessRef, Map<String, Object> inputs, String idempotencyKey) {
        public Submission {
            if (businessRef == null || inputs == null || idempotencyKey == null
                    || !idempotencyKey.matches("[a-fA-F0-9]{8}-[a-fA-F0-9]{4}-[a-fA-F0-9]{4}-[a-fA-F0-9]{4}-[a-fA-F0-9]{12}")) {
                throw new AiRuntimeException(ErrorCode.INVALID_INPUT);
            }
            inputs = new AiInputs(inputs).values();
        }
        @Override public String toString() { return "AiSubmission[REDACTED]"; }
    }
    public record Entry(String appId, String appVersionId, String name) {}
    public static final class AdmissionFailure extends RuntimeException {
        private final String requestId;
        private final ErrorCode code;
        AdmissionFailure(String requestId, ErrorCode code) { super(code.name()); this.requestId = requestId; this.code = code; }
        public String requestId() { return requestId; }
        public ErrorCode code() { return code; }
    }
    private record Loaded(AiApplicationStore.View app, AiApplicationPolicy policy, String version,
                          List<ResourceVersionRef> resources, boolean test, int modelIndex) {}

    private final AiApplicationStore applications;
    private final AiApplicationService versions;
    private final AiRequestStore requests;
    private final AiRequestJournal journal;
    private final AiRequestLifecycle lifecycle;
    private final AiRequestExecutor worker;
    private final ObjectProvider<AiTemplateRuntime> templates;
    private final JdbcTemplate jdbc;
    private final ObjectMapper mapper;
    private final SecretCipher cipher;
    private final TransactionTemplate transaction;
    private final ThreadPoolExecutor pool;
    private final ConcurrentMap<Long, AiRequestStore.Owner> dispatched = new ConcurrentHashMap<>();
    private volatile boolean closing;

    public AiRuntimeService(AiApplicationStore applications, AiApplicationService versions, AiRequestStore requests,
                             AiRequestJournal journal, AiRequestLifecycle lifecycle, AiRequestExecutor worker,
                             ObjectProvider<AiTemplateRuntime> templates, JdbcTemplate jdbc,
                             ObjectMapper mapper, PlatformTransactionManager manager, SecretCipher cipher) {
        this.applications = applications; this.versions = versions; this.requests = requests; this.journal = journal;
        this.lifecycle = lifecycle; this.worker = worker; this.templates = templates; this.jdbc = jdbc;
        this.mapper = mapper.copy().enable(SerializationFeature.ORDER_MAP_ENTRIES_BY_KEYS);
        this.cipher = cipher;
        this.transaction = new TransactionTemplate(manager);
        var sequence = new AtomicInteger();
        this.pool = new ThreadPoolExecutor(4, 4, 30, TimeUnit.SECONDS, new ArrayBlockingQueue<>(32), task -> {
            Thread thread = new Thread(task, "ai-request-" + sequence.incrementAndGet()); thread.setDaemon(true); return thread;
        }, new ThreadPoolExecutor.AbortPolicy());
    }

    public AiRequestStore.Request submit(long appId, Submission input, TrustedActor actor) {
        Loaded loaded = transaction.execute(status -> {
            var app = applications.current(appId);
            if (app.publishedVersionId() == null) throw new AiRuntimeException(ErrorCode.MODEL_UNAVAILABLE);
            var version = versions.version(appId, AiApplicationService.positiveId(app.publishedVersionId()), true);
            return new Loaded(app, version.snapshot().policy(), version.appVersionId(), version.snapshot().resources(), false, -1);
        });
        return admit(loaded, input, actor);
    }

    public AiRequestStore.Request test(long appId, int revision, int modelIndex, Submission input, TrustedActor actor) {
        Loaded loaded = transaction.execute(status -> {
            var app = applications.current(appId);
            if (app.revision() != revision) throw new AiRuntimeException(ErrorCode.VERSION_CONFLICT);
            var policy = app.draft(); policy.requireComplete();
            if (modelIndex < 0 || modelIndex >= policy.models().size()) throw new AiRuntimeException(ErrorCode.INVALID_INPUT);
            var selected = new AiApplicationPolicy(AiApplicationPolicy.ModelMode.FIXED, List.of(policy.models().get(modelIndex)), false,
                    policy.template(), policy.inputNames(), policy.requirements(), policy.scope(), policy.allowedSubjects(),
                    policy.allowedAuthorities(), policy.formId(), policy.timeoutSeconds(), policy.maxAttempts(), policy.maxConcurrent(), policy.maxTokens());
            return new Loaded(app, selected, "draft:" + app.draftRevision(), List.of(), true, modelIndex);
        });
        return admit(loaded, input, actor);
    }

    private AiRequestStore.Request admit(Loaded loaded, Submission input, TrustedActor actor) {
        if (closing) throw new AiRuntimeException(ErrorCode.DEPENDENCY_UNAVAILABLE);
        loaded.policy().requireComplete();
        if (!(loaded.test() ? loaded.policy().permitsSubject(actor) : loaded.policy().permits(actor))) {
            throw new AiRuntimeException(ErrorCode.FORBIDDEN);
        }
        validateInput(loaded.policy(), input);
        Instant now = Instant.now();
        var owner = owner(actor);
        var admission = new AiRequestStore.Admission(AiApplicationService.positiveId(loaded.app().appId()),
                loaded.test() ? null : AiApplicationService.positiveId(loaded.version()),
                loaded.test() ? loaded.app().draftRevision() : null, loaded.test() ? AiRequestStore.Kind.TEST : AiRequestStore.Kind.BUSINESS,
                owner, input.businessRef().type(), input.businessRef().id(), input.idempotencyKey(), fingerprint(loaded, input, owner),
                now.plusSeconds(loaded.policy().timeoutSeconds()));
        AiRequestStore.Accepted accepted;
        try { accepted = transaction.execute(status -> requests.accept(admission, now)); }
        catch (IllegalArgumentException conflict) {
            if ("IDEMPOTENCY_CONFLICT".equals(conflict.getMessage())) throw new AiRuntimeException(ErrorCode.VERSION_CONFLICT);
            throw conflict;
        }
        if (!accepted.created()) return status(AiApplicationService.positiveId(accepted.request().requestId()), actor);
        long id = AiApplicationService.positiveId(accepted.request().requestId());
        dispatched.put(id, owner);
        var invocation = new Invocation(actor, loaded.app().appId(), loaded.version(), input.businessRef(), accepted.request().requestId());
        InvocationControl control = control(accepted.request(), owner);
        try {
            journal.stage(id, AiRequestStore.Stage.ACCEPTED, AiRequestJournal.Outcome.SUCCEEDED, null, null, null, null);
            if (!loaded.policy().inputNames().containsAll(input.inputs().keySet())) throw new AiRuntimeException(ErrorCode.INVALID_INPUT);
            lifecycle.accepted(id, owner); // Verifies a genuinely volatile, available buffer before any model attempt.
            if (!loaded.test() && !loaded.app().enabled()) throw new AiRuntimeException(ErrorCode.MODEL_UNAVAILABLE);
            var template = templates.getIfUnique();
            if (template == null) throw new AiRuntimeException(ErrorCode.DEPENDENCY_UNAVAILABLE);
            requests.stage(id, AiRequestStore.Stage.DATA, Instant.now());
            journal.stage(id, AiRequestStore.Stage.DATA, AiRequestJournal.Outcome.STARTED, null, null, null, null);
            var rendered = template.renderPublished(new AiTemplateRuntime.RenderRequest(loaded.policy().template(),
                    new AiInputs(input.inputs()), invocation), control);
            control.check();
            if (!rendered.version().equals(loaded.policy().template()) || rendered.messages().isEmpty()
                    || (!loaded.test() && !new HashSet<>(rendered.resources()).equals(new HashSet<>(loaded.resources())))) {
                throw new AiRuntimeException(ErrorCode.VERSION_CONFLICT);
            }
            int bytes = 0;
            for (AiMessage message : rendered.messages()) bytes = Math.addExact(bytes, message.content().getBytes(StandardCharsets.UTF_8).length);
            if (bytes > 65_536) throw new AiRuntimeException(ErrorCode.INVALID_INPUT);
            journal.stage(id, AiRequestStore.Stage.RENDER, AiRequestJournal.Outcome.SUCCEEDED, null,
                    AiRequestJournal.ReferenceKind.TEMPLATE, rendered.version().templateId(), rendered.version().templateVersionId());
            for (ResourceVersionRef resource : rendered.resources()) journal.stage(id, AiRequestStore.Stage.DATA,
                    AiRequestJournal.Outcome.SUCCEEDED, null, AiRequestJournal.ReferenceKind.RESOURCE, resource.resourceId(), resource.resourceVersionId());
            for (var notice : rendered.notices()) journal.stage(id, AiRequestStore.Stage.RENDER,
                    switch (notice.kind()) {
                        case DEFAULT_USED -> AiRequestJournal.Outcome.DEFAULT_USED;
                        case OPTIONAL_RESOURCE_MISSING -> AiRequestJournal.Outcome.OPTIONAL_MISSING;
                        case TRUNCATED -> AiRequestJournal.Outcome.TRUNCATED;
                    }, null, AiRequestJournal.ReferenceKind.VARIABLE, notice.reference(), null);
            var work = new AiRequestExecutor.Work(invocation, loaded.policy(), rendered.messages(), rendered.resources(), input.inputs().keySet(), loaded.test());
            pool.execute(() -> {
                try { worker.execute(work); }
                finally { dispatched.remove(id); }
            });
            // Return the admission snapshot: a very fast worker may finish before HTTP202 is written.
            return accepted.request();
        } catch (AiRuntimeException safe) {
            failAdmission(id, owner, safe.code());
            throw new AdmissionFailure(Long.toString(id), safe.code());
        } catch (RuntimeException unexpected) {
            failAdmission(id, owner, ErrorCode.DEPENDENCY_UNAVAILABLE);
            throw new AdmissionFailure(Long.toString(id), ErrorCode.DEPENDENCY_UNAVAILABLE);
        }
    }

    private void failAdmission(long id, AiRequestStore.Owner owner, ErrorCode code) {
        dispatched.remove(id);
        lifecycle.finish(id, owner, code == ErrorCode.CANCELLED ? AiRequestStatus.CANCELLED
                : code == ErrorCode.DEADLINE_EXCEEDED ? AiRequestStatus.TIMED_OUT : AiRequestStatus.FAILED, code, null);
        journal.stage(id, AiRequestStore.Stage.FINISHED, AiRequestJournal.Outcome.FAILED, code, null, null, null);
    }

    public AiRequestStore.Request status(long id, TrustedActor actor) {
        AiRequestStore.Request request;
        try { request = requests.owned(id, owner(actor)); }
        catch (IllegalArgumentException notOwned) { throw new AiRuntimeException(ErrorCode.NOT_FOUND); }
        AiApplicationPolicy policy = policy(request);
        if (!(request.kind() == AiRequestStore.Kind.TEST ? policy.permitsSubject(actor) : policy.permits(actor))) {
            throw new AiRuntimeException(ErrorCode.FORBIDDEN);
        }
        return request;
    }

    public AiApplicationPolicy policy(AiRequestStore.Request request) {
        long appId = AiApplicationService.positiveId(request.appId());
        if (request.kind() == AiRequestStore.Kind.TEST) return applications.get(appId).draft();
        return versions.version(appId, AiApplicationService.positiveId(request.appVersionId()), false).snapshot().policy();
    }

    public AiRequestStore.Request cancel(long id, TrustedActor actor) {
        status(id, actor);
        lifecycle.finish(id, owner(actor), AiRequestStatus.CANCELLED, ErrorCode.CANCELLED, null);
        return status(id, actor);
    }

    public Entry recordSummary(String formId, TrustedActor actor) {
        AiApplicationService.positiveId(formId);
        if (!actor.authorities().contains("lowcode:record:query")) throw new AiRuntimeException(ErrorCode.FORBIDDEN);
        List<Long> ids = jdbc.query("SELECT app_id FROM sys_ai_application WHERE entry_key=? AND enabled=TRUE"
                        + " AND published_version_id IS NOT NULL", (rs, index) -> rs.getLong(1), "lowcode.record.summary/" + formId);
        if (ids.size() > 1) throw new AiRuntimeException(ErrorCode.VERSION_CONFLICT);
        if (ids.isEmpty()) return null;
        var app = applications.get(ids.get(0));
        var policy = versions.version(ids.get(0), AiApplicationService.positiveId(app.publishedVersionId()), false).snapshot().policy();
        return policy.permits(actor) ? new Entry(app.appId(), app.publishedVersionId(), app.name()) : null;
    }

    public void authorizeEvents(AiRequestStore.Request request, TrustedActor actor) {
        var policy = policy(request);
        if (request.kind() == AiRequestStore.Kind.TEST && applications.get(AiApplicationService.positiveId(request.appId())).draftRevision()
                != request.draftRevision()) throw new AiRuntimeException(ErrorCode.VERSION_CONFLICT);
        var template = templates.getIfUnique();
        if (template == null) throw new AiRuntimeException(ErrorCode.DEPENDENCY_UNAVAILABLE);
        var invocation = new Invocation(actor, request.appId(), request.appVersionId() == null ? "draft:" + request.draftRevision()
                : request.appVersionId(), new BusinessRef(request.businessType(), request.businessId()), request.requestId());
        InvocationControl readControl = new InvocationControl() {
            private final Instant deadline = Instant.now().plusSeconds(5);
            @Override public Instant deadline() { return deadline; }
            @Override public boolean cancelled() { return false; }
        };
        var validation = template.validateBinding(policy.template(), policy.inputNames(), invocation, readControl);
        if (!validation.valid()) throw new AiRuntimeException(validation.violations().get(0).code());
    }

    private InvocationControl control(AiRequestStore.Request request, AiRequestStore.Owner owner) {
        return new InvocationControl() {
            @Override public Instant deadline() { return request.deadline(); }
            @Override public boolean cancelled() { return requests.owned(AiApplicationService.positiveId(request.requestId()), owner).status() == AiRequestStatus.CANCELLED; }
        };
    }

    static AiRequestStore.Owner owner(TrustedActor actor) {
        return new AiRequestStore.Owner(actor.servicePrincipalRef() == null ? actor.subjectId() : "service:" + actor.servicePrincipalRef(), actor.tenantId());
    }

    private void validateInput(AiApplicationPolicy policy, Submission input) {
        AiApplicationInputRules.validate(policy, input.businessRef(), input.inputs());
    }

    private String fingerprint(Loaded loaded, Submission input, AiRequestStore.Owner owner) {
        try {
            var values = new TreeMap<String, Object>();
            values.put("businessType", input.businessRef().type()); values.put("businessId", input.businessRef().id());
            values.put("inputs", input.inputs());
            values.put("appId", loaded.app().appId()); values.put("ownerScope", owner.scope());
            values.put("idempotencyKey", input.idempotencyKey());
            if (loaded.test()) { values.put("draftRevision", loaded.app().draftRevision()); values.put("modelIndex", loaded.modelIndex()); }
            return cipher.fingerprint("ai:request-input:v1", mapper.writeValueAsBytes(values));
        } catch (ServiceException unavailableMaster) { throw new AiRuntimeException(ErrorCode.CREDENTIAL_UNAVAILABLE); }
        catch (JsonProcessingException unavailable) { throw new AiRuntimeException(ErrorCode.INTERNAL_FAILURE); }
    }

    @Scheduled(fixedDelay = 10_000, initialDelay = 10_000)
    public void expireInterruptedRequests() {
        requests.expire(Instant.now()); // Deadline recovery only, never re-dispatch an unknown provider request.
    }

    @PreDestroy
    public void stop() {
        closing = true; pool.shutdownNow();
        dispatched.forEach((id, owner) -> {
            try { lifecycle.finish(id, owner, AiRequestStatus.CANCELLED, ErrorCode.CANCELLED, null); }
            catch (RuntimeException unavailableDuringShutdown) { /* Deadline recovery remains the durable fallback. */ }
        });
    }
}
