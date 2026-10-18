package com.xinghuiTec.ai.scene;

import java.time.Instant;

/** Route maintenance belongs to the independent AI settings page, not prompt body saves. */
public final class SceneRoutingDtos {
    private SceneRoutingDtos() {}
    public record View(String sceneKey,int revision,String currentVersionId,SceneDtos.ModelPolicy modelPolicy) {}
    public record Change(int expectedRevision,String clientMutationId,SceneDtos.ModelPolicy modelPolicy) {
        @Override public String toString(){return "SceneRoutingChange[REDACTED]";}
    }
    public record Saved(View routing,String clientMutationId,Instant effectiveFrom) {}
}
