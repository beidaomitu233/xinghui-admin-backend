package com.xinghuiTec.agent.runtime;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.xinghuiTec.agent.api.*;
import io.agentscope.core.message.ToolResultBlock;
import io.agentscope.core.tool.AgentTool;
import io.agentscope.core.tool.ToolCallParam;
import io.agentscope.core.tool.Toolkit;
import reactor.core.publisher.Mono;
import reactor.core.scheduler.Schedulers;

import java.util.List;
import java.util.Map;

/** Resource discovery and data operations are shared by every registered business resource. */
final class BusinessTools {
    private BusinessTools() {}
    static Toolkit create(AgentCall call, AgentResourceGateway gateway, ObjectMapper json) {
        var toolkit = new Toolkit();
        toolkit.registerAgentTool(new ResourceTool("resources_list", "列出当前账号可访问的业务资源及可用操作。", false,
                Map.of("type", "object", "properties", Map.of(), "additionalProperties", false), call, gateway, json));
        toolkit.registerAgentTool(new ResourceTool("resource_describe", "查看资源的字段、查询方式、版本及可执行操作；写入前先读取该定义。", false,
                schema(false), call, gateway, json));
        toolkit.registerAgentTool(new ResourceTool("records_query", "查询指定业务资源。参数结构由resource_describe返回；支持记录、筛选、排序和分页。", false,
                schema(true), call, gateway, json));
        toolkit.registerAgentTool(new ResourceTool("records_mutate", "对业务资源新增、修改、删除或批量处理。使用资源定义的操作和字段；修改和删除携带当前记录版本。", true,
                schema(true), call, gateway, json));
        return toolkit;
    }
    private static Map<String, Object> schema(boolean arguments) {
        var resource = Map.<String, Object>of("type", "string", "description", "resources_list返回的资源标识");
        return Map.of("type", "object", "additionalProperties", false,
                "properties", arguments ? Map.of("resource", resource, "arguments", Map.of("type", "object", "additionalProperties", true))
                        : Map.of("resource", resource),
                "required", arguments ? List.of("resource", "arguments") : List.of("resource"));
    }
    private record ResourceTool(String name, String description, boolean mutation, Map<String, Object> parameters,
                                AgentCall call, AgentResourceGateway gateway, ObjectMapper json) implements AgentTool {
        @Override public String getName() { return name; }
        @Override public String getDescription() { return description; }
        @Override public Map<String, Object> getParameters() { return parameters; }
        @Override public boolean isReadOnly() { return !mutation; }
        @Override public Mono<ToolResultBlock> callAsync(ToolCallParam param) {
            return Mono.fromCallable(() -> {
                call.check();
                Map<String, Object> input = param.getInput();
                String resource = input.get("resource") instanceof String value ? value : "";
                if (!name.equals("resources_list") && (resource.isBlank() || resource.length() > 128))
                    throw new AgentProblem("INVALID_RESOURCE", "请选择有效的业务资源");
                JsonNode arguments = json.valueToTree(input.getOrDefault("arguments", Map.of()));
                if (!arguments.isObject()) throw new AgentProblem("INVALID_ARGUMENTS", "操作参数必须是对象");
                JsonNode result = switch (name) {
                    case "resources_list" -> gateway.resources(call);
                    case "resource_describe" -> gateway.describe(call, resource);
                    case "records_query" -> gateway.query(call, resource, arguments);
                    case "records_mutate" -> gateway.mutate(call, param.getToolUseBlock().getId(), resource, arguments);
                    default -> throw new IllegalStateException("Unknown registered tool");
                };
                call.check();
                return ToolResultBlock.text(json.writeValueAsString(result));
            }).onErrorResume(AgentProblem.class, problem -> Mono.just(ToolResultBlock.error(
                    json.createObjectNode().put("code", problem.code()).put("message", problem.getMessage()).toString())))
              .onErrorResume(Exception.class, ignored -> Mono.just(ToolResultBlock.error(
                    "{\"code\":\"BUSINESS_OPERATION_FAILED\",\"message\":\"操作未能完成，请查询当前状态后再决定下一步\"}")))
              .subscribeOn(Schedulers.boundedElastic());
        }
    }
}
