package com.xinghuiTec.agent.api;

import java.time.Instant;
import java.util.Objects;
import java.util.function.BooleanSupplier;

/** Trusted execution context. The call id identifies one model turn inside a run. */
public record AgentCall(AgentActor actor, String sessionId, String runId, String callId,
                        Instant deadline, BooleanSupplier cancelled) {
    public AgentCall {
        Objects.requireNonNull(actor); Objects.requireNonNull(sessionId); Objects.requireNonNull(runId);
        Objects.requireNonNull(callId); Objects.requireNonNull(deadline); Objects.requireNonNull(cancelled);
    }
    public void check() {
        if (!Instant.now().isBefore(deadline)) throw new AgentProblem("DEADLINE_EXCEEDED", "本轮任务已超时");
        if (cancelled.getAsBoolean()) throw new AgentProblem("RUN_STOPPED", "任务已停止");
    }
}
