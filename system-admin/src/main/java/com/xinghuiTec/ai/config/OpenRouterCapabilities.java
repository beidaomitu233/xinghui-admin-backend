package com.xinghuiTec.ai.config;

import java.util.List;
import java.util.Set;
import static com.xinghuiTec.ai.config.AiConfigurationDtos.ModelSpec;
import static com.xinghuiTec.ai.contract.AiTypes.*;

/** Reviewed server-owned capability evidence, never a client assertion or a provider-wide default. */
public final class OpenRouterCapabilities {
    private OpenRouterCapabilities() {}
    public static final String EVIDENCE="openrouter-endpoints-20261016-v1";
    // https://openrouter.ai/api/v1/models/openai/gpt-4o-mini/endpoints
    // Both published OpenAI/Azure endpoints advertise response_format. Reviewed 2026-10-16.
    // This is metadata evidence, NOT a successful vendor generation or a deployment seed.
    private static final Set<String> JSON_MODELS=Set.of("openai/gpt-4o-mini");
    public static List<String> jsonObjectModels() { return JSON_MODELS.stream().sorted().toList(); }
    static boolean supportsJson(String modelCode) { return JSON_MODELS.contains(modelCode); }
    static boolean jsonEnabled(ModelSpec model) {
        return model.jsonObject()&&EVIDENCE.equals(model.capabilityEvidence())&&supportsJson(model.modelCode());
    }
    static Capabilities forModel(ModelSpec model) {
        return new Capabilities(jsonEnabled(model)?Set.of(OutputFormat.TEXT,OutputFormat.JSON_OBJECT):Set.of(OutputFormat.TEXT),true,false);
    }
}
