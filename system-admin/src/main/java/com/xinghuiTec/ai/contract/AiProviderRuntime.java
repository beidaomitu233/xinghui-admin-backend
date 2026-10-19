package com.xinghuiTec.ai.contract;

import java.util.List;
import java.util.Objects;
import java.util.Set;

import static com.xinghuiTec.ai.contract.AiTypes.*;

/** A implements this. B owns model order, retries, budgets and lifecycle. No implicit retries here. */
public interface AiProviderRuntime {
    List<AvailableModel> availableModels(Requirements requirements);
    Validation validateBindings(List<ModelBinding> bindings, Requirements requirements);

    /** Only verified enabled pairs in the pinned version; excludes attempted keys, never picks another model. */
    List<Target> resolveTargets(ModelBinding binding, Set<KeyRef> attemptedKeys, InvocationControl control);

    /** Exactly one upstream attempt. Recheck emergency disable, decrypt internally, bound I/O and response. */
    ExecutionOutcome execute(ExecutionRequest request, StreamObserver observer, InvocationControl control);

    record AvailableModel(ModelVersionRef model, String configId, String configName, String providerCode,
                          String modelCode, Capabilities capabilities, int usableKeyCount) {
        public AvailableModel {
            Objects.requireNonNull(model); required(configId); required(configName); required(providerCode);
            required(modelCode); Objects.requireNonNull(capabilities);
            if (usableKeyCount < 1) throw new IllegalArgumentException("No usable key");
        }
    }

    enum KeyMode { AUTO, FIXED }
    /** Empty allowedKeys means all verified keys of this version. Fixed fallback is explicitly ordered. */
    record ModelBinding(ModelVersionRef model, KeyMode keyMode, Set<KeyRef> allowedKeys,
                        KeyRef fixedKey, List<KeyRef> fallbackKeys) {
        public ModelBinding {
            Objects.requireNonNull(model); Objects.requireNonNull(keyMode);
            allowedKeys = Set.copyOf(allowedKeys); fallbackKeys = List.copyOf(fallbackKeys);
            if (keyMode == KeyMode.FIXED && fixedKey == null) throw new IllegalArgumentException("Missing fixed key");
            if (keyMode == KeyMode.AUTO && (fixedKey != null || !fallbackKeys.isEmpty()))
                throw new IllegalArgumentException("Automatic mode cannot carry fixed fallback");
            if (keyMode == KeyMode.FIXED && (!allowedKeys.isEmpty()
                    && (!allowedKeys.contains(fixedKey) || !allowedKeys.containsAll(fallbackKeys))))
                throw new IllegalArgumentException("Fixed key outside allowed range");
            if (Set.copyOf(fallbackKeys).size() != fallbackKeys.size()
                    || (fixedKey != null && fallbackKeys.contains(fixedKey)))
                throw new IllegalArgumentException("Duplicate fallback key");
        }
    }

    /** Safe reference only, not a secret lease or authority to bypass runtime checks. */
    record Target(ModelVersionRef model, KeyRef key, String providerCode, String modelCode,
                  String maskedKeyLabel, Capabilities capabilities) {
        public Target {
            Objects.requireNonNull(model); Objects.requireNonNull(key); required(providerCode);
            required(modelCode); required(maskedKeyLabel); Objects.requireNonNull(capabilities);
        }
    }

    record ExecutionRequest(Invocation invocation, String attemptId, Target target,
                            List<AiMessage> messages, Requirements requirements, int maxTokens) {
        public ExecutionRequest {
            Objects.requireNonNull(invocation); required(attemptId); Objects.requireNonNull(target);
            messages = List.copyOf(messages); Objects.requireNonNull(requirements);
            if (messages.isEmpty() || maxTokens < 1) throw new IllegalArgumentException("Invalid execution bounds");
        }
        @Override public String toString() { return "ExecutionRequest[REDACTED]"; }
    }

    enum ExecutionStatus { SUCCEEDED, FAILED, CANCELLED, TIMED_OUT, INCOMPLETE }
    record ExecutionOutcome(ExecutionStatus status, ErrorCode errorCode, boolean retryable,
                            boolean outputStarted, Usage usage, String providerRequestId, long elapsedMs,AiCallTelemetry telemetry) {
        public ExecutionOutcome(ExecutionStatus status,ErrorCode errorCode,boolean retryable,boolean outputStarted,
                                Usage usage,String providerRequestId,long elapsedMs){
            this(status,errorCode,retryable,outputStarted,usage,providerRequestId,elapsedMs,null);
        }
        public ExecutionOutcome {
            Objects.requireNonNull(status);
            if (elapsedMs < 0 || (status == ExecutionStatus.SUCCEEDED && (errorCode != null || retryable))
                    || (status != ExecutionStatus.SUCCEEDED && errorCode == null)
                    || (outputStarted && retryable)
                    || ((status == ExecutionStatus.CANCELLED || status == ExecutionStatus.INCOMPLETE) && retryable))
                throw new IllegalArgumentException("Inconsistent execution outcome");
        }
        @Override public String toString(){return "ExecutionOutcome[REDACTED]";}
    }
}
