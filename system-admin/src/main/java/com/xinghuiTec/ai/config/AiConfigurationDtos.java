package com.xinghuiTec.ai.config;

import com.fasterxml.jackson.annotation.JsonProperty;
import java.time.LocalDateTime;
import java.util.List;

/** HTTP types never contain stored secret material. All database identifiers remain decimal strings. */
public final class AiConfigurationDtos {
    private AiConfigurationDtos() {}
    public record Create(DraftChange draft, String legacyModelId) {
        @Override public String toString() { return "Create[REDACTED]"; }
    }
    public record ModelInput(String modelRef, String modelCode, boolean jsonObject) {
        public ModelInput(String modelRef,String modelCode) { this(modelRef,modelCode,false); }
    }
    public record KeyInput(String keyRef, String label, String mode, String envName,
                           @JsonProperty(access=JsonProperty.Access.WRITE_ONLY) String apiKey) {
        @Override public String toString() { return "KeyInput[REDACTED]"; }
    }
    public record DraftChange(int revision, String name, String environment, String providerCode,
                              String endpointUrl, String protocol, int timeoutSeconds,
                              List<ModelInput> models, List<KeyInput> keys) {
        @Override public String toString() { return "DraftChange[REDACTED]"; }
    }
    public record ModelSpec(String modelRef, String modelCode, boolean jsonObject, String capabilityEvidence) {
        public ModelSpec(String modelRef,String modelCode) { this(modelRef,modelCode,false,null); }
    }
    public record KeySpec(String keyRef, String keyVersion, String label, String mode, String envName) {}
    public record Snapshot(String configVersion, String providerCode, String endpointUrl, String protocol,
                           int timeoutSeconds, List<ModelSpec> models, List<KeySpec> keys) {}
    public record Pair(String modelRef, String keyRef, String keyVersion) {}
    record AdmittedPair(String modelRef, String keyRef, String keyVersion, String proofHash) {
        Pair safe() { return new Pair(modelRef,keyRef,keyVersion); }
    }
    public record Version(String configVersion, Snapshot snapshot, List<Pair> admittedPairs,
                          LocalDateTime createdAt, boolean activated) {}
    public record View(String id, String name, String environment, String providerCode, String status,
                       int revision, String activeVersion, Snapshot draft, List<Pair> activePairs,
                       String latestTestId, LocalDateTime updatedAt, String legacyModelId) {}
    public record Row(String id, String name, String environment, String providerCode, String status,
                      int revision, int modelCount, int usableKeyCount, LocalDateTime latestTestTime) {}
    public record LegacyModel(String id, String providerCode, String modelCode) {}
    public record Page(List<Row> records, long total, int pageNum, int pageSize, List<LegacyModel> legacyModels, List<String> jsonObjectModels) {}
    public record Activate(int revision, String configVersion, boolean onlyAvailable) {}
    public record Revision(int revision) {}
    public record TestStart(int revision, String configVersion, boolean confirmedCosts) {}
    public record TestPair(String modelRef, String modelCode, String keyRef, String keyVersion,
                           String keyLabel, String status, String errorCode, Long elapsedMs) {}
    public record TestRun(String id, String configId, String configVersion, String status,
                          int total, int finished, List<TestPair> pairs) {}
}
