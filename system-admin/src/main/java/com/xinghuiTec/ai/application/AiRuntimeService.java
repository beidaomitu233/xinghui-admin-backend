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
    public record Submission(BusinessRef businessRef, Map<String, Object> inputs, String idempotencyKey,AiConversationRuntime.Target conversation) {
        public Submission(BusinessRef businessRef,Map<String,Object> inputs,String idempotencyKey){this(businessRef,inputs,idempotencyKey,null);}
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
                          List<ResourceVersionRef> resources, boolean test, int modelIndex,AiSceneRequestAccess.Execution scene) {
        Loaded(AiApplicationStore.View app,AiApplicationPolicy policy,String version,List<ResourceVersionRef> resources,boolean test,int modelIndex) {
            this(app,policy,version,resources,test,modelIndex,null);
        }
    }
    private AiSceneRequestAccess sceneAccess;
    private com.xinghuiTec.ai.modelhub.ModelHubService modelHub;
    @org.springframework.beans.factory.annotation.Autowired(required=false)
    public void modelHub(com.xinghuiTec.ai.modelhub.ModelHubService hub){this.modelHub=hub;}
    private AiConversationRuntime conversations;
    @org.springframework.beans.factory.annotation.Autowired(required=false)
    public void conversations(AiConversationRuntime conversations){this.conversations=conversations;}
    @org.springframework.beans.factory.annotation.Autowired(required=false)
    public void sceneAccess(AiSceneRequestAccess access){this.sceneAccess=access;}

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
            var nativeScene=sceneAccess==null?null:sceneAccess.execution(version.appVersionId());
            return new Loaded(app, version.snapshot().policy(), version.appVersionId(), version.snapshot().resources(), false, -1,nativeScene);
        });
        return admit(loaded, input, actor);
    }

    /** Scene service selects a real immutable execution version; the existing request pipeline owns all I/O. */
    public AiRequestStore.Request submitScene(long appId,long versionId,boolean test,int modelIndex,Submission input,TrustedActor actor) {
        Loaded loaded=transaction.execute(status->{
            var app=applications.current(appId);var version=versions.version(appId,versionId,true);
            var nativeScene=sceneAccess==null?null:sceneAccess.execution(version.appVersionId());
            if(nativeScene==null)throw new AiRuntimeException(ErrorCode.DEPENDENCY_UNAVAILABLE);
            var policy=version.snapshot().policy();policy.requireModelComplete();
            if(test && policy.businessModel()==null){
                if(modelIndex<0 || modelIndex>=policy.models().size())throw new AiRuntimeException(ErrorCode.INVALID_INPUT);
                policy=new AiApplicationPolicy(AiApplicationPolicy.ModelMode.FIXED,List.of(policy.models().get(modelIndex)),false,null,
                        policy.inputNames(),policy.requirements(),policy.scope(),policy.allowedSubjects(),policy.allowedAuthorities(),policy.formId(),
                        policy.timeoutSeconds(),policy.maxAttempts(),policy.maxConcurrent(),policy.maxTokens());
            }
            return new Loaded(app,policy,version.appVersionId(),List.of(),test,modelIndex,nativeScene);
        });
        return admit(loaded,input,actor);
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

    /** Replay precedes the facade's current revision check, with input-free current access revalidation. */
    public AiRequestStore.Request replayScene(long appId,String sceneKey,boolean test,int modelIndex,Submission input,TrustedActor actor) {
        if(actor.servicePrincipalRef()!=null || !actor.authorities().contains("ai:scene:invoke"))throw new AiRuntimeException(ErrorCode.FORBIDDEN);
        var owner=owner(actor);AiRequestStore.Request replay;
        try{replay=transaction.execute(tx->requests.sceneReplay(appId,sceneKey,test?AiRequestStore.Kind.TEST:AiRequestStore.Kind.BUSINESS,owner,input.idempotencyKey(),
                fingerprint(Long.toString(appId),test,null,modelIndex,input,owner)));}
        catch(IllegalArgumentException conflict){if("IDEMPOTENCY_CONFLICT".equals(conflict.getMessage()))throw new AiRuntimeException(ErrorCode.VERSION_CONFLICT);throw conflict;}
        if(replay==null)return null;
        var authorized=status(AiApplicationService.positiveId(replay.requestId()),actor);
        authorizeEvents(authorized,actor);
        return authorized;
    }

    private AiRequestStore.Request admit(Loaded loaded, Submission input, TrustedActor actor) {
        if (closing) throw new AiRuntimeException(ErrorCode.DEPENDENCY_UNAVAILABLE);
        if(loaded.scene()==null)loaded.policy().requireComplete();else{
            loaded.policy().requireModelComplete();
            if(actor.servicePrincipalRef()!=null || !actor.authorities().contains("ai:scene:invoke"))throw new AiRuntimeException(ErrorCode.FORBIDDEN);
        }
        if (!(loaded.test() ? loaded.policy().permitsSubject(actor) : loaded.policy().permits(actor))) {
            throw new AiRuntimeException(ErrorCode.FORBIDDEN);
        }
        validateInput(loaded.policy(), input);
        Instant now = Instant.now();
        var owner = owner(actor);
        var admission = new AiRequestStore.Admission(AiApplicationService.positiveId(loaded.app().appId()),
                loaded.test() && loaded.scene()==null ? null : AiApplicationService.positiveId(loaded.version()),
                loaded.test() && loaded.scene()==null ? loaded.app().draftRevision() : null, loaded.test() ? AiRequestStore.Kind.TEST : AiRequestStore.Kind.BUSINESS,
                owner, input.businessRef().type(), input.businessRef().id(), input.idempotencyKey(), fingerprint(loaded, input, owner),
                now.plusSeconds(loaded.policy().timeoutSeconds()),loaded.scene()==null?null:loaded.scene().sceneKey(),loaded.scene()==null?null:loaded.scene().sceneVersionId());
        AiRequestStore.Accepted accepted;
        try { accepted = transaction.execute(status -> {var result=requests.accept(admission,now);
            if(result.created() && conversations!=null)conversations.admitted(result.request(),owner,input.inputs(),input.conversation());
            return result;}); }
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
            AiApplicationPolicy effectivePolicy=loaded.policy();
            if(effectivePolicy.businessModel()!=null){
                if(modelHub==null)throw new AiRuntimeException(ErrorCode.DEPENDENCY_UNAVAILABLE);
                var frozen=modelHub.freeze(effectivePolicy);modelHub.pin(id,frozen,actor,loaded.test());effectivePolicy=frozen.policy();
            }
            journal.stage(id, AiRequestStore.Stage.ACCEPTED, AiRequestJournal.Outcome.SUCCEEDED, null, null, null, null);
            if (!loaded.policy().inputNames().containsAll(input.inputs().keySet())) throw new AiRuntimeException(ErrorCode.INVALID_INPUT);
            lifecycle.accepted(id, owner); // Verifies a genuinely volatile, available buffer before any model attempt.
            if (!loaded.test() && !loaded.app().enabled()) throw new AiRuntimeException(ErrorCode.MODEL_UNAVAILABLE);
            requests.stage(id, AiRequestStore.Stage.DATA, Instant.now());
            journal.stage(id, AiRequestStore.Stage.DATA, AiRequestJournal.Outcome.STARTED, null, null, null, null);
            if(loaded.scene()!=null){
                var rendered=sceneAccess.render(loaded.scene(),invocation,new AiInputs(input.inputs()),control);control.check();
                if(rendered==null || rendered.messages().isEmpty())throw new AiRuntimeException(ErrorCode.INVALID_INPUT);
                var messages=withConversation(accepted.request(),rendered.messages(),actor);
                int bytes=0;for(AiMessage message:messages)bytes=Math.addExact(bytes,message.content().getBytes(StandardCharsets.UTF_8).length);
                if(bytes>65536)throw new AiRuntimeException(ErrorCode.INVALID_INPUT);
                journal.stage(id,AiRequestStore.Stage.RENDER,AiRequestJournal.Outcome.SUCCEEDED,null,null,null,null);
                var work=new AiRequestExecutor.Work(invocation,effectivePolicy,messages,List.of(),input.inputs().keySet(),loaded.test(),
                        ()->authorizeEvents(status(id,actor),actor));
                pool.execute(()->{try{worker.execute(work);}finally{dispatched.remove(id);}});
                return accepted.request();
            }
            var template = templates.getIfUnique();
            if (template == null) throw new AiRuntimeException(ErrorCode.DEPENDENCY_UNAVAILABLE);
            var rendered = template.renderPublished(new AiTemplateRuntime.RenderRequest(loaded.policy().template(),
                    new AiInputs(input.inputs()), invocation), control);
            control.check();
            if (!rendered.version().equals(loaded.policy().template()) || rendered.messages().isEmpty()
                    || (!loaded.test() && !new HashSet<>(rendered.resources()).equals(new HashSet<>(loaded.resources())))) {
                throw new AiRuntimeException(ErrorCode.VERSION_CONFLICT);
            }
            var messages=withConversation(accepted.request(),rendered.messages(),actor);
            int bytes = 0;
            for (AiMessage message : messages) bytes = Math.addExact(bytes, message.content().getBytes(StandardCharsets.UTF_8).length);
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
            var work = new AiRequestExecutor.Work(invocation, effectivePolicy, messages, rendered.resources(), input.inputs().keySet(), loaded.test());
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
    private List<AiMessage> withConversation(AiRequestStore.Request request,List<AiMessage> current,TrustedActor actor){
        if(conversations==null)return current;int bytes=current.stream().mapToInt(m->m.content().getBytes(StandardCharsets.UTF_8).length).sum();
        var history=conversations.context(Long.parseLong(request.requestId()),Math.max(0,65536-bytes));
        for(long prior:history.requestIds())authorizeSingle(status(prior,actor),actor);
        var all=new ArrayList<AiMessage>();int leading=0;while(leading<current.size() && current.get(leading).role()==MessageRole.SYSTEM)all.add(current.get(leading++));
        all.addAll(history.messages());all.addAll(current.subList(leading,current.size()));return List.copyOf(all);
    }

    /** Reuse the existing bounded worker pool; SQL assistance owns its safe polling/result boundary. */
    public void dispatchPrivate(AiRequestExecutor.Work work,Runnable completed) {
        var owner=owner(work.invocation().actor());long id=AiApplicationService.positiveId(work.invocation().requestId());
        var request=requests.owned(id,owner);
        if(work.privateOutput()==null || !AiRequestStore.privateRequest(request.businessType())) throw new AiRuntimeException(ErrorCode.FORBIDDEN);
        if(closing) throw new AiRuntimeException(ErrorCode.DEPENDENCY_UNAVAILABLE);
        dispatched.put(id,owner);
        try {
            pool.execute(()->{try{worker.execute(work);}finally{
                try{completed.run();}catch(RuntimeException unavailableResult){/* Polling fails closed; never publish raw text or re-dispatch. */}
                dispatched.remove(id);
            }});
        }catch(java.util.concurrent.RejectedExecutionException busy){
            dispatched.remove(id);lifecycle.finish(id,owner,AiRequestStatus.FAILED,ErrorCode.RATE_LIMITED,null);throw new AiRuntimeException(ErrorCode.RATE_LIMITED);
        }
    }

    public AiRequestStore.Request status(long id, TrustedActor actor) {
        AiRequestStore.Request request;
        try { request = requests.owned(id, owner(actor)); }
        catch (IllegalArgumentException notOwned) { throw new AiRuntimeException(ErrorCode.NOT_FOUND); }
        if(AiRequestStore.privateRequest(request.businessType())) throw new AiRuntimeException(ErrorCode.NOT_FOUND);
        AiApplicationPolicy policy = policy(request);
        if (!(request.kind() == AiRequestStore.Kind.TEST ? policy.permitsSubject(actor) : policy.permits(actor))) {
            throw new AiRuntimeException(ErrorCode.FORBIDDEN);
        }
        return request;
    }

    public AiApplicationPolicy policy(AiRequestStore.Request request) {
        long appId = AiApplicationService.positiveId(request.appId());
        if (request.kind() == AiRequestStore.Kind.TEST && request.appVersionId()==null) return applications.get(appId).draft();
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
        authorizeSingle(request,actor);
        if(conversations!=null)for(long prior:conversations.contextRequests(Long.parseLong(request.requestId())))authorizeSingle(status(prior,actor),actor);
    }
    private void authorizeSingle(AiRequestStore.Request request,TrustedActor actor) {
        if(AiRequestStore.privateRequest(request.businessType())) throw new AiRuntimeException(ErrorCode.NOT_FOUND);
        var policy = policy(request);
        if (request.kind() == AiRequestStore.Kind.TEST && request.appVersionId()==null && applications.get(AiApplicationService.positiveId(request.appId())).draftRevision()
                != request.draftRevision()) throw new AiRuntimeException(ErrorCode.VERSION_CONFLICT);
        var invocation = new Invocation(actor, request.appId(), request.appVersionId() == null ? "draft:" + request.draftRevision()
                : request.appVersionId(), new BusinessRef(request.businessType(), request.businessId()), request.requestId());
        InvocationControl readControl = new InvocationControl() {
            private final Instant deadline = Instant.now().plusSeconds(5);
            @Override public Instant deadline() { return deadline; }
            @Override public boolean cancelled() { return false; }
        };
        var nativeScene=request.appVersionId()==null || sceneAccess==null?null:sceneAccess.execution(request.appVersionId());
        if(nativeScene!=null){sceneAccess.authorize(nativeScene,invocation,readControl);return;}
        var template = templates.getIfUnique();
        if (template == null) throw new AiRuntimeException(ErrorCode.DEPENDENCY_UNAVAILABLE);
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
        return fingerprint(loaded.app().appId(),loaded.test(),loaded.scene()==null?loaded.app().draftRevision():null,loaded.modelIndex(),input,owner);
    }
    private String fingerprint(String appId,boolean test,Integer draftRevision,int modelIndex,Submission input,AiRequestStore.Owner owner) {
        try {
            var values = new TreeMap<String, Object>();
            values.put("businessType", input.businessRef().type()); values.put("businessId", input.businessRef().id());
            values.put("inputs", input.inputs());
            values.put("appId", appId); values.put("ownerScope", owner.scope());
            values.put("idempotencyKey", input.idempotencyKey());
            if(input.conversation()!=null)values.put("conversationId",input.conversation().conversationId());
            if (test) { if(draftRevision!=null)values.put("draftRevision", draftRevision); values.put("modelIndex", modelIndex); }
            return cipher.fingerprint("ai:request-input:v1", mapper.writeValueAsBytes(values));
        } catch (ServiceException unavailableMaster) { throw new AiRuntimeException(ErrorCode.CREDENTIAL_UNAVAILABLE); }
        catch (JsonProcessingException unavailable) { throw new AiRuntimeException(ErrorCode.INTERNAL_FAILURE); }
    }

    @Scheduled(fixedDelay = 10_000, initialDelay = 10_000)
    public void expireInterruptedRequests() {
        requests.expire(Instant.now()); // Deadline recovery only, never re-dispatch an unknown provider request.
        if(conversations!=null)conversations.reconcile();
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
