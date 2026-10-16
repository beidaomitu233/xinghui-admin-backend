package com.xinghuiTec.ai.contract;

import java.time.Instant;
import java.util.List;
import java.util.Objects;
import java.util.Set;

/** Internal service values. These types are not HTTP request authentication DTOs. */
public final class AiTypes {
    private AiTypes() {}

    public static String required(String value) {
        if (value == null || value.isBlank()) throw new IllegalArgumentException("Required reference is missing");
        return value;
    }

    /** Construct only from authenticated server context; resource services must recheck access. */
    public record TrustedActor(String subjectId, String tenantId, Set<String> authorities,
                               String servicePrincipalRef) {
        public TrustedActor {
            required(subjectId);
            required(tenantId);
            authorities = Set.copyOf(authorities);
        }
        @Override public String toString() { return "TrustedActor[REDACTED]"; }
    }

    public record BusinessRef(String type, String id) {
        public BusinessRef { required(type); required(id); }
        @Override public String toString() { return "BusinessRef[REDACTED]"; }
    }

    public record Invocation(TrustedActor actor, String appId, String appVersionId,
                             BusinessRef businessRef, String requestId) {
        public Invocation {
            Objects.requireNonNull(actor); required(appId); required(appVersionId);
            Objects.requireNonNull(businessRef); required(requestId);
        }
        @Override public String toString() { return "Invocation[REDACTED]"; }
    }

    public record ModelVersionRef(String modelRef, String configVersion) {
        public ModelVersionRef { required(modelRef); required(configVersion); }
    }
    public record TemplateVersionRef(String templateId, String templateVersionId) {
        public TemplateVersionRef { required(templateId); required(templateVersionId); }
    }
    public record ResourceVersionRef(String resourceId, String resourceVersionId) {
        public ResourceVersionRef { required(resourceId); required(resourceVersionId); }
    }
    public record KeyRef(String keyRef, String keyVersion) {
        public KeyRef { required(keyRef); required(keyVersion); }
    }

    public enum MessageRole { SYSTEM, USER, ASSISTANT }
    public record AiMessage(MessageRole role, String content) {
        public AiMessage { Objects.requireNonNull(role); Objects.requireNonNull(content); }
        @Override public String toString() { return "AiMessage[role=" + role + ",content=REDACTED]"; }
    }

    public enum OutputFormat { TEXT, JSON_OBJECT }
    public record Requirements(OutputFormat outputFormat, boolean streaming, boolean tools) {
        public Requirements { Objects.requireNonNull(outputFormat); }
    }
    public record Capabilities(Set<OutputFormat> outputFormats, boolean streaming, boolean tools) {
        public Capabilities { outputFormats = Set.copyOf(outputFormats); }
        public boolean supports(Requirements requirements) {
            return outputFormats.contains(requirements.outputFormat())
                    && (!requirements.streaming() || streaming) && (!requirements.tools() || tools);
        }
    }

    /** Null means unknown; zero is a measured zero, never an inferred missing value. */
    public record Usage(Long inputTokens, Long outputTokens, Long totalTokens) {
        public Usage {
            if ((inputTokens != null && inputTokens < 0) || (outputTokens != null && outputTokens < 0)
                    || (totalTokens != null && totalTokens < 0)) throw new IllegalArgumentException("Negative usage");
        }
    }

    public enum ErrorCode {
        UNAUTHENTICATED, FORBIDDEN, NOT_FOUND, INVALID_INPUT, VERSION_CONFLICT,
        MODEL_UNAVAILABLE, NO_USABLE_KEY, CAPABILITY_UNSUPPORTED, CREDENTIAL_UNAVAILABLE,
        DEPENDENCY_UNAVAILABLE, TEMPLATE_UNAVAILABLE, RESOURCE_UNAVAILABLE,
        MISSING_VARIABLE, RESOURCE_FORBIDDEN, RESOURCE_TIMEOUT,
        AUTH_FAILED, RATE_LIMITED, PROVIDER_UNAVAILABLE, NETWORK_ERROR, INVALID_RESPONSE,
        CONTENT_REJECTED, OUTPUT_LIMIT, CANCELLED, DEADLINE_EXCEEDED, INTERNAL_FAILURE
    }

    /** Error fields are enum codes, never provider bodies, prompts or exception messages. */
    public record Violation(String field, ErrorCode code) {
        public Violation { required(field); Objects.requireNonNull(code); }
    }
    public record Validation(List<Violation> violations) {
        public Validation { violations = List.copyOf(violations); }
        public boolean valid() { return violations.isEmpty(); }
    }

    /** Check before each new side effect. Providers must also interrupt in-flight I/O on cancel. */
    public interface InvocationControl {
        Instant deadline();
        boolean cancelled();
        default void check() {
            if (cancelled()) throw new AiRuntimeException(ErrorCode.CANCELLED);
            if (!Instant.now().isBefore(Objects.requireNonNull(deadline())))
                throw new AiRuntimeException(ErrorCode.DEADLINE_EXCEEDED);
        }
    }

    /** Delta may contain business content: never log or persist by default. No callback after return. */
    public interface StreamObserver {
        void onDelta(String text);
        void onUsage(Usage usage);
    }
}
