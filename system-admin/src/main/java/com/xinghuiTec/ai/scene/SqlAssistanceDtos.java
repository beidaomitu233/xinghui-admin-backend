package com.xinghuiTec.ai.scene;

import com.xinghuiTec.ai.contract.AiSceneQueryRuntime;
import java.time.Instant;
import java.util.List;

/** Public SQL assistance values. No system message, actor, owner, tenant or raw provider output. */
public final class SqlAssistanceDtos {
    private SqlAssistanceDtos() {}
    public enum Status { QUEUED,RUNNING,VALIDATING,SUCCEEDED,FAILED,CANCELLED,TIMED_OUT,INCOMPLETE }
    public record Start(String sceneKey,int expectedSceneRevision,String description,List<String> datasetKeys,
                        SceneDtos.BusinessRef businessRef,SceneDtos.ModelRef initialModel,String expectedSchemaFingerprint,
                        boolean confirmedCosts,String clientMutationId,int maxRows) {
        public Start {datasetKeys=datasetKeys==null?List.of():List.copyOf(datasetKeys);if(maxRows==0)maxRows=20;}
        @Override public String toString(){return "SqlAssistanceStart[REDACTED]";}
    }
    public record Accepted(String requestId,Status status,String scopeDescription,String schemaFingerprint) {}
    public record View(String requestId,Status status,AiSceneQueryRuntime.SqlQuery query,String scopeDescription,
                       String schemaFingerprint,List<AiSceneQueryRuntime.QueryDiagnostic> diagnostics,String errorCode,Instant deadline) {
        public View {diagnostics=List.copyOf(diagnostics);}
        @Override public String toString(){return "SqlAssistanceView[REDACTED]";}
    }
    public record Cancel() {}
}
