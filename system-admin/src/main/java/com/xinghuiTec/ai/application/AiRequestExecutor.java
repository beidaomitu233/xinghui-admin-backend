package com.xinghuiTec.ai.application;

import com.xinghuiTec.ai.contract.*;
import com.xinghuiTec.ai.contract.AiProviderRuntime.*;
import com.xinghuiTec.ai.contract.AiTypes.*;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

import java.time.Instant;
import java.util.*;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicReference;

/** Exactly one worker per newly admitted request. No provider retries are hidden outside this loop. */
@Service
public class AiRequestExecutor {
    public record Work(Invocation invocation, AiApplicationPolicy policy, List<AiMessage> messages,
                       List<ResourceVersionRef> resources, Set<String> suppliedInputs, boolean draftTest,Runnable sceneAccessCheck,java.util.function.Consumer<String> privateOutput) {
        public Work(Invocation invocation,AiApplicationPolicy policy,List<AiMessage> messages,
                    List<ResourceVersionRef> resources,Set<String> suppliedInputs,boolean draftTest,Runnable sceneAccessCheck) {
            this(invocation,policy,messages,resources,suppliedInputs,draftTest,sceneAccessCheck,null);
        }
        public Work(Invocation invocation,AiApplicationPolicy policy,List<AiMessage> messages,
                    List<ResourceVersionRef> resources,Set<String> suppliedInputs,boolean draftTest) {
            this(invocation,policy,messages,resources,suppliedInputs,draftTest,null);
        }
        public Work {
            messages = List.copyOf(messages); resources = List.copyOf(resources); suppliedInputs = Set.copyOf(suppliedInputs);
        }
        @Override public String toString() { return "AiRequestWork[REDACTED]"; }
    }

    private final AiRequestStore requests;
    private final AiApplicationStore applications;
    private final AiRequestJournal journal;
    private final AiRequestLifecycle lifecycle;
    private final AiSharedModelCursor cursor;
    private final AiRuntimeEvents events;
    private final ObjectProvider<AiProviderRuntime> providers;
    private final ObjectProvider<AiTemplateRuntime> templates;
    private final JdbcTemplate jdbc;
    private final TransactionTemplate transaction;

    public AiRequestExecutor(AiRequestStore requests, AiApplicationStore applications, AiRequestJournal journal,
                              AiRequestLifecycle lifecycle, AiSharedModelCursor cursor, AiRuntimeEvents events,
                              ObjectProvider<AiProviderRuntime> providers, ObjectProvider<AiTemplateRuntime> templates,
                              JdbcTemplate jdbc, PlatformTransactionManager manager) {
        this.requests = requests; this.applications = applications; this.journal = journal;
        this.lifecycle = lifecycle; this.cursor = cursor; this.events = events;
        this.providers = providers; this.templates = templates; this.jdbc = jdbc;
        this.transaction = new TransactionTemplate(manager);
    }

    public void execute(Work work) {
        long id = AiApplicationService.positiveId(work.invocation().requestId());
        var owner = owner(work.invocation());
        var control = new RequestControl(id, owner, requests.owned(id, owner).deadline());
        try {
            while (!start(work, id, owner)) {
                control.check();
                Thread.sleep(50);
            }
            run(work, id, owner, control);
        } catch (AlreadyClaimed alreadyClaimed) {
            // A duplicate dispatcher never changes the state owned by another worker.
        } catch (InterruptedException interrupted) {
            Thread.currentThread().interrupt();
            finish(id, owner, AiRequestStatus.CANCELLED, ErrorCode.CANCELLED);
        } catch (AiRuntimeException safe) {
            var current = requests.owned(id, owner);
            var status = safe.code() == ErrorCode.CANCELLED ? AiRequestStatus.CANCELLED
                    : current.outputStarted() ? AiRequestStatus.INCOMPLETE
                    : safe.code() == ErrorCode.DEADLINE_EXCEEDED ? AiRequestStatus.TIMED_OUT : AiRequestStatus.FAILED;
            finish(id, owner, status, safe.code());
        } catch (RuntimeException unexpected) {
            // No exception message/cause can escape into request logs or event payloads.
            var current = requests.owned(id, owner);
            finish(id, owner, current.outputStarted() ? AiRequestStatus.INCOMPLETE : AiRequestStatus.FAILED,
                    ErrorCode.INTERNAL_FAILURE);
        }
    }

