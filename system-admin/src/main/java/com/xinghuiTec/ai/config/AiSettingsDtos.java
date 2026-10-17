package com.xinghuiTec.ai.config;

import com.fasterxml.jackson.annotation.JsonProperty;
import java.time.Instant;
import java.util.List;

/** Usable settings input: no credential source, protocol, key refs, pair IDs or version selectors. */
public final class AiSettingsDtos {
    private AiSettingsDtos() {}
    public record Change(Integer expectedRevision,String name,String provider,String endpointUrl,
                         @JsonProperty(access=JsonProperty.Access.WRITE_ONLY) String apiKey,List<String> modelCodes,List<KeyChange> keys) {
        public Change(Integer expectedRevision,String name,String provider,String endpointUrl,String apiKey,List<String> modelCodes) {this(expectedRevision,name,provider,endpointUrl,apiKey,modelCodes,null);}
        public Change {modelCodes=modelCodes==null?List.of():List.copyOf(modelCodes);keys=keys==null?null:List.copyOf(keys);}
        @Override public String toString(){return "AiSettingsChange[REDACTED]";}
    }
    public record KeyChange(String keyId,String name,@JsonProperty(access=JsonProperty.Access.WRITE_ONLY) String apiKey,List<String> modelCodes) {
        public KeyChange {modelCodes=modelCodes==null?null:List.copyOf(modelCodes);}
        @Override public String toString(){return "KeyChange[REDACTED]";}
    }
    public record KeyView(String keyId,String name,boolean keyConfigured,String maskedKey,List<String> modelCodes,
                          ConnectionStatus status,String message,List<ModelResult> models) {
        public KeyView {modelCodes=List.copyOf(modelCodes);models=List.copyOf(models);}
    }
    public record KeyResult(String keyId,String name,ConnectionStatus status,String message,List<ModelResult> models) {
        public KeyResult {models=List.copyOf(models);}
    }
    public record Model(String code,String name) {}
    public record Provider(String code,String name,String defaultEndpoint,List<Model> suggestedModels) {
        public Provider {suggestedModels=List.copyOf(suggestedModels);}
    }
    public enum ConnectionStatus { NOT_TESTED, QUEUED, RUNNING, SUCCEEDED, PARTIAL, FAILED, CANCELLED, INTERRUPTED }
    public record Connection(String testId,ConnectionStatus status,String message,boolean active,Instant checkedAt) {}
    public record View(String configId,int revision,String name,String provider,String endpointUrl,
                       boolean keyConfigured,String maskedKey,List<Model> models,boolean active,Connection connection,boolean pendingChanges,List<KeyView> keys) {
        public View(String configId,int revision,String name,String provider,String endpointUrl,boolean keyConfigured,String maskedKey,List<Model> models,boolean active,Connection connection,boolean pendingChanges){this(configId,revision,name,provider,endpointUrl,keyConfigured,maskedKey,models,active,connection,pendingChanges,List.of());}
        public View {models=List.copyOf(models);keys=List.copyOf(keys);}
    }
    public record Page(List<View> records,String total,int pageNum,int pageSize) {
        public Page {records=List.copyOf(records);}
    }
    public record Test(boolean confirmedCosts,boolean activateOnSuccess) {}
    public record ModelResult(String code,ConnectionStatus status,String message) {}
    public record TestRun(String testId,String configId,ConnectionStatus status,String message,boolean active,List<ModelResult> models,List<KeyResult> keys) {
        public TestRun(String testId,String configId,ConnectionStatus status,String message,boolean active,List<ModelResult> models){this(testId,configId,status,message,active,models,List.of());}
        public TestRun {models=List.copyOf(models);keys=List.copyOf(keys);}
    }
}
