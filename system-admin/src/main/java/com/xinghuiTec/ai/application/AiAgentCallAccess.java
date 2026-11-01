package com.xinghuiTec.ai.application;

import com.xinghuiTec.ai.contract.AiTypes.TrustedActor;

/** Current Agent owner and resource-history permission, without coupling the common log service to Agent tables. */
public interface AiAgentCallAccess {
    boolean canRead(TrustedActor actor,String sessionId,String runId);
}
