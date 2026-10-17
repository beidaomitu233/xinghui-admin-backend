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
    public record DataObject(String objectKey,String formId,List<String> fields) {
        public DataObject { fields=List.copyOf(fields); }
    }
    public record Definition(String name,String purpose,String promptText,String outputRequirement,
                             List<DataObject> dataObjects,ModelPolicy modelPolicy,List<com.xinghuiTec.ai.contract.AiSceneDataRuntime.DataBinding> dataBindings) {
        public Definition(String name,String purpose,String promptText,String outputRequirement,List<DataObject> dataObjects,ModelPolicy modelPolicy){this(name,purpose,promptText,outputRequirement,dataObjects,modelPolicy,List.of());}
        public Definition { dataObjects=dataObjects==null?List.of():List.copyOf(dataObjects);dataBindings=dataBindings==null?List.of():List.copyOf(dataBindings); }
        @Override public String toString() { return "SceneDefinition[REDACTED]"; }
    }
    /** Creates a stable unconfigured identity. Enabled is intent only until a valid executable save. */
    public record Create(String name,String purpose,Kind kind,String formId,String clientMutationId) {}
    public record Save(int expectedRevision,String clientMutationId,Definition definition,boolean confirmedLegacyConversion) {
        public Save(int expectedRevision,String clientMutationId,Definition definition){this(expectedRevision,clientMutationId,definition,false);}
        @Override public String toString() { return "SceneSave[REDACTED]"; }
    }
    public record Enabled(int expectedRevision,String clientMutationId,boolean enabled) {}
    public record Summary(String sceneKey,String name,String purpose,Kind kind,String formId,Source source,
                          boolean enabled,int revision,String currentVersionId,Availability availability,Instant updatedAt) {}
    /** Only configuration-authorized management endpoints return Definition. */
    public record LegacyMessage(String section,String text) {
        @Override public String toString(){return "LegacyMessage[REDACTED]";}
    }
    public record LegacyEditor(String promptText,String outputRequirement,List<DataObject> dataObjects) {
        public LegacyEditor {dataObjects=List.copyOf(dataObjects);}
        @Override public String toString(){return "LegacyEditor[REDACTED]";}
    }
    public record LegacyDetails(String compatibility,List<String> reasons,boolean requiresConfirmation,
                                List<String> conversionNotes,LegacyEditor editor,List<LegacyMessage> originalMessages,Map<String,Object> originalDefinition) {
        public LegacyDetails{reasons=List.copyOf(reasons);conversionNotes=List.copyOf(conversionNotes);originalMessages=List.copyOf(originalMessages);originalDefinition=java.util.Collections.unmodifiableMap(new java.util.LinkedHashMap<>(originalDefinition));}
        @Override public String toString(){return "LegacyDetails[REDACTED]";}
    }
    public record Detail(Summary scene,Definition definition,LegacyDetails legacy) {
        public Detail(Summary scene,Definition definition){this(scene,definition,null);}
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
    public record Submission(BusinessRef businessRef,Map<String,Object> inputs,String idempotencyKey,String conversationId,Integer expectedConversationRevision) {
        public Submission(BusinessRef businessRef,Map<String,Object> inputs,String idempotencyKey){this(businessRef,inputs,idempotencyKey,null,null);}
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
                        int revision,Availability availability,boolean contextRequired,String contextSourceKey,String contextSourceLabel,boolean canStartFromMonitor) {
        public Entry(String sceneKey,String name,Kind kind,String formId,String currentVersionId,int revision,Availability availability){this(sceneKey,name,kind,formId,currentVersionId,revision,availability,kind==Kind.RECORD_SUMMARY,null,null,kind!=Kind.RECORD_SUMMARY);}
    }
    public record Request(String requestId,String sceneKey,String sceneVersionId,String kind,BusinessRef businessRef,
                          String status,String stage,String errorCode,boolean outputStarted,int attemptCount,
                          Long inputTokens,Long outputTokens,boolean usageKnown,Instant deadline,
                          Instant createdAt,Instant finishedAt,String conversationId,String userMessageId,String assistantMessageId) {
        public Request(String requestId,String sceneKey,String sceneVersionId,String kind,BusinessRef businessRef,String status,String stage,String errorCode,boolean outputStarted,int attemptCount,Long inputTokens,Long outputTokens,boolean usageKnown,Instant deadline,Instant createdAt,Instant finishedAt){this(requestId,sceneKey,sceneVersionId,kind,businessRef,status,stage,errorCode,outputStarted,attemptCount,inputTokens,outputTokens,usageKnown,deadline,createdAt,finishedAt,null,null,null);}
    }
    public record Message(String role,String content) {
        @Override public String toString() { return "SceneMessage[REDACTED]"; }
    }
    public record Preview(String sceneKey,String sceneVersionId,List<Message> originalMessages,List<Message> renderedMessages,
                          Map<String,String> inputSummary,Map<String,String> resourceSummary,List<String> notices,
                          Map<String,Object> dataValues,List<com.xinghuiTec.ai.contract.AiSceneDataRuntime.ReadReceipt> reads) {
        public Preview(String sceneKey,String sceneVersionId,List<Message> originalMessages,List<Message> renderedMessages,Map<String,String> inputSummary,Map<String,String> resourceSummary,List<String> notices){this(sceneKey,sceneVersionId,originalMessages,renderedMessages,inputSummary,resourceSummary,notices,Map.of(),List.of());}
        public Preview {originalMessages=List.copyOf(originalMessages);renderedMessages=List.copyOf(renderedMessages);
            inputSummary=Map.copyOf(inputSummary);resourceSummary=Map.copyOf(resourceSummary);notices=List.copyOf(notices);
            dataValues=dataValues==null?Map.of():new com.xinghuiTec.ai.contract.AiInputs(dataValues).values();reads=reads==null?List.of():List.copyOf(reads);}
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
