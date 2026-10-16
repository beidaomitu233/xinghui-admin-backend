package com.xinghuiTec.ai.contract;

import java.util.List;
import java.util.Objects;
import java.util.Set;

import static com.xinghuiTec.ai.contract.AiTypes.*;

/** C implements exact-version lookup and authorized rendering; never chooses or calls a model. */
public interface AiTemplateRuntime {
    PublishedTemplate publishedSnapshot(TemplateVersionRef version, Invocation invocation, InvocationControl control);
    Validation validateBinding(TemplateVersionRef version, Set<String> suppliedInputs,
                               Invocation invocation, InvocationControl control);
    RenderedTemplate renderPublished(RenderRequest request, InvocationControl control);

    enum VariableType { STRING, NUMBER, BOOLEAN, OBJECT, ARRAY }
    record Variable(String name, VariableType type, boolean required, boolean hasDefault) {
        public Variable { AiTypes.required(name); Objects.requireNonNull(type); }
    }
    /** Metadata only: no prompt text, default value or resource contents. */
    record PublishedTemplate(TemplateVersionRef version, List<Variable> variables,
                             List<ResourceVersionRef> resources) {
        public PublishedTemplate {
            Objects.requireNonNull(version); variables = List.copyOf(variables); resources = List.copyOf(resources);
        }
    }
    record RenderRequest(TemplateVersionRef version, AiInputs inputs, Invocation invocation) {
        public RenderRequest { Objects.requireNonNull(version); Objects.requireNonNull(inputs); Objects.requireNonNull(invocation); }
        @Override public String toString() { return "RenderRequest[REDACTED]"; }
    }
    enum RenderNoticeKind { DEFAULT_USED, OPTIONAL_RESOURCE_MISSING, TRUNCATED }
    /** Reference names and classifications only, never the missing/truncated/default content. */
    record RenderNotice(RenderNoticeKind kind, String reference) {
        public RenderNotice { Objects.requireNonNull(kind); required(reference); }
    }
    record RenderedTemplate(TemplateVersionRef version, List<AiMessage> messages,
                            List<ResourceVersionRef> resources, List<RenderNotice> notices) {
        public RenderedTemplate {
            Objects.requireNonNull(version); messages = List.copyOf(messages);
            resources = List.copyOf(resources); notices = List.copyOf(notices);
        }
        @Override public String toString() { return "RenderedTemplate[REDACTED]"; }
    }
}
