package com.xinghuiTec.agent.runtime;

import com.xinghuiTec.agent.api.AgentCall;
import com.xinghuiTec.agent.api.AgentModelGateway;
import io.agentscope.core.message.Msg;
import io.agentscope.core.model.ChatResponse;
import io.agentscope.core.model.GenerateOptions;
import io.agentscope.core.model.Model;
import io.agentscope.core.model.ToolSchema;
import io.agentscope.core.util.JsonUtils;
import io.agentscope.extensions.model.openai.dto.OpenAIResponse;
import io.agentscope.extensions.model.openai.dto.OpenAIStreamOptions;
import io.agentscope.extensions.model.openai.formatter.OpenAIChatFormatter;
import reactor.core.publisher.Flux;

import java.time.Instant;
import java.util.List;
import java.util.Objects;
import java.util.UUID;

/** Uses the vendor formatter for mixed assistant/tool history, including fragmented tool calls. */
final class GatewayModel implements Model {
    private final AgentModelGateway gateway;
    private final AgentCall run;
    GatewayModel(AgentModelGateway gateway, AgentCall run) { this.gateway = gateway; this.run = run; }
    @Override public String getModelName() { return "configured-agent-model"; }
    @Override public Flux<ChatResponse> stream(List<Msg> messages, List<ToolSchema> tools, GenerateOptions options) {
        return Flux.defer(() -> {
            run.check();
            var formatter = new OpenAIChatFormatter();
            var request = formatter.buildRequest(getModelName(), formatter.format(messages), true);
            request.setStreamOptions(new OpenAIStreamOptions(true));
            formatter.applyOptions(request, options, null);
            formatter.applyTools(request, tools);
            if (options != null && options.getToolChoice() != null)
                formatter.applyToolChoice(request, options.getToolChoice());
            var call = new AgentCall(run.actor(), run.sessionId(), run.runId(), UUID.randomUUID().toString(),
                    run.deadline(), run.cancelled());
            Instant started = Instant.now();
            return RunCancellation.guard(gateway.stream(call, JsonUtils.getJsonCodec().toJson(request)), call)
                    .doOnNext(ignored -> call.check())
                    .map(json -> formatter.parseResponse(JsonUtils.getJsonCodec().fromJson(json, OpenAIResponse.class), started))
                    .filter(Objects::nonNull);
        });
    }
}
