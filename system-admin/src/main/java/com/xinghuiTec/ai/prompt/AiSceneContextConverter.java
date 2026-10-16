package com.xinghuiTec.ai.prompt;

import java.util.Map;
import java.util.Set;

/** Code-owned pure HTTP-to-context conversion. No business reads, logging, reflection or identity input. */
public interface AiSceneContextConverter<C extends AiBusinessVariableResolver.BusinessContext> {
    String appKey();
    Class<C> contextType();
    Set<String> allowedFields();
    Set<String> requiredFields();
    C convert(Map<String, String> fields);
}
