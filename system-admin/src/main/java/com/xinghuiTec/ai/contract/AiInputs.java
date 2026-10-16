package com.xinghuiTec.ai.contract;

import java.math.BigDecimal;
import java.math.BigInteger;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/** Immutable JSON-shaped validated inputs; never accepts arbitrary executable Java objects. */
public final class AiInputs {
    private final Map<String, Object> values;

    public AiInputs(Map<String, ?> values) { this.values = copyMap(values, 0); }
    public Map<String, Object> values() { return values; }
    @Override public String toString() { return "AiInputs[REDACTED]"; }

    private static Map<String, Object> copyMap(Map<?, ?> values, int depth) {
        var copy = new LinkedHashMap<String, Object>();
        for (var entry : values.entrySet()) {
            if (!(entry.getKey() instanceof String key)) throw new IllegalArgumentException("Non-string input key");
            copy.put(key, copy(entry.getValue(), depth + 1));
        }
        return Collections.unmodifiableMap(copy);
    }

    private static Object copy(Object value, int depth) {
        if (depth > 16) throw new IllegalArgumentException("Input nesting exceeds contract limit");
        if (value == null || value instanceof String || value instanceof Boolean || value instanceof Byte
                || value instanceof Short || value instanceof Integer || value instanceof Long
                || value instanceof BigDecimal || value instanceof BigInteger) return value;
        if (value instanceof Double number && Double.isFinite(number)) return value;
        if (value instanceof Float number && Float.isFinite(number)) return value;
        if (value instanceof Map<?, ?> map) return copyMap(map, depth);
        if (value instanceof List<?> list) return list.stream().map(item -> copy(item, depth + 1)).toList();
        throw new IllegalArgumentException("Unsupported input value");
    }
}
