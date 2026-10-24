package com.xinghuiTec.agent.api;

/** Resolve an enabled Agent scene from the existing prompt management and model configuration. */
public interface AgentPromptGateway {
    record Preset(String systemPrompt, AgentModelGateway models) {}
    Preset resolve(AgentActor actor, String sceneKey);
}
