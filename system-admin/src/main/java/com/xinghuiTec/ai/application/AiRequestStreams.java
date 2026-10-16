package com.xinghuiTec.ai.application;

import com.xinghuiTec.ai.contract.AiRuntimeException;
import com.xinghuiTec.ai.contract.AiTypes.ErrorCode;
import com.xinghuiTec.ai.contract.AiTypes.TrustedActor;
import jakarta.annotation.PreDestroy;
import org.springframework.stereotype.Service;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;

import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;

/** Bounded authenticated subscriptions. A subscription never creates or dispatches a model call. */
@Service
public class AiRequestStreams {
    private final AiRuntimeService runtime;
    private final AiRuntimeEvents events;
    private final AiRequestLifecycle lifecycle;
    private final ThreadPoolExecutor subscribers;

    public AiRequestStreams(AiRuntimeService runtime, AiRuntimeEvents events, AiRequestLifecycle lifecycle) {
        this.runtime = runtime; this.events = events; this.lifecycle = lifecycle;
        var counter = new AtomicInteger();
        subscribers = new ThreadPoolExecutor(0, 16, 30, TimeUnit.SECONDS, new SynchronousQueue<>(), task -> {
            Thread thread = new Thread(task, "ai-events-" + counter.incrementAndGet()); thread.setDaemon(true); return thread;
        }, new ThreadPoolExecutor.AbortPolicy());
    }

    public SseEmitter subscribe(long requestId, TrustedActor actor) {
        var request = runtime.status(requestId, actor);
        runtime.authorizeEvents(request, actor);
        if (request.status().terminal() && events.after(request.requestId(), 0).isEmpty()) {
            throw new AiRuntimeException(ErrorCode.RESOURCE_UNAVAILABLE);
        }
        var emitter = new SseEmitter(150_000L);
        var closed = new AtomicBoolean();
        var terminalSent = new AtomicBoolean();
        Runnable disconnected = () -> {
            if (!closed.compareAndSet(false, true) || terminalSent.get()) return;
            try {
                var current = runtime.status(requestId, actor);
                if (!current.status().terminal()) lifecycle.finish(requestId, AiRuntimeService.owner(actor),
                        current.outputStarted() ? AiRequestStatus.INCOMPLETE : AiRequestStatus.FAILED, ErrorCode.NETWORK_ERROR, null);
            } catch (RuntimeException unavailable) { /* Durable deadline recovery is the fallback; never replay. */ }
        };
        emitter.onCompletion(disconnected);
        emitter.onTimeout(disconnected);
        emitter.onError(ignored -> disconnected.run());
        try {
            subscribers.execute(() -> {
                long sequence = 0, terminalWait = 0, lastAuth = System.nanoTime();
                try {
                    while (!closed.get() && !Thread.currentThread().isInterrupted()) {
                        var current = runtime.status(requestId, actor);
                        if (System.nanoTime() - lastAuth > 1_000_000_000L) {
                            runtime.authorizeEvents(current, actor); lastAuth = System.nanoTime();
                        }
                        var batch = events.after(current.requestId(), sequence);
                        for (var event : batch) {
                            if (closed.get()) return;
                            if (event.sequence() != sequence + 1) throw new AiRuntimeException(ErrorCode.INVALID_RESPONSE);
                            boolean terminal = event.name() == AiRuntimeEvents.Name.COMPLETED || event.name() == AiRuntimeEvents.Name.CANCELLED
                                    || event.name() == AiRuntimeEvents.Name.FAILED;
                            if (terminal && runtime.status(requestId, actor).status() != event.status()) {
                                throw new AiRuntimeException(ErrorCode.INVALID_RESPONSE);
                            }
                            emitter.send(SseEmitter.event().id(Long.toString(event.sequence())).name(event.name().wireName()).data(AiRuntimeWire.event(event)));
                            sequence = event.sequence();
                            if (terminal) { terminalSent.set(true); closed.set(true); emitter.complete(); return; }
                        }
                        if (current.status().terminal() && batch.isEmpty()) {
                            if (terminalWait == 0) terminalWait = System.nanoTime();
                            if (System.nanoTime() - terminalWait > 2_000_000_000L) throw new AiRuntimeException(ErrorCode.RESOURCE_UNAVAILABLE);
                        }
                        Thread.sleep(50);
                    }
                } catch (InterruptedException interrupted) {
                    Thread.currentThread().interrupt(); disconnected.run(); emitter.complete();
                } catch (Exception unavailable) {
                    disconnected.run(); emitter.completeWithError(new AiRuntimeException(ErrorCode.RESOURCE_UNAVAILABLE));
                }
            });
        } catch (RejectedExecutionException full) {
            throw new AiRuntimeException(ErrorCode.DEPENDENCY_UNAVAILABLE);
        }
        return emitter;
    }

    @PreDestroy public void stop() { subscribers.shutdownNow(); }
}
