package com.xinghuiTec.quartz.handler;

import com.fasterxml.jackson.databind.JsonNode;
import com.xinghuiTec.exception.ServiceException;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Component;

/** Side-effect-free failure probe, disabled unless explicitly enabled for isolated tests. */
@Component
@ConditionalOnProperty(name = "job.diagnostic-failure-handler", havingValue = "true")
public class DiagnosticFailureJobHandler implements JobHandler {
    @Override
    public String key() {
        return "system.diagnostic-fail";
    }

    @Override
    public void validateParameters(JsonNode parameters) {
        if (!parameters.isObject() || !parameters.isEmpty()) {
            throw new ServiceException("handlerParams: system.diagnostic-fail 仅接受空对象 {}");
        }
    }

    @Override
    public void execute(JsonNode parameters) {
        throw new IllegalStateException();
    }
}
