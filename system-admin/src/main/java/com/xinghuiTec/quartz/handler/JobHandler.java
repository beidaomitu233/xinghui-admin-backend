package com.xinghuiTec.quartz.handler;

import com.fasterxml.jackson.databind.JsonNode;

/** Explicit extension point. Implementations are registered as Spring beans. */
public interface JobHandler {
    String key();

    /** Reject invalid business parameters before a job is saved. */
    void validateParameters(JsonNode parameters);

    /** JOB-02 will call this through the registry, never through invoke_target. */
    void execute(JsonNode parameters);
}
