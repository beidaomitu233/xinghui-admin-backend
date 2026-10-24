package com.xinghuiTec.agent.runtime;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.xinghuiTec.agent.api.*;
import io.agentscope.core.agent.RuntimeContext;
import io.agentscope.core.event.*;
import io.agentscope.core.model.GenerateOptions;
import io.agentscope.core.state.AgentStateStore;
import io.agentscope.harness.agent.HarnessAgent;
import reactor.core.publisher.Flux;

import java.nio.file.Path;
import java.util.Objects;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * One official Harness/ReAct invocation. The application supplies a persisted state store,
 * authorized resources and a configured model bridge. No business intent routing lives here.
 */
public final class AgentRuntime implements AutoCloseable {
    private final HarnessAgent agent;
    private final RuntimeContext context;
    private final AgentCall call;
    private final AtomicBoolean stopped = new AtomicBoolean();

    public AgentRuntime(AgentCall call, String systemPrompt, Path workspace, AgentStateStore stateStore,
                        AgentModelGateway models, AgentResourceGateway resources, ObjectMapper json) {
        Objects.requireNonNull(call);
        this.call = new AgentCall(call.actor(), call.sessionId(), call.runId(), call.callId(), call.deadline(),
                () -> stopped.get() || call.cancelled().getAsBoolean());
        this.context = RuntimeContext.builder().userId(call.actor().stateUserId()).sessionId(call.sessionId()).build();
        this.agent = HarnessAgent.builder()
                .name("AI工作台").agentId("business-agent")
                .sysPrompt(Objects.requireNonNull(systemPrompt))
                .model(new GatewayModel(models, this.call))
                .toolkit(BusinessTools.create(this.call, resources, json))
                .stateStore(Objects.requireNonNull(stateStore))
                .workspace(Objects.requireNonNull(workspace))
                .maxIters(40).maxRetries(1) // SDK counts the initial model attempt in this budget.
                .generateOptions(GenerateOptions.builder().stream(true).maxTokens(8192).parallelToolCalls(false).build())
                .enableTaskList(true)
                // This application's tools operate on business services. Host files, shells and
                // unreviewed workspace plugins are not business capabilities.
                .disableFilesystemTools().disableShellTool().disableMemoryTools().disableMemoryHooks()
                .disableTranscript().disableWorkspaceContext().disableAtPathExpansion()
                .disableDynamicSkills().disableDefaultWorkspaceSkills().disableToolsConfig()
                .disableDynamicSubagents().disableSubagents()
                .disableToolResultEviction().enableAgentTracingLog(false)
                .build();
        // Harness 2.0.3 installs web tools even with workspace tools disabled. This app's
        // business gateway owns outbound access; remove implicit vendor tools at the registry.
        var businessTools = Set.of("resources_list", "resource_describe", "records_query", "records_mutate", "todo_write");
        Set.copyOf(agent.getToolkit().getToolNames()).stream().filter(name -> !businessTools.contains(name))
                .forEach(agent.getToolkit()::removeTool);
    }
    public Flux<AgentEventView> run(String input) {
        if (input == null || input.isBlank() || input.length() > 32768)
            return Flux.error(new AgentProblem("INVALID_MESSAGE", "请输入内容，最多32768个字符"));
        return Flux.defer(() -> {
            call.check();
            return RunCancellation.guard(agent.streamEvents(input, context), call)
                    .concatMapIterable(this::publicEvents);
        });
    }
    private List<AgentEventView> publicEvents(AgentEvent event) {
        if (event instanceof TextBlockDeltaEvent text)
            return List.of(new AgentEventView("TEXT_DELTA", Map.of("delta", text.getDelta(), "replyId", text.getReplyId())));
        if (event instanceof ModelCallStartEvent)
            return List.of(new AgentEventView("PHASE", Map.of("phase", "MODEL")));
        if (event instanceof ModelCallEndEvent model && model.getUsage() != null) {
            var usage = model.getUsage();
            return List.of(new AgentEventView("USAGE", Map.of("inputTokens", usage.getInputTokens(),
                    "outputTokens", usage.getOutputTokens(), "cachedTokens", usage.getCachedTokens(), "seconds", usage.getTime())));
        }
        if (event instanceof ToolCallStartEvent tool)
            return List.of(new AgentEventView("TOOL_START", Map.of("callId", tool.getToolCallId(), "name", tool.getToolCallName())));
        if (event instanceof ToolResultEndEvent tool) {
            var result = new AgentEventView("TOOL_END", Map.of("callId", tool.getToolCallId(),
                    "name", tool.getToolCallName(), "state", tool.getState().name()));
            if (!"todo_write".equals(tool.getToolCallName())) return List.of(result);
            var tasks = agent.getDelegate().getAgentState(context).getTasksContext().getTasks().stream()
                    .map(task -> Map.of("id", task.getId(), "title", task.getSubject(), "status", task.getState().name())).toList();
            return List.of(result, new AgentEventView("PLAN", Map.of("steps", tasks)));
        }
        if (event instanceof ExceedMaxItersEvent)
            return List.of(new AgentEventView("LIMIT_REACHED", Map.of("message", "本轮执行次数已用完，可查看进度后继续任务")));
        if (event instanceof AgentResultEvent result)
            return List.of(new AgentEventView("ASSISTANT_DONE", Map.of("replyId", result.getResult().getId())));
        return List.of();
    }
    public void cancel() { stopped.set(true); }
    @Override public void close() { stopped.set(true); agent.close(); }
}
