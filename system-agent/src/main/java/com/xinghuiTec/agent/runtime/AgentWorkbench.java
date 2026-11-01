package com.xinghuiTec.agent.runtime;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.xinghuiTec.agent.api.*;
import com.xinghuiTec.agent.persistence.JdbcAgentRepository;
import com.xinghuiTec.agent.persistence.JdbcAgentStateStore;
import com.xinghuiTec.agent.persistence.JdbcAgentImages;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.transaction.PlatformTransactionManager;

import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.time.Duration;
import java.util.HexFormat;
import java.util.Map;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicBoolean;

/** Bounded background execution. Reading/reconnecting to history never submits an invocation. */
public final class AgentWorkbench implements AutoCloseable {
    private final JdbcAgentRepository repository;
    private final AgentPromptGateway prompts;
    private final AgentResourceGateway resources;
    private final JdbcTemplate jdbc;
    private final PlatformTransactionManager transactions;
    private final AgentPayloadCodec codec;
    private final ObjectMapper json;
    private final Path workspaces;
    private JdbcAgentImages images;
    private AgentExecutionGateway execution;
    public void execution(AgentExecutionGateway execution){this.execution=execution;}
    private final ThreadPoolExecutor executor = new ThreadPoolExecutor(1, 1, 0, TimeUnit.SECONDS,
            new ArrayBlockingQueue<>(8), task -> { var thread = new Thread(task, "agent-workbench"); thread.setDaemon(true); return thread; },
            new ThreadPoolExecutor.AbortPolicy());

    public AgentWorkbench(JdbcAgentRepository repository, AgentPromptGateway prompts, AgentResourceGateway resources,
                           JdbcTemplate jdbc, PlatformTransactionManager transactions, AgentPayloadCodec codec,
                           ObjectMapper json, Path workspaces) {
        this.repository = repository; this.prompts = prompts; this.resources = resources;
        this.jdbc = jdbc; this.transactions = transactions; this.codec = codec; this.json = json;
        this.workspaces = workspaces.toAbsolutePath().normalize();
    }
    public void images(JdbcAgentImages images){this.images=images;}
    public JdbcAgentRepository.Admission submit(AgentActor actor, String sessionId, String requestId, String input, String sceneKey) {
        return submit(actor,sessionId,requestId,input,sceneKey,java.util.List.of());
    }
    public JdbcAgentRepository.Admission submit(AgentActor actor, String sessionId, String requestId, String input, String sceneKey,java.util.List<String> imageIds) {
        return submit(actor,sessionId,requestId,input,sceneKey,imageIds,"EXECUTE");
    }
    public JdbcAgentRepository.Admission submit(AgentActor actor, String sessionId, String requestId, String input, String sceneKey,java.util.List<String> imageIds,String mode) {
        var attached = imageIds==null||imageIds.isEmpty()?java.util.List.<JdbcAgentImages.Image>of():images.require(actor,sessionId,imageIds);
        var admission = repository.admit(actor, sessionId, requestId, input, sceneKey, Duration.ofMinutes(5),attached,mode);
        if (!admission.created()) return admission;
        try { executor.execute(() -> execute(actor, admission.run().id())); }
        catch (RejectedExecutionException full) { repository.finish(actor, admission.run().id(), "FAILED", "QUEUE_FULL"); }
        return new JdbcAgentRepository.Admission(repository.run(actor, admission.run().id()), true);
    }
    public void cancel(AgentActor actor, String runId) {
        repository.cancel(actor, runId);
    }
    private void execute(AgentActor actor, String runId) {
        try {
            var run = repository.claim(actor, runId);
            var preset = prompts.resolve(actor, run.promptSceneKey(),run.input());
            var call = new AgentCall(actor, run.sessionId(), run.id(), "run", run.deadline(), () -> !repository.running(actor, run.id()));
            var state = new JdbcAgentStateStore(jdbc, transactions, actor, run.sessionId(), codec,
                    () -> repository.guard(actor, run.id(), true));
            AgentModelGateway authorizedModels = (modelCall, request) -> reactor.core.publisher.Flux.defer(() -> {
                repository.session(modelCall.actor(), modelCall.sessionId());
                modelCall.check();
                return preset.models().stream(modelCall, request);
            });
            if(execution==null)throw new AgentProblem("AGENT_NOT_CONFIGURED","AI中台尚未配置");
            var limited = new AtomicBoolean(false); var completed = new AtomicBoolean(false);
            var references=run.imageIds().stream().map(id->{images.read(actor,run.sessionId(),id);return JdbcAgentImages.REFERENCE_PREFIX+id;}).toList();
            execution.execute(call,new AgentPromptGateway.Preset(preset.systemPrompt(),authorizedModels),run.input(),references,run.mode(),state,trackedResources()).doOnNext(event -> {
                if ("LIMIT_REACHED".equals(event.type())) limited.set(true);
                if ("ASSISTANT_DONE".equals(event.type())) completed.set(true);
                repository.append(actor, run.id(), event);
            }).blockLast();
            repository.finish(actor, runId, !limited.get() && completed.get() ? "COMPLETED" : "INTERRUPTED",
                    limited.get() ? "ITERATION_LIMIT" : completed.get() ? null : "EXECUTION_INTERRUPTED");
        } catch (AgentProblem expected) {
            repository.finish(actor, runId, "INTERRUPTED", expected.code());
        } catch (Exception unexpected) {
            // Provider bodies, hidden prompts, tool arguments and SQL errors stay out of public events.
            repository.finish(actor, runId, "FAILED", "AGENT_EXECUTION_FAILED");
        }
    }
    private AgentResourceGateway trackedResources() {
        return new AgentResourceGateway() {
            public JsonNode resources(AgentCall call) { return resources.resources(call); }
            public JsonNode describe(AgentCall call, String resource) {
                JsonNode result = resources.describe(call, resource); repository.reference(call.actor(), call.sessionId(), resource); return result;
            }
            public JsonNode query(AgentCall call, String resource, JsonNode query) {
                JsonNode result = resources.query(call, resource, query); repository.reference(call.actor(), call.sessionId(), resource); return result;
            }
            public JsonNode mutate(AgentCall call, String toolId, String resource, JsonNode mutation) {
                return repository.mutateOnce(call, toolId, resource, mutation, () -> resources.mutate(call, toolId, resource, mutation));
            }
        };
    }
    private Path workspace(AgentActor actor, String session) {
        try {
            String owner = HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(actor.stateUserId().getBytes(StandardCharsets.UTF_8)));
            Path path = workspaces.resolve(owner).resolve(session).normalize();
            if (!path.startsWith(workspaces)) throw new IllegalStateException("Invalid workspace path");
            return path;
        } catch (java.security.NoSuchAlgorithmException impossible) { throw new IllegalStateException(impossible); }
    }
    @Override public void close() {
        executor.shutdownNow();
    }
}
