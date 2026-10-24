package com.xinghuiTec.agent.api;

/** Resolve an enabled Agent scene from the existing prompt management and model configuration. */
public interface AgentPromptGateway {
    record Preset(String systemPrompt, AgentModelGateway models, java.util.List<String> contextMessages) {
        public Preset(String systemPrompt,AgentModelGateway models){this(systemPrompt,models,java.util.List.of());}
        public Preset { contextMessages=java.util.List.copyOf(contextMessages); }
    }
    Preset resolve(AgentActor actor, String sceneKey);
    default Preset resolve(AgentActor actor,String sceneKey,String task){return resolve(actor,sceneKey);}
}
