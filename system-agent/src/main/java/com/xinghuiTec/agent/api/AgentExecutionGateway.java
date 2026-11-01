package com.xinghuiTec.agent.api;

import io.agentscope.core.state.AgentStateStore;
import reactor.core.publisher.Flux;
import java.util.List;

/** Application binding for the official SDK; contains no reasoning loop or custom worker protocol. */
public interface AgentExecutionGateway {
    Flux<AgentEventView> execute(AgentCall call, AgentPromptGateway.Preset preset, String input,
            List<String> images, String mode, AgentStateStore state, AgentResourceGateway resources);
}
