package com.xinghuiTec.ai.scene;

import java.time.Instant;
import java.util.List;
import java.util.Map;

/** AI-UX public wire types. A scene/version is its own identity, never an application ID alias. */
public final class SceneDtos {
    private SceneDtos() {}
    public enum Kind { GENERAL, RECORD_SUMMARY }
    public enum Source { NATIVE, LEGACY_V04 }
    public enum ModelMode { FIXED, ROUND_ROBIN }
    public enum KeyMode { AUTO, FIXED }
    public enum OutputFormat { TEXT, JSON_OBJECT }
    public enum AvailabilityState { READY, UNCONFIGURED, DISABLED, UNAVAILABLE }
    public record Availability(AvailabilityState state,List<String> reasons) {
        public Availability { reasons=List.copyOf(reasons); }
    }
    public record KeyRef(String keyRef,String keyVersion) {}
    public record ModelRef(String modelRef,String configVersion) {}
    public record ModelBinding(ModelRef model,KeyMode keyMode,List<KeyRef> allowedKeys,KeyRef fixedKey,List<KeyRef> fallbackKeys) {
        public ModelBinding { allowedKeys=List.copyOf(allowedKeys);fallbackKeys=List.copyOf(fallbackKeys); }
    }
    public record ModelPolicy(ModelMode mode,List<ModelBinding> models,boolean fallback,OutputFormat outputFormat,
                              int timeoutSeconds,int maxAttempts,int maxConcurrent,int maxTokens) {
        public ModelPolicy { models=List.copyOf(models); }
    }
    /** Directory keys and selected field names only; never SQL, URLs, classes, record bodies or caller identity. */
    public record DataObject(String objectKey,List<String> fields) {
        public DataObject { fields=List.copyOf(fields); }
    }
    public record Definition(String name,String purpose,String promptText,String outputRequirement,
                             List<DataObject> dataObjects,ModelPolicy modelPolicy) {
        public Definition { dataObjects=List.copyOf(dataObjects); }
        @Override public String toString() { return "SceneDefinition[REDACTED]"; }
    }
    /** Creates a stable unconfigured identity. Enabled is intent only until a valid executable save. */
    public record Create(String name,String purpose,Kind kind,String formId,String clientMutationId) {}
    public record Save(int expectedRevision,String clientMutationId,Definition definition) {
        @Override public String toString() { return "SceneSave[REDACTED]"; }
    }
    public record Enabled(int expectedRevision,String clientMutationId,boolean enabled) {}
    public record Summary(String sceneKey,String name,String purpose,Kind kind,String formId,Source source,
                          boolean enabled,int revision,String currentVersionId,Availability availability,Instant updatedAt) {}
    /** Only configuration-authorized management endpoints return Definition. */
    public record Detail(Summary scene,Definition definition) {
        @Override public String toString() { return "SceneDetail[REDACTED]"; }
    }
    public record SaveResult(Detail detail,String clientMutationId,Instant effectiveFrom) {}
    public record Page(List<Summary> records,String total,int pageNum,int pageSize) {
        public Page {records=List.copyOf(records);}
    }
    public record Workspace(Page scenes,Detail selected) {}
    public record FieldError(String field,String code) {}
    public record ErrorDetails(List<FieldError> fields,Integer currentRevision,String currentVersionId,String requestId) {
        public ErrorDetails {fields=List.copyOf(fields);}
    }
    public record ModelOption(ModelRef model,String configId,String configName,String providerCode,String modelCode,
                              boolean streaming,List<OutputFormat> outputFormats,int usableKeyCount) {
        public ModelOption {outputFormats=List.copyOf(outputFormats);}
    }
    public record KeyOption(KeyRef key,String maskedLabel) {}
    public record BusinessRef(String type,String id) {}
    public record Submission(BusinessRef businessRef,Map<String,Object> inputs,String idempotencyKey) {
        @Override public String toString() { return "SceneSubmission[REDACTED]"; }
    }
    public record PreviewInput(int expectedRevision,BusinessRef businessRef,Map<String,Object> inputs) {
        @Override public String toString() { return "ScenePreviewInput[REDACTED]"; }
    }
    public record TestInput(int expectedRevision,int modelIndex,boolean confirmedCosts,Submission submission) {
        @Override public String toString() { return "SceneTestInput[REDACTED]"; }
    }
    /** Business callers receive no prompt, model key material, internal application or template IDs. */
    public record Entry(String sceneKey,String name,Kind kind,String formId,String currentVersionId,
                        int revision,Availability availability) {}
    public record Request(String requestId,String sceneKey,String sceneVersionId,String kind,BusinessRef businessRef,
                          String status,String stage,String errorCode,boolean outputStarted,int attemptCount,
                          Long inputTokens,Long outputTokens,boolean usageKnown,Instant deadline,
                          Instant createdAt,Instant finishedAt) {}
    public record Message(String role,String content) {
        @Override public String toString() { return "SceneMessage[REDACTED]"; }
    }
    public record Preview(String sceneKey,String sceneVersionId,List<Message> originalMessages,List<Message> renderedMessages,
                          Map<String,String> inputSummary,Map<String,String> resourceSummary,List<String> notices) {
        public Preview {originalMessages=List.copyOf(originalMessages);renderedMessages=List.copyOf(renderedMessages);
            inputSummary=Map.copyOf(inputSummary);resourceSummary=Map.copyOf(resourceSummary);notices=List.copyOf(notices);}
        @Override public String toString() { return "ScenePreview[REDACTED]"; }
    }
    public record Window(Instant from,Instant to) {}
    public record KeyCards(Long enabledKeyVersions,Long recentlyFailedKeyVersions,Long knownAdmittedPairs,
                           Long unattributedAttempts,String unavailableReason) {}
    public record Calls(long requests,long businessRequests,long testRequests,long succeededRequests,long failedRequests,
                        long modelAttempts,long failedAttempts,Long knownInputTokens,Long knownOutputTokens,
                        long unknownInputAttempts,long unknownOutputAttempts) {}
    public record Monitor(Instant observedAt,Window window,String scope,String requestKind,KeyCards keyCards,Calls calls) {}
}
