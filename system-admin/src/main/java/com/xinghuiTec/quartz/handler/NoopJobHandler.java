package com.xinghuiTec.quartz.handler;

import com.fasterxml.jackson.databind.JsonNode;
import com.xinghuiTec.exception.ServiceException;
import org.springframework.stereotype.Component;

/** Harmless built-in handler for proving the real scheduling path. */
@Component
public class NoopJobHandler implements JobHandler {
    @Override
    public String key() {
        return "system.noop";
    }

    @Override
    public void validateParameters(JsonNode parameters) {
        if (!parameters.isObject() || !parameters.isEmpty()) {
            throw new ServiceException("handlerParams: system.noop 仅接受空对象 {}");
        }
    }

    @Override
    public void execute(JsonNode parameters) {
        // Deliberately no side effect; the durable fire result proves execution.
    }
}
