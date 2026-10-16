package com.xinghuiTec.ai.application;

import com.xinghuiTec.ai.contract.AiTypes.ErrorCode;
import com.xinghuiTec.ai.contract.AiTypes.Usage;
import org.springframework.stereotype.Service;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;
import org.springframework.transaction.support.TransactionSynchronization;
import org.springframework.transaction.support.TransactionSynchronizationManager;

import java.time.Instant;

/** Request row locks order deltas versus cancellation across nodes; terminal state wins exactly once. */
@Service
public class AiRequestLifecycle {
    private final AiRequestStore store;
    private final AiRuntimeEvents events;
    private final TransactionTemplate transaction;

    public AiRequestLifecycle(AiRequestStore store, AiRuntimeEvents events, PlatformTransactionManager manager) {
        this.store = store;
        this.events = events;
        this.transaction = new TransactionTemplate(manager);
    }

    public void accepted(long id, AiRequestStore.Owner owner) {
        transaction.executeWithoutResult(status -> {
            var current = store.currentOwned(id, owner);
            if (!current.status().terminal()) afterCommit(() -> events.append(event(AiRuntimeEvents.Name.ACCEPTED, current, null, null, null)));
        });
    }

    public boolean delta(long id, AiRequestStore.Owner owner, String text) {
        return Boolean.TRUE.equals(transaction.execute(status -> {
            var current = store.currentOwned(id, owner);
            if (current.status() != AiRequestStatus.RUNNING || !store.markOutputStarted(id, Instant.now())) return false;
            // A rolled-back output marker must never release a fragment. If this callback fails,
            // output_started is already durable, so the worker must end incomplete, never retry.
            afterCommit(() -> events.append(event(AiRuntimeEvents.Name.DELTA, current, text, null, null)));
            return true;
        }));
    }

    public boolean finish(long id, AiRequestStore.Owner owner, AiRequestStatus terminal, ErrorCode code, Usage usage) {
        return Boolean.TRUE.equals(transaction.execute(status -> {
            var current = store.currentOwned(id, owner);
            Instant now = Instant.now();
            AiRequestStatus chosen = terminal;
            ErrorCode chosenCode = code;
            if (terminal == AiRequestStatus.SUCCEEDED && !now.isBefore(current.deadline())) {
                chosen = current.outputStarted() ? AiRequestStatus.INCOMPLETE : AiRequestStatus.TIMED_OUT;
                chosenCode = ErrorCode.DEADLINE_EXCEEDED;
            }
            boolean changed = store.finish(id, chosen, chosenCode == null ? null : chosenCode.name(),
                    usage == null ? null : usage.inputTokens(), usage == null ? null : usage.outputTokens(), now);
            if (!changed) return false;
            var finished = store.currentOwned(id, owner);
            var name = chosen == AiRequestStatus.SUCCEEDED ? AiRuntimeEvents.Name.COMPLETED
                    : chosen == AiRequestStatus.CANCELLED ? AiRuntimeEvents.Name.CANCELLED : AiRuntimeEvents.Name.FAILED;
            ErrorCode finalCode = chosenCode;
            afterCommit(() -> {
                try {
                    if (usage != null) events.append(event(AiRuntimeEvents.Name.USAGE, finished, null, usage, null));
                    events.append(event(name, finished, null, null, finalCode));
                } catch (RuntimeException unavailableBuffer) {
                    // Durable terminal already committed. Query original state; never replay a model.
                }
            });
            return true;
        }));
    }

    private void afterCommit(Runnable publication) {
        if (!TransactionSynchronizationManager.isSynchronizationActive()) {
            throw new IllegalStateException("Runtime event requires transaction synchronization");
        }
        TransactionSynchronizationManager.registerSynchronization(new TransactionSynchronization() {
            @Override public void afterCommit() { publication.run(); }
        });
    }

    private AiRuntimeEvents.Event event(AiRuntimeEvents.Name name, AiRequestStore.Request current,
                                        String text, Usage usage, ErrorCode code) {
        return new AiRuntimeEvents.Event(name, current.requestId(), 0, current.status(), text,
                usage == null ? null : usage.inputTokens(), usage == null ? null : usage.outputTokens(), code);
    }
}
