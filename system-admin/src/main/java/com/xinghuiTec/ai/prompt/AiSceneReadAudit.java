package com.xinghuiTec.ai.prompt;

import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

/** Metadata-only sink, intentionally separate from generic operation-log persistence. */
@Component
public class AiSceneReadAudit {
    public record Event(String traceId,Long userId,String tenantId,String appKey,String sceneKey,
                        Integer publishedRevision,String outcome,int httpStatus) {}
    public void record(Event event) {
        LoggerFactory.getLogger(AiSceneReadAudit.class).info(
                "ai_scene_read trace={} user={} tenant={} app={} scene={} revision={} outcome={} http={}",
                event.traceId(),event.userId(),event.tenantId(),event.appKey(),event.sceneKey(),
                event.publishedRevision(),event.outcome(),event.httpStatus());
    }
}
