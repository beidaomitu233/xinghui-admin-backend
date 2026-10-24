package com.xinghuiTec.agent.api;

import java.util.Map;

/** Public event contract. Provider reasoning, hidden prompts and raw SDK state are never included. */
public record AgentEventView(String type, Map<String, Object> data) {
    public AgentEventView { data = Map.copyOf(data); }
}
