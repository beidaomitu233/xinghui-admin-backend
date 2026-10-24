package com.xinghuiTec.agent.runtime;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.xinghuiTec.agent.api.*;
import io.agentscope.core.state.InMemoryAgentStateStore;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import reactor.core.publisher.Flux;

import java.nio.file.Path;
import java.time.Duration;
import java.time.Instant;
import java.util.*;
import java.util.concurrent.atomic.AtomicInteger;

import static org.assertj.core.api.Assertions.*;

class AgentRuntimeTest {
    @TempDir Path workspace;
    private final ObjectMapper json = new ObjectMapper();
    private final AgentActor owner = new AgentActor("tenant-a", "42");
    private AgentCall call() { return new AgentCall(owner, "session-a", "run-a", "initial", Instant.now().plusSeconds(30), () -> false); }

    @Test void officialHarnessDiscoversMutatesQueriesAndKeepsToolHistory() throws Exception {
        var turns = new AtomicInteger();
        var received = new ArrayList<JsonNode>();
        var resourceCalls = new ArrayList<String>();
        AgentModelGateway models = (context, request) -> {
            assertThat(context.actor()).isEqualTo(owner);
            assertThat(context.callId()).isNotEqualTo("initial");
            JsonNode body = read(request); received.add(body);
            Set<String> tools = new HashSet<>();
            body.path("tools").forEach(tool -> tools.add(tool.path("function").path("name").asText()));
            assertThat(tools).containsExactlyInAnyOrder("resources_list", "resource_describe", "records_query", "records_mutate", "todo_write");
            int turn = turns.getAndIncrement();
            return switch (turn) {
                case 0 -> tool("resources_list", "a", "{}");
                case 1 -> tool("resource_describe", "b", "{\"resource\":\"forms:1\"}");
                case 2 -> tool("records_mutate", "c", "{\"resource\":\"forms:1\",\"arguments\":{\"operation\":\"create\",\"data\":{\"name\":\"QA 商品\"}}}");
                case 3 -> tool("records_query", "d", "{\"resource\":\"forms:1\",\"arguments\":{\"page\":1}}");
                default -> text("已新增并核对QA商品");
            };
        };
        AgentResourceGateway resources = new AgentResourceGateway() {
            public JsonNode resources(AgentCall c) { resourceCalls.add("list"); return read("[{\"id\":\"forms:1\"}]"); }
            public JsonNode describe(AgentCall c, String r) { resourceCalls.add("describe:" + r); return read("{\"fields\":[\"name\"]}"); }
            public JsonNode query(AgentCall c, String r, JsonNode q) { resourceCalls.add("query:" + r); return read("{\"records\":[{\"name\":\"QA 商品\"}]}"); }
            public JsonNode mutate(AgentCall c, String id, String r, JsonNode data) {
                assertThat(c.actor()).isEqualTo(owner); assertThat(id).isEqualTo("c");
                assertThat(data.path("data").path("name").asText()).isEqualTo("QA 商品");
                resourceCalls.add("mutate:" + r); return read("{\"id\":1,\"version\":1}");
            }
        };
        var state = new InMemoryAgentStateStore();
        List<AgentEventView> events;
        try (var runtime = new AgentRuntime(call(), "你负责管理授权业务数据。", workspace, state, models, resources, json)) {
            events = runtime.run("新增QA商品并核对").collectList().block(Duration.ofSeconds(25));
        }
        assertThat(resourceCalls).containsExactly("list", "describe:forms:1", "mutate:forms:1", "query:forms:1");
        assertThat(turns).hasValue(5);
        assertThat(events).anySatisfy(event -> {
            assertThat(event.type()).isEqualTo("TEXT_DELTA");
            assertThat(event.data().get("delta")).isEqualTo("已新增并核对QA商品");
        });
        assertThat(events.toString()).doesNotContain("private reasoning", "你负责管理授权业务数据");
        assertThat(received.get(4).path("messages").findValuesAsText("role")).contains("tool");
        assertThat(state.exists(owner.stateUserId(), "session-a")).isTrue();
        assertThat(state.exists(new AgentActor("tenant-b", "42").stateUserId(), "session-a")).isFalse();
    }