    private boolean start(Work work, long id, AiRequestStore.Owner owner) {
        return Boolean.TRUE.equals(transaction.execute(status -> {
            // Consistent lock order: application first, then request (same as publication/evidence).
            var app = applications.current(AiApplicationService.positiveId(work.invocation().appId()));
            if (!work.draftTest() && !app.enabled()) throw new AiRuntimeException(ErrorCode.MODEL_UNAVAILABLE);
            var current = requests.currentOwned(id, owner);
            if (current.status() != AiRequestStatus.QUEUED) throw new AlreadyClaimed();
            List<Long> active = jdbc.query("SELECT request_id FROM sys_ai_request WHERE app_id=? AND status='RUNNING'"
                            + " AND deadline>? FOR UPDATE", (rs, index) -> rs.getLong(1),
                    AiApplicationService.positiveId(work.invocation().appId()), java.sql.Timestamp.from(Instant.now()));
            if (active.size() >= work.policy().maxConcurrent()) return false;
            return requests.start(id, Instant.now());
        }));
    }

    private void run(Work work, long id, AiRequestStore.Owner owner, RequestControl control) {
        var provider = providers.getIfUnique();
        if (provider == null || (work.sceneAccessCheck()==null && templates.getIfUnique() == null)) throw new AiRuntimeException(ErrorCode.DEPENDENCY_UNAVAILABLE);
        AiApplicationPolicy policy = work.policy();
        List<Boolean> eligible = policy.models().stream().map(binding -> {
            try { return !targets(provider, binding, Set.of(), policy.requirements(), control).isEmpty(); }
            catch (AiRuntimeException unavailable) {
                if (unavailable.code() == ErrorCode.MODEL_UNAVAILABLE || unavailable.code() == ErrorCode.NO_USABLE_KEY
                        || unavailable.code() == ErrorCode.CAPABILITY_UNSUPPORTED) return false;
                throw unavailable;
            }
        }).toList();
        int first = 0;
        if (policy.modelMode() == AiApplicationPolicy.ModelMode.ROUND_ROBIN && !work.draftTest()) {
            first = cursor.next(AiApplicationService.positiveId(work.invocation().appVersionId()), eligible)
                    .orElseThrow(() -> new AiRuntimeException(ErrorCode.MODEL_UNAVAILABLE));
        }
        int maximum = Math.min(policy.maxAttempts(), policy.maxTokens());
        int remainingTokens = policy.maxTokens();
        int attempts = 0;
        int previousModel = -1;
        ErrorCode lastCode = ErrorCode.NO_USABLE_KEY;
        var modelOrder=policy.attemptModelOrder(first);
        for (int position=0;position<modelOrder.size();position++) {
            int index=modelOrder.get(position);
            // A Key may serve multiple models: exclusions are per model version, never global.
            Set<KeyRef> attemptedKeys = new HashSet<>();
            while (attempts < maximum && remainingTokens > 0) {
                control.check();
                if (!work.draftTest() && !applications.get(AiApplicationService.positiveId(work.invocation().appId())).enabled()) {
                    throw new AiRuntimeException(ErrorCode.MODEL_UNAVAILABLE);
                }
                if (events.after(Long.toString(id), 0).isEmpty()) throw new AiRuntimeException(ErrorCode.DEPENDENCY_UNAVAILABLE);
                validateTemplate(work, control);
                List<Target> targets;
                try {
                    targets = targets(provider, policy.models().get(index), attemptedKeys, policy.requirements(), control);
                } catch (AiRuntimeException unavailable) {
                    if (unavailable.code() != ErrorCode.MODEL_UNAVAILABLE && unavailable.code() != ErrorCode.NO_USABLE_KEY
                            && unavailable.code() != ErrorCode.CAPABILITY_UNSUPPORTED) throw unavailable;
                    lastCode = unavailable.code();
                    break; // no execution/output: only the explicitly allowed model order may continue
                }
                if (targets.isEmpty()) break;
                Target target = targets.get(0);
                int remainingTargets=targets.size();
                for(int next=position+1;next<modelOrder.size() && remainingTargets<maximum-attempts;next++) {
                    try { remainingTargets+=targets(provider,policy.models().get(modelOrder.get(next)),Set.of(),policy.requirements(),control).size(); }
                    catch(AiRuntimeException unavailable) {
                        if(unavailable.code()!=ErrorCode.MODEL_UNAVAILABLE && unavailable.code()!=ErrorCode.NO_USABLE_KEY && unavailable.code()!=ErrorCode.CAPABILITY_UNSUPPORTED) throw unavailable;
                    }
                }
                int windows=Math.max(1,Math.min(maximum-attempts,remainingTargets));
                long remainingMillis=Math.max(1,java.time.Duration.between(Instant.now(),control.deadline()).toMillis());
                Instant attemptDeadline=Instant.now().plusMillis(Math.max(1,remainingMillis/windows));
                if(attemptDeadline.isAfter(control.deadline())) attemptDeadline=control.deadline();
                final Instant boundedDeadline=attemptDeadline;
                InvocationControl attemptControl=new InvocationControl() {
                    public Instant deadline(){return boundedDeadline;}
                    public boolean cancelled(){return control.cancelled();}
                };
                var reason = attempts == 0 ? (index == first ? AiRequestJournal.SwitchReason.FIRST : AiRequestJournal.SwitchReason.FALLBACK_MODEL)
                        : previousModel == index ? AiRequestJournal.SwitchReason.NEXT_KEY : AiRequestJournal.SwitchReason.FALLBACK_MODEL;
                int allocation = remainingTokens / windows;
                if (allocation < 1) throw new AiRuntimeException(ErrorCode.OUTPUT_LIMIT);
                var attempt = journal.reserve(id, owner, target, maximum, reason, allocation);
                attemptedKeys.add(target.key());
                remainingTokens -= allocation;
                attempts++;
                previousModel = index;
                var outputTouched = new AtomicBoolean();
                var usage = new AtomicReference<Usage>();
                var buffer = new OutputBuffer(id, owner, control, outputTouched,work.privateOutput());
                ExecutionOutcome outcome;
                try {
                    outcome = provider.execute(new ExecutionRequest(work.invocation(), attempt.attemptId(), target,
                            work.messages(), policy.requirements(), allocation), new StreamObserver() {
                        @Override public void onDelta(String text) { buffer.delta(text); }
                        @Override public void onUsage(Usage measured) { usage.set(measured); }
                    }, attemptControl);
                    if (outcome == null) throw new AiRuntimeException(ErrorCode.INVALID_RESPONSE);
                    if (outcome.status() != ExecutionStatus.CANCELLED && outcome.status() != ExecutionStatus.TIMED_OUT) buffer.flush();
                    boolean started = outputTouched.get() || outcome.outputStarted();
                    Usage measured = outcome.usage() == null ? usage.get() : outcome.usage();
                    if (outcome.status() == ExecutionStatus.SUCCEEDED && !outputTouched.get()) {
                        outcome = new ExecutionOutcome(ExecutionStatus.FAILED, ErrorCode.INVALID_RESPONSE, false, started,
                                measured, outcome.providerRequestId(), outcome.elapsedMs());
                    } else if (started && outcome.status() != ExecutionStatus.SUCCEEDED && outcome.status() != ExecutionStatus.CANCELLED) {
                        outcome = new ExecutionOutcome(ExecutionStatus.INCOMPLETE, outcome.errorCode(), false, true,
                                measured, outcome.providerRequestId(), outcome.elapsedMs());
                    } else {
                        outcome = new ExecutionOutcome(outcome.status(), outcome.errorCode(), outcome.retryable() && !started,
                                started, measured, outcome.providerRequestId(), outcome.elapsedMs());
                    }
                } catch (AiRuntimeException safe) {
                    outcome = failure(safe.code(), outputTouched.get(), usage.get());
                } catch (RuntimeException unexpected) {
                    outcome = failure(ErrorCode.INTERNAL_FAILURE, outputTouched.get(), usage.get());
                }
                journal.complete(id, attempt.attemptId(), outcome);
                if (outcome.status() == ExecutionStatus.SUCCEEDED) {
                    lifecycle.finish(id, owner, AiRequestStatus.SUCCEEDED, null, journal.usage(id));
                    return;
                }
                lastCode = outcome.errorCode();
                if (!outcome.retryable() || outcome.outputStarted() || terminalError(lastCode)) {
                    finish(id, owner, AiRequestStatus.valueOf(outcome.status().name()), lastCode);
                    return;
                }
            }
            if (attempts >= maximum || remainingTokens <= 0) break;
        }
        finish(id, owner, lastCode==ErrorCode.DEADLINE_EXCEEDED?AiRequestStatus.TIMED_OUT:AiRequestStatus.FAILED, lastCode);
    }

