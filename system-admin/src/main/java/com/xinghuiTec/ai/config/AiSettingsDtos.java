package com.xinghuiTec.ai.config;

import com.fasterxml.jackson.annotation.JsonProperty;
import java.time.Instant;
import java.util.List;

/** Usable settings input: no credential source, protocol, key refs, pair IDs or version selectors. */
public final class AiSettingsDtos {
    private AiSettingsDtos() {}
    public record Change(Integer expectedRevision,String name,String provider,String endpointUrl,
                         @JsonProperty(access=JsonProperty.Access.WRITE_ONLY) String apiKey,List<String> modelCodes) {
        public Change {modelCodes=modelCodes==null?List.of():List.copyOf(modelCodes);}
        @Override public String toString(){return "AiSettingsChange[REDACTED]";}
    }
    public record Model(String code,String name) {}
    public record Provider(String code,String name,String defaultEndpoint,List<Model> suggestedModels) {
        public Provider {suggestedModels=List.copyOf(suggestedModels);}
    }
    public enum ConnectionStatus { NOT_TESTED, QUEUED, RUNNING, SUCCEEDED, PARTIAL, FAILED, CANCELLED, INTERRUPTED }
    public record Connection(String testId,ConnectionStatus status,String message,boolean active,Instant checkedAt) {}
    public record View(String configId,int revision,String name,String provider,String endpointUrl,
                       boolean keyConfigured,String maskedKey,List<Model> models,boolean active,Connection connection,boolean pendingChanges) {
        public View {models=List.copyOf(models);}
    }
    public record Page(List<View> records,String total,int pageNum,int pageSize) {
        public Page {records=List.copyOf(records);}
    }
    public record Test(boolean confirmedCosts,boolean activateOnSuccess) {}
    public record ModelResult(String code,ConnectionStatus status,String message) {}
    public record TestRun(String testId,String configId,ConnectionStatus status,String message,boolean active,List<ModelResult> models) {
        public TestRun {models=List.copyOf(models);}
    }
}
