package com.xinghuiTec.agent.api;

import com.fasterxml.jackson.databind.JsonNode;

/**
 * Generic resource access, implemented by business adapters. Every method must recheck the
 * current user's permissions, tenant, resource schema and record scope. Mutations and their
 * idempotency receipt must commit in one transaction. No SQL or secret-bearing config is exposed.
 */
public interface AgentResourceGateway {
    JsonNode resources(AgentCall call);
    JsonNode describe(AgentCall call, String resource);
    JsonNode query(AgentCall call, String resource, JsonNode query);
    JsonNode mutate(AgentCall call, String toolCallId, String resource, JsonNode mutation);
}