    private void validateTemplate(Work work, InvocationControl control) {
        if(work.sceneAccessCheck()!=null){control.check();work.sceneAccessCheck().run();return;}
        var template = templates.getIfUnique();
        if (template == null) throw new AiRuntimeException(ErrorCode.DEPENDENCY_UNAVAILABLE);
        var validation = template.validateBinding(work.policy().template(), work.suppliedInputs(), work.invocation(), control);
        if (!validation.valid()) throw new AiRuntimeException(validation.violations().get(0).code());
        var current = template.publishedSnapshot(work.policy().template(), work.invocation(), control);
        if (!current.version().equals(work.policy().template())
                || !new HashSet<>(current.resources()).equals(new HashSet<>(work.resources()))) {
            throw new AiRuntimeException(ErrorCode.VERSION_CONFLICT);
        }
    }

    private List<Target> targets(AiProviderRuntime provider, ModelBinding binding, Set<KeyRef> tried,
                                  Requirements requirements, InvocationControl control) {
        List<Target> result = provider.resolveTargets(binding, Set.copyOf(tried), control);
        var available = new LinkedHashMap<KeyRef, Target>();
        for (Target target : result) {
            if (!target.model().equals(binding.model()) || tried.contains(target.key()) || !target.capabilities().supports(requirements)
                    || (!binding.allowedKeys().isEmpty() && !binding.allowedKeys().contains(target.key()))) {
                throw new AiRuntimeException(ErrorCode.INVALID_RESPONSE);
            }
            available.putIfAbsent(target.key(), target);
        }
        if (binding.keyMode() == KeyMode.AUTO) return List.copyOf(available.values());
        var order = new ArrayList<KeyRef>();
        order.add(binding.fixedKey()); order.addAll(binding.fallbackKeys());
        return order.stream().map(available::get).filter(Objects::nonNull).toList();
    }

