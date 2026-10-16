package com.xinghuiTec.ai.application;

import com.fasterxml.jackson.databind.node.JsonNodeFactory;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.xinghuiTec.ai.contract.AiRuntimeException;
import com.xinghuiTec.ai.contract.AiTypes.ErrorCode;

/** SSE-only numeric contract. Global Long/ID serializers and internal/Redis event values stay intact. */
final class AiRuntimeWire {
    static final long MAX_SAFE_INTEGER = 9_007_199_254_740_991L;
    private AiRuntimeWire() {}

    static ObjectNode event(AiRuntimeEvents.Event event) {
        if (event.sequence() < 1 || event.sequence() > MAX_SAFE_INTEGER)
            throw new AiRuntimeException(ErrorCode.INVALID_RESPONSE);
        var node = JsonNodeFactory.instance.objectNode();
        node.put("name", event.name().name());
        node.put("requestId", event.requestId());
        node.put("sequence", event.sequence()); // LongNode writes a JSON number regardless of global Long serializer.
        node.put("status", event.status() == null ? null : event.status().name());
        node.put("text", event.text());
        usage(node, "inputTokens", event.inputTokens());
        usage(node, "outputTokens", event.outputTokens());
        node.put("errorCode", event.errorCode() == null ? null : event.errorCode().name());
        return node;
    }

    private static void usage(ObjectNode node, String field, Long value) {
        if (value == null || value < 0 || value > MAX_SAFE_INTEGER) node.putNull(field);
        else node.put(field, value.longValue());
    }
}