    @Test void toolFailureIsAvailableToAgentWithoutLeakingInternalException() {
        var turns = new AtomicInteger(); var last = new ArrayList<String>();
        AgentModelGateway models = (context, request) -> {
            last.add(request);
            return turns.getAndIncrement() == 0 ? tool("records_query", "denied", "{\"resource\":\"admin-only\",\"arguments\":{}}") : text("当前账号不能读取该资源");
        };
        var resources = new AgentResourceGateway() {
            public JsonNode resources(AgentCall c) { return read("[]"); }
            public JsonNode describe(AgentCall c, String r) { throw new UnsupportedOperationException(); }
            public JsonNode query(AgentCall c, String r, JsonNode q) { throw new AgentProblem("FORBIDDEN", "无权访问该资源"); }
            public JsonNode mutate(AgentCall c, String id, String r, JsonNode q) { throw new IllegalStateException("database-password-DO-NOT-EXPOSE"); }
        };
        try (var runtime = new AgentRuntime(call(), "处理业务", workspace, new InMemoryAgentStateStore(), models, resources, json)) {
            var events = runtime.run("查询管理资源").collectList().block(Duration.ofSeconds(25));
            assertThat(events).isNotEmpty();
            assertThat(last.get(1)).contains("FORBIDDEN", "无权访问该资源").doesNotContain("database-password");
        }
    }

    @Test void stoppedRunNeverCallsModelOrBusinessGateway() {
        var stopped = new AgentCall(owner, "s", "r", "c", Instant.now().plusSeconds(30), () -> true);
        try (var runtime = new AgentRuntime(stopped, "处理业务", workspace, new InMemoryAgentStateStore(),
                (c, r) -> { throw new AssertionError("must not send"); }, null, json)) {
            assertThatThrownBy(() -> runtime.run("继续").collectList().block()).isInstanceOf(AgentProblem.class).hasMessage("任务已停止");
        }
    }
    @Test void nativeTodoToolProducesPublicTaskPanelWithoutReasoningContent() {
        var turns = new AtomicInteger();
        AgentModelGateway model = (context, request) -> turns.getAndIncrement() == 0
                ? tool("todo_write", "plan-1", "{\"todos\":[{\"content\":\"核对业务数据\",\"status\":\"in_progress\"},{\"content\":\"保存修改\",\"status\":\"pending\"}]}")
                : text("计划已更新");
        try (var runtime = new AgentRuntime(call(), "处理业务", workspace, new InMemoryAgentStateStore(), model, null, json)) {
            var events = runtime.run("安排任务").collectList().block(Duration.ofSeconds(25));
            assertThat(events).anySatisfy(event -> {
                assertThat(event.type()).isEqualTo("PLAN");
                assertThat(event.data().get("steps").toString()).contains("核对业务数据", "IN_PROGRESS", "保存修改", "PENDING");
            });
            assertThat(events.toString()).doesNotContain("private reasoning");
        }
    }
    private JsonNode read(String text) { try { return json.readTree(text); } catch (Exception e) { throw new AssertionError(e); } }
    private Flux<String> tool(String name, String id, String arguments) {
        var function = json.createObjectNode().put("name", name).put("arguments", arguments);
        var tool = json.createObjectNode().put("index", 0).put("id", id).put("type", "function").set("function", function);
        var delta = json.createObjectNode().set("tool_calls", json.createArrayNode().add(tool));
        return Flux.just(chunk(delta, null), chunk(json.createObjectNode(), "tool_calls"));
    }
    private Flux<String> text(String text) {
        return Flux.just(chunk(json.createObjectNode().put("reasoning_content", "private reasoning never shown"), null),
                chunk(json.createObjectNode().put("content", text), null), chunk(json.createObjectNode(), "stop"));
    }
    private String chunk(JsonNode delta, String finish) {
        var choice = json.createObjectNode().put("index", 0).put("finish_reason", finish); choice.set("delta", delta);
        var root = json.createObjectNode().put("id", UUID.randomUUID().toString()).put("object", "chat.completion.chunk");
        root.set("choices", json.createArrayNode().add(choice));
        return root.toString();
    }
}
