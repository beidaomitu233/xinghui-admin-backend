package com.xinghuiTec.quartz.handler;

import com.fasterxml.jackson.databind.JsonNode;
import com.xinghuiTec.exception.ServiceException;
import org.springframework.stereotype.Component;

import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.regex.Pattern;
import java.util.Set;

/** Closed registry: only explicitly declared handler beans may be configured. */
@Component
public class JobHandlerRegistry {
    private static final Pattern KEY = Pattern.compile("[a-z][a-z0-9]*(?:[.-][a-z0-9]+)*");
    private final Map<String, JobHandler> handlers;

    public JobHandlerRegistry(List<JobHandler> registered) {
        Map<String, JobHandler> byKey = new HashMap<>();
        for (JobHandler handler : registered) {
            String key = handler.key();
            if (key == null || key.length() > 100 || !KEY.matcher(key).matches()
                    || byKey.putIfAbsent(key, handler) != null) {
                throw new IllegalStateException("Invalid or duplicate job handler key: " + key);
            }
        }
        handlers = Map.copyOf(byKey);
    }

    public void validate(String key, JsonNode parameters) {
        JobHandler handler = key == null ? null : handlers.get(key);
        if (handler == null) {
            throw new ServiceException("handlerKey: 未注册的任务处理器");
        }
        handler.validateParameters(parameters);
    }

    public JobHandler require(String key) {
        JobHandler handler = key == null ? null : handlers.get(key);
        if (handler == null) {
            throw new ServiceException("handlerKey: 未注册的任务处理器");
        }
        return handler;
    }

    public Set<String> keys() {
        return handlers.keySet();
    }
}
