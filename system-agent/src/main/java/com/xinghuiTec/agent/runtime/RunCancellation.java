package com.xinghuiTec.agent.runtime;

import com.xinghuiTec.agent.api.AgentCall;
import reactor.core.publisher.Flux;

import java.time.Duration;
import java.util.concurrent.atomic.AtomicReference;

/** Uses a normal stop signal to cancel the source before reporting why it stopped. */
final class RunCancellation {
    private RunCancellation() {}
    static <T> Flux<T> guard(Flux<T> source, AgentCall call) {
        return Flux.defer(() -> {
            var failure = new AtomicReference<RuntimeException>();
            var stop = Flux.interval(Duration.ofMillis(100)).<Long>handle((tick, sink) -> {
                try { call.check(); }
                catch (RuntimeException stopped) { failure.set(stopped); sink.next(tick); }
            }).next();
            // An error from takeUntilOther's other publisher does not cancel its main
            // subscription in Reactor 3.6. Signal normally, then propagate the saved error.
            return source.takeUntilOther(stop).concatWith(Flux.defer(() ->
                    failure.get() == null ? Flux.empty() : Flux.error(failure.get())));
        });
    }
}
