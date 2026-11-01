package com.xinghuiTec.agent.runtime;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.xinghuiTec.agent.api.*;
import io.agentscope.core.agent.RuntimeContext;
import io.agentscope.core.event.*;
import io.agentscope.core.model.GenerateOptions;
import io.agentscope.core.permission.*;
import io.agentscope.core.tool.AgentTool;
import io.agentscope.core.tool.ToolCallParam;
import io.agentscope.core.message.ToolResultBlock;
import io.agentscope.core.state.AgentStateStore;
import io.agentscope.harness.agent.HarnessAgent;
import io.agentscope.harness.agent.DistributedStore;
import io.agentscope.harness.agent.IsolationScope;
import io.agentscope.harness.agent.filesystem.spec.RemoteFilesystemSpec;
import io.agentscope.harness.agent.memory.compaction.CompactionConfig;
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
public final class AgentScopeBinding implements AutoCloseable {
    private final HarnessAgent agent;
    private final RuntimeContext context;
    private final AgentCall call;
    private final String mode;
    private final AtomicBoolean stopped = new AtomicBoolean();

    public AgentScopeBinding(AgentCall call, String systemPrompt, Path workspace, AgentStateStore stateStore,
                        AgentModelGateway models, AgentResourceGateway resources, ObjectMapper json) {
        this(call,systemPrompt,workspace,stateStore,models,resources,json,false,"EXECUTE",null);
    }
    public AgentScopeBinding(AgentCall call, String systemPrompt, Path workspace, AgentStateStore stateStore,
                        AgentModelGateway models, AgentResourceGateway resources, ObjectMapper json, boolean hub, String mode, DistributedStore distributed) {
        Objects.requireNonNull(call);
        this.mode=mode;
        this.call = new AgentCall(call.actor(), call.sessionId(), call.runId(), call.callId(), call.deadline(),
                () -> stopped.get() || call.cancelled().getAsBoolean());
        this.context = RuntimeContext.builder().userId(call.actor().stateUserId()).sessionId(call.sessionId()).build();
        var catalog=hub?resources.resources(this.call):null;
        var toolkit=hub?SkillBusinessTools.create(this.call,resources,json,catalog):BusinessTools.create(this.call,resources,json);
        if(hub) {
            // Harness's plan middleware captures its pre-ReAct toolkit copy. Seed metadata
            // there too; ReAct re-registers the native meta tool against its final tool groups.
            toolkit.registerMetaTool();
            markMetaToolReadOnly(toolkit);
        }
        var builder = HarnessAgent.builder()
                .name("AI工作台").agentId("business-agent")
                .sysPrompt(Objects.requireNonNull(systemPrompt)+(hub?"\n系统提供的当前账号业务能力目录："+catalog+"\n仅能使用目录内当前授权的业务能力。操作和字段以实时describe为准；写入必须经过具体预览与原用户确认，以实际回执核实结果。未开放的接口、Shell、任意SQL、主机文件、已有密钥读取均不可执行。不要承诺或虚构目录之外的能力；遇到未接入业务须说明边界。": ""))
                .model(new GatewayModel(models, this.call))
                .toolkit(toolkit)
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
                // Preserve native context summarization. Raw transcript offload and memory
                // ledger flush would bypass the application's encrypted state store.
                .compaction(CompactionConfig.builder().flushBeforeCompact(false).offloadBeforeCompact(false).build());
        if(hub) {
            // Official shared-KV mode never creates a shell-capable filesystem. Harness owns execution and state recovery.
            builder.distributedStore(Objects.requireNonNull(distributed,"Official distributed store required"))
                    .filesystem(new RemoteFilesystemSpec().isolationScope(IsolationScope.SESSION));
            try { builder.skillRepository(new io.agentscope.core.skill.repository.ClasspathSkillRepository("hub-skills")); }
            catch(java.io.IOException failed){ throw new IllegalStateException("Skills unavailable",failed); }
            builder.enablePlanMode().enableMetaTool(true);
        }
        this.agent=builder.build();
        // Harness 2.0.3 installs web tools even with workspace tools disabled. This app's
        // business gateway owns outbound access; remove implicit vendor tools at the registry.
        var businessTools = new java.util.HashSet<>(toolkit.getToolNames());
        businessTools.add("todo_write");
        if(hub)businessTools.addAll(Set.of("load_skill_through_path","reset_equipped_tools","plan_enter","plan_write","plan_exit"));
        Set.copyOf(agent.getToolkit().getToolNames()).stream().filter(name -> !businessTools.contains(name))
                .forEach(agent.getToolkit()::removeTool);
        if(hub) {
            // Native meta-tool activation changes only equipped schemas, never business data.
            // SDK 2.0.3 omits its read-only metadata, which otherwise blocks discovery in PLAN.
            markMetaToolReadOnly(agent.getToolkit());
            var permissions=PermissionContextState.builder().mode(PermissionMode.DONT_ASK);
            for(String name:List.of("plan_enter","plan_write","todo_write"))
                permissions.addAllowRule(name,new PermissionRule(name,null,PermissionBehavior.ALLOW,"application"));
            if("EXECUTE".equals(mode))permissions.addAllowRule("plan_exit",
                    new PermissionRule("plan_exit",null,PermissionBehavior.ALLOW,"userSelectedExecute"));
            else permissions.addAskRule("plan_exit",new PermissionRule("plan_exit",null,PermissionBehavior.ASK,"userMustSelectExecute"));
            agent.getDelegate().replacePermissionContext(context.getUserId(),context.getSessionId(),permissions.build());
            // Explicit ASK uses the SDK's persisted permission pause, not a DENIED retry loop.
            // Only a subsequent authenticated EXECUTE turn confirms that exact native request.
            if("PLAN".equals(mode))agent.enterPlanMode(context);else agent.exitPlanMode(context);
        }
    }
    private static void markMetaToolReadOnly(io.agentscope.core.tool.Toolkit toolkit) {
        AgentTool meta=Objects.requireNonNull(toolkit.getTool("reset_equipped_tools"));
        toolkit.removeTool(meta.getName());
        toolkit.registerAgentTool(new ReadOnlyMetaTool(meta));
    }
    private record ReadOnlyMetaTool(AgentTool nativeTool) implements AgentTool {
        public String getName(){return nativeTool.getName();}
        public String getDescription(){return nativeTool.getDescription();}
        public Map<String,Object> getParameters(){return nativeTool.getParameters();}
        public Boolean getStrict(){return nativeTool.getStrict();}
        public Map<String,Object> getOutputSchema(){return nativeTool.getOutputSchema();}
        public boolean isReadOnly(){return true;}
        public reactor.core.publisher.Mono<ToolResultBlock> callAsync(ToolCallParam param){return nativeTool.callAsync(param);}
    }
    public Flux<AgentEventView> run(String input) {
        return run(input,List.of());
    }
    public Flux<AgentEventView> run(String input,List<String> contextMessages) {
        return run(input,contextMessages,List.of());
    }
    public Flux<AgentEventView> run(String input,List<String> contextMessages,List<String> images) {
        if (input == null || input.isBlank() && images.isEmpty() || input.length() > 32768)
            return Flux.error(new AgentProblem("INVALID_MESSAGE", "请输入内容，最多32768个字符"));
        return Flux.defer(() -> {
            call.check();
            var messages=new java.util.ArrayList<io.agentscope.core.message.Msg>();
            contextMessages.forEach(text->messages.add(new io.agentscope.core.message.UserMessage(text)));
            var content=new java.util.ArrayList<io.agentscope.core.message.ContentBlock>();
            if(!input.isBlank())content.add(io.agentscope.core.message.TextBlock.builder().text(input).build());
            images.forEach(url->content.add(new io.agentscope.core.message.ImageBlock(new io.agentscope.core.message.URLSource(url))));
            var message=io.agentscope.core.message.UserMessage.builder().content(content);
            var history=agent.getDelegate().getAgentState(context).getContext();
            for(int i=history.size()-1;i>=0;i--){
                var prior=history.get(i);if(prior.getRole()!=io.agentscope.core.message.MsgRole.ASSISTANT)continue;
                var asking=prior.getContentBlocks(io.agentscope.core.message.ToolUseBlock.class).stream()
                    .filter(tool->tool.getState()==io.agentscope.core.message.ToolCallState.ASKING).toList();
                if(!asking.isEmpty()){
                    if(asking.stream().anyMatch(tool->!"plan_exit".equals(tool.getName())))throw new AgentProblem("INVALID_CONFIRMATION","待确认工具不是中台计划请求");
                    message.metadata(Map.of(io.agentscope.core.message.Msg.METADATA_CONFIRM_RESULTS,
                        asking.stream().map(tool->new ConfirmResult("EXECUTE".equals(mode),tool)).toList()));
                }break;
            }
            messages.add(message.build());
            return RunCancellation.guard(agent.streamEvents(messages, context), call)
                    .concatMapIterable(this::publicEvents);
        });
    }
    private List<AgentEventView> publicEvents(AgentEvent event) {
        if (event instanceof TextBlockDeltaEvent text)
            return List.of(new AgentEventView("TEXT_DELTA", Map.of("delta", text.getDelta(), "replyId", text.getReplyId())));
        if (event instanceof ModelCallStartEvent)
            return List.of(new AgentEventView("PHASE", Map.of("phase", "MODEL")));
        if (event instanceof RequireUserConfirmEvent confirm) {
            if(confirm.getToolCalls().stream().anyMatch(tool->!"plan_exit".equals(tool.getName())))
                throw new AgentProblem("INVALID_CONFIRMATION","待确认工具不是中台计划请求");
            return List.of(new AgentEventView("PLAN_READY",Map.of("awaitingExecution",true)));
        }
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
            if (hubPlan(tool)) {
                // The explicit plan document is public task output, never the model's private reasoning.
                var plan=agent.getWorkspaceManager().readManagedWorkspaceFileUtf8(context,"plans/PLAN.md");
                return List.of(result,new AgentEventView("PLAN_DOCUMENT",Map.of("markdown",plan.length()>32768?plan.substring(0,32768):plan)));
            }
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
    private boolean hubPlan(ToolResultEndEvent event){
        return "plan_write".equals(event.getToolCallName()) && event.getState()==io.agentscope.core.message.ToolResultState.SUCCESS;
    }
    public void cancel() { stopped.set(true); }
    public HarnessAgent nativeAgent(){return agent;}
    @Override public void close() { stopped.set(true); agent.close(); }
}
