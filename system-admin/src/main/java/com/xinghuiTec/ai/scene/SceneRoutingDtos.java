package com.xinghuiTec.ai.scene;

import java.time.Instant;

/** Compatibility route endpoint; current prompt editing manages the ordered model cards. */
public final class SceneRoutingDtos {
    private SceneRoutingDtos() {}
    public record View(String sceneKey,int revision,String currentVersionId,SceneDtos.ModelPolicy modelPolicy,String businessModel,java.util.List<String> businessModels) {
        public View(String sceneKey,int revision,String currentVersionId,SceneDtos.ModelPolicy modelPolicy,String businessModel){this(sceneKey,revision,currentVersionId,modelPolicy,businessModel,java.util.List.of());}
        public View(String sceneKey,int revision,String currentVersionId,SceneDtos.ModelPolicy modelPolicy){this(sceneKey,revision,currentVersionId,modelPolicy,null);}
    }
    public record Change(int expectedRevision,String clientMutationId,SceneDtos.ModelPolicy modelPolicy,String businessModel,java.util.List<String> businessModels) {
        public Change(int expectedRevision,String clientMutationId,SceneDtos.ModelPolicy modelPolicy,String businessModel){this(expectedRevision,clientMutationId,modelPolicy,businessModel,java.util.List.of());}
        public Change { businessModels=businessModels==null?java.util.List.of():java.util.List.copyOf(businessModels); }
        public Change(int expectedRevision,String clientMutationId,SceneDtos.ModelPolicy modelPolicy){this(expectedRevision,clientMutationId,modelPolicy,null);}
        @Override public String toString(){return "SceneRoutingChange[REDACTED]";}
    }
    public record Saved(View routing,String clientMutationId,Instant effectiveFrom) {}
}