    private ExecutionOutcome failure(ErrorCode code, boolean started, Usage usage) {
        ExecutionStatus status = code == ErrorCode.CANCELLED ? ExecutionStatus.CANCELLED
                : started ? ExecutionStatus.INCOMPLETE : code == ErrorCode.DEADLINE_EXCEEDED ? ExecutionStatus.TIMED_OUT : ExecutionStatus.FAILED;
        return new ExecutionOutcome(status, code, false, started, usage, null, 0);
    }

    private boolean terminalError(ErrorCode code) {
        return code == ErrorCode.INVALID_INPUT || code == ErrorCode.CONTENT_REJECTED || code == ErrorCode.CANCELLED
                || code == ErrorCode.DEPENDENCY_UNAVAILABLE;
    }

    private void finish(long id, AiRequestStore.Owner owner, AiRequestStatus terminal, ErrorCode code) {
        lifecycle.finish(id, owner, terminal, code, journal.usage(id));
    }

    private AiRequestStore.Owner owner(Invocation invocation) {
        String subject = invocation.actor().servicePrincipalRef() == null ? invocation.actor().subjectId()
                : "service:" + invocation.actor().servicePrincipalRef();
        return new AiRequestStore.Owner(subject, invocation.actor().tenantId());
    }

    private final class RequestControl implements InvocationControl {
        private final long id;
        private final AiRequestStore.Owner owner;
        private final Instant deadline;
        RequestControl(long id, AiRequestStore.Owner owner, Instant deadline) { this.id = id; this.owner = owner; this.deadline = deadline; }
        @Override public Instant deadline() { return deadline; }
        @Override public boolean cancelled() { return Thread.currentThread().isInterrupted() || requests.owned(id, owner).status().terminal(); }
    }

    private static final class AlreadyClaimed extends RuntimeException {
        private AlreadyClaimed() { super(null, null, false, false); }
    }

    private final class OutputBuffer {
        private final long id;
        private final AiRequestStore.Owner owner;
        private final InvocationControl control;
        private final AtomicBoolean touched;
        private final java.util.function.Consumer<String> privateOutput;
        private final StringBuilder pending = new StringBuilder();
        private int total;
        private long lastFlush;
        OutputBuffer(long id, AiRequestStore.Owner owner, InvocationControl control, AtomicBoolean touched,java.util.function.Consumer<String> privateOutput) {
            this.id = id; this.owner = owner; this.control = control; this.touched = touched;this.privateOutput=privateOutput;
        }
        void delta(String text) {
            control.check();
            if (text == null || text.isEmpty()) return;
            touched.set(true);
            total = Math.addExact(total, text.length());
            if (total > 65_536) throw new AiRuntimeException(ErrorCode.OUTPUT_LIMIT);
            pending.append(text);
            if (pending.length() >= 512 || System.nanoTime() - lastFlush > 100_000_000L) flush();
        }
        void flush() {
            while (!pending.isEmpty()) {
                control.check();
                int end = Math.min(2048, pending.length());
                if (end < pending.length() && Character.isHighSurrogate(pending.charAt(end - 1))) end--;
                String text = pending.substring(0, end);
                if (!(privateOutput==null?lifecycle.delta(id,owner,text):lifecycle.privateDelta(id,owner,text,privateOutput))) throw new AiRuntimeException(ErrorCode.CANCELLED);
                pending.delete(0, end);
            }
            lastFlush = System.nanoTime();
        }
    }
}
