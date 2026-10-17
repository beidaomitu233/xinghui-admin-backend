package com.xinghuiTec.ai.config;

import java.time.Instant;
import java.util.List;
import java.util.Map;
import com.fasterxml.jackson.annotation.JsonProperty;

/** Discovery metadata only; no credential material or inference evidence. */
public final class AiModelCatalogDtos {
    private AiModelCatalogDtos() {}

    public record Scan(String configId, String keyId, int expectedRevision,
                       String search, int pageNum, int pageSize) {}

    public record DraftScan(String provider,
                            @JsonProperty(access=JsonProperty.Access.WRITE_ONLY) String apiKey,
                            String search, int pageNum, int pageSize) {
        @Override public String toString() { return "DraftScan[REDACTED]"; }
    }

    public record Model(String code, String name, Long contextLength, Long maxCompletionTokens,
                        List<String> inputModalities, List<String> outputModalities,
                        List<String> supportedParameters) {}

    public record Page(List<Model> records, int total, int pageNum, int pageSize,
                       Instant refreshedAt, String source, String provider, String configId, String keyId,
                       Integer revision, boolean generationVerified, int scannedCount,
                       int filteredCount, Map<String, Integer> filterReasons) {
        public Page(List<Model> records, int total, int pageNum, int pageSize, Instant refreshedAt,
                    String source, String provider, String configId, String keyId,
                    Integer revision, boolean generationVerified) {
            this(records, total, pageNum, pageSize, refreshedAt, source, provider, configId, keyId,
                    revision, generationVerified, total, 0, Map.of());
        }
    }
}
