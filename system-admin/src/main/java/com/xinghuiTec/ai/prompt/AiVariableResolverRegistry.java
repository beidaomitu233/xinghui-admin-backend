package com.xinghuiTec.ai.prompt;

import org.springframework.stereotype.Component;

import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

import static com.xinghuiTec.ai.prompt.AiBusinessRenderException.Code.*;

/** Immutable registration metadata; one resolver owns one app_key namespace. */
@Component
public class AiVariableResolverRegistry {
    record Registration<C extends AiBusinessVariableResolver.BusinessContext>(
            AiBusinessVariableResolver<C> resolver, Class<C> contextType,
            Set<String> variables, String requiredAuthority) {}

    private final Map<String, Registration<?>> registrations;

    public AiVariableResolverRegistry(List<AiBusinessVariableResolver<?>> resolvers, AiPromptSceneRules rules) {
        Map<String, Registration<?>> registered = new HashMap<>();
        for (AiBusinessVariableResolver<?> resolver : resolvers) {
            try {
                String appKey = rules.appKey(resolver.appKey());
                Registration<?> registration = registration(resolver);
                if (registered.putIfAbsent(appKey, registration) != null) {
                    throw new AiBusinessRenderException(DUPLICATE_REGISTRATION);
                }
            } catch (AiBusinessRenderException invalid) {
                throw invalid;
            } catch (RuntimeException invalid) {
                throw new AiBusinessRenderException(INVALID_REGISTRATION);
            }
        }
        registrations = Map.copyOf(registered);
    }

    private <C extends AiBusinessVariableResolver.BusinessContext> Registration<C> registration(
            AiBusinessVariableResolver<C> resolver) {
        Class<C> contextType = resolver.contextType();
        Set<String> variables = Set.copyOf(resolver.variableNames());
        String authority = resolver.requiredAuthority();
        if (contextType == null || !AiBusinessVariableResolver.BusinessContext.class.isAssignableFrom(contextType)
                || contextType == AiBusinessVariableResolver.BusinessContext.class
                || variables.size() > AiPromptSceneRules.MAX_VARIABLES
                || variables.stream().anyMatch(name -> !name.matches("[A-Za-z][A-Za-z0-9_]{0,63}"))
                || authority == null || !authority.matches("[A-Za-z][A-Za-z0-9:_-]{0,127}")
                || authority.startsWith("ai:model:") || authority.startsWith("ai:prompt:")) {
            throw new AiBusinessRenderException(INVALID_REGISTRATION);
        }
        return new Registration<>(resolver, contextType, variables, authority);
    }

    Registration<?> require(String appKey) {
        if (appKey == null) throw new AiBusinessRenderException(APP_NOT_REGISTERED);
        Registration<?> registration = registrations.get(appKey);
        if (registration == null) throw new AiBusinessRenderException(APP_NOT_REGISTERED);
        return registration;
    }
}
