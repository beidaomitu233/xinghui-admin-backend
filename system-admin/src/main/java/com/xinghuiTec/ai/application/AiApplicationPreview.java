package com.xinghuiTec.ai.application;

import com.xinghuiTec.ai.contract.*;
import com.xinghuiTec.ai.contract.AiTypes.*;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.stereotype.Service;

import java.time.Instant;
import java.util.Map;
import java.util.UUID;

/** Bound draft preview only: no provider, runtime admission, journal or publication evidence. */
@Service
public class AiApplicationPreview {
    private final AiApplicationStore store;
    private final ObjectProvider<AiTemplatePreviewRuntime> templates;

    public AiApplicationPreview(AiApplicationStore store, ObjectProvider<AiTemplatePreviewRuntime> templates) {
        this.store = store; this.templates = templates;
    }

    public record Input(Integer revision, BusinessRef businessRef, Map<String, Object> inputs) {
        @Override public String toString() { return "ApplicationPreviewInput[REDACTED]"; }
    }

    public AiTemplatePreviewRuntime.PublishedPreview preview(long appId, Input input, TrustedActor actor, String trace) {
        if (appId < 1 || input == null || input.revision() == null || input.revision() < 0
                || input.businessRef() == null || input.inputs() == null || input.inputs().size() > 32) {
            throw new AiRuntimeException(ErrorCode.INVALID_INPUT);
        }
        UUID.fromString(trace); // server generated HTTP trace, never supplied by the JSON envelope
        var app = store.get(appId);
        if (app.revision() != input.revision()) throw new AiRuntimeException(ErrorCode.VERSION_CONFLICT);
        var policy = app.draft();
        if (!policy.permitsSubject(actor)) throw new AiRuntimeException(ErrorCode.FORBIDDEN);
        if (policy.template() == null) throw new AiRuntimeException(ErrorCode.TEMPLATE_UNAVAILABLE);
        AiApplicationInputRules.validate(policy, input.businessRef(), input.inputs());
        if (!policy.inputNames().containsAll(input.inputs().keySet())) throw new AiRuntimeException(ErrorCode.INVALID_INPUT);
        var runtime = templates.getIfUnique();
        if (runtime == null) throw new AiRuntimeException(ErrorCode.DEPENDENCY_UNAVAILABLE);
        InvocationControl control = new InvocationControl() {
            private final Instant deadline = Instant.now().plusSeconds(10);
            @Override public Instant deadline() { return deadline; }
            @Override public boolean cancelled() { return false; }
        };
        var invocation = new Invocation(actor, app.appId(), "draft:" + app.draftRevision(), input.businessRef(), trace);
        var result = runtime.previewPublished(new AiTemplateRuntime.RenderRequest(policy.template(), new AiInputs(input.inputs()), invocation), control);
        control.check();
        if (result == null || !policy.template().equals(result.version()) || store.get(appId).revision() != input.revision()) {
            throw new AiRuntimeException(ErrorCode.VERSION_CONFLICT);
        }
        return result;
    }
}
