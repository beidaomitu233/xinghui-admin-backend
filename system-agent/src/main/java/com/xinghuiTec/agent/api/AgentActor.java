package com.xinghuiTec.agent.api;

/** Created by the authenticated application adapter, never by the model or request body. */
public record AgentActor(String tenantId, String userId) {
    public AgentActor {
        if (tenantId == null || tenantId.isBlank() || tenantId.length() > 64
                || userId == null || userId.isBlank() || userId.length() > 64)
            throw new IllegalArgumentException("Invalid authenticated agent owner");
    }
    public String stateUserId() { return tenantId.length() + ":" + tenantId + userId; }
}
