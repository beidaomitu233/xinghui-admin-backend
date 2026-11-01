package com.xinghuiTec.agent.api;

import reactor.core.publisher.Flux;

/**
 * Application-owned routing, credentials, endpoint policy, retries and detailed call archive.
 * The request/response are OpenAI-compatible JSON; credentials never enter this module.
 * Each response item is one validated SSE data object (without the data prefix or DONE marker).
 */
public interface AgentModelGateway {
    Flux<String> stream(AgentCall call, String requestJson);
}
