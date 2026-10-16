package com.xinghuiTec.ai.prompt;

import com.xinghuiTec.ai.prompt.AiBusinessVariableResolver.Actor;
import com.xinghuiTec.ai.prompt.AiBusinessVariableResolver.BusinessContext;
import com.xinghuiTec.ai.prompt.AiBusinessVariableResolver.Resource;
import com.xinghuiTec.domain.entity.LoginUser;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.stereotype.Service;

import java.util.HashMap;
import java.util.HashSet;
import java.util.Map;
import java.util.Set;
import java.util.function.Supplier;

import static com.xinghuiTec.ai.prompt.AiBusinessRenderException.Code.*;

/** Internal business renderer. No HTTP endpoint, provider call, persistence or business-data logging. */
@Service
public class AiBusinessPromptRenderer {
    public static final int MAX_VALUE_LENGTH = 2_048;
    public static final int MAX_RENDERED_LENGTH = AiPromptSceneRules.MAX_PROMPT_LENGTH;

    public record RenderedPrompt(String appKey, String sceneKey, long modelId,
                                 int publishedRevision, String prompt) {
        @Override public String toString() {
            return "RenderedPrompt[appKey=" + appKey + ",sceneKey=" + sceneKey
                    + ",modelId=" + modelId + ",publishedRevision=" + publishedRevision + ",prompt=REDACTED]";
        }
    }

    private final AiPromptSceneService scenes;
    private final AiVariableResolverRegistry registry;
    private final AiPromptSceneRules rules;

    public AiBusinessPromptRenderer(AiPromptSceneService scenes, AiVariableResolverRegistry registry,
                                    AiPromptSceneRules rules) {
        this.scenes = scenes;
        this.registry = registry;
        this.rules = rules;
    }

    public RenderedPrompt render(String appKey, String sceneKey, BusinessContext context) {
        Actor actor = authenticatedActor();
        return renderRegistered(appKey, sceneKey, context, actor, registry.require(appKey));
    }

    Actor authenticatedActor() {
        var authentication = SecurityContextHolder.getContext().getAuthentication();
        if (authentication == null || !authentication.isAuthenticated()
                || !(authentication.getPrincipal() instanceof LoginUser login)) {
            throw new AiBusinessRenderException(UNAUTHENTICATED);
        }
        var user = login.getUser();
        String tenantId = login.getTenantId();
        if (user == null || user.getUserId() == null || user.getUserId() <= 0 || !login.isEnabled()
                || tenantId == null || tenantId.isBlank() || tenantId.length() > 64
                || !tenantId.equals(user.getTenantId())) {
            throw new AiBusinessRenderException(INVALID_IDENTITY);
        }
        Set<String> authorities = new HashSet<>();
        authentication.getAuthorities().forEach(authority -> authorities.add(authority.getAuthority()));
        return new Actor(user.getUserId(), tenantId, authorities);
    }

    private <C extends BusinessContext> RenderedPrompt renderRegistered(String appKey, String sceneKey,
            BusinessContext requestedContext, Actor actor, AiVariableResolverRegistry.Registration<C> registration) {
        if (!registration.contextType().isInstance(requestedContext)) {
            throw new AiBusinessRenderException(CONTEXT_INVALID);
        }
        if (!actor.authorities().contains(registration.requiredAuthority())) {
            throw new AiBusinessRenderException(BUSINESS_PERMISSION_REQUIRED);
        }
        AiPromptSceneService.PublishedScene scene;
        try { scene = scenes.publishedForUse(appKey, sceneKey); }
        catch (RuntimeException unavailable) { throw new AiBusinessRenderException(SCENE_UNAVAILABLE); }
        Set<String> requestedNames = new HashSet<>();
        scene.variables().forEach(variable -> requestedNames.add(variable.name()));
        if (!registration.variables().containsAll(requestedNames)) {
            throw new AiBusinessRenderException(VARIABLE_NOT_REGISTERED);
        }
        C context = registration.contextType().cast(requestedContext);
        var resolver = registration.resolver();
        Resource resource = resolverCall(() -> resolver.findResource(context, actor));
        if (resource == null) throw new AiBusinessRenderException(RESOURCE_NOT_FOUND);
        if (resource.resourceId() == null || resource.resourceId().isBlank() || resource.resourceId().length() > 256
                || resource.tenantId() == null || resource.tenantId().isBlank() || resource.tenantId().length() > 64) {
            throw new AiBusinessRenderException(CONTEXT_INVALID);
        }
        if (!actor.tenantId().equals(resource.tenantId())) throw new AiBusinessRenderException(TENANT_MISMATCH);
        if (!resolverCall(() -> resolver.canRead(context, resource, actor))) {
            throw new AiBusinessRenderException(RESOURCE_FORBIDDEN);
        }
        Set<String> declarations = Set.copyOf(requestedNames);
        Map<String, String> values = resolverCall(() -> {
            Map<String, String> resolved = resolver.resolve(context, resource, actor, declarations);
            if (resolved != null && resolved.size() > AiPromptSceneRules.MAX_VARIABLES) {
                throw new AiBusinessRenderException(UNDECLARED_VALUE);
            }
            return resolved == null ? null : new HashMap<>(resolved);
        });
        if (values == null || !values.keySet().containsAll(declarations)) {
            throw new AiBusinessRenderException(MISSING_VARIABLE);
        }
        if (!declarations.containsAll(values.keySet())) throw new AiBusinessRenderException(UNDECLARED_VALUE);
        for (var variable : scene.variables()) {
            String value = values.get(variable.name());
            if (value == null || value.isBlank()) throw new AiBusinessRenderException(MISSING_VARIABLE);
            if (value.length() > MAX_VALUE_LENGTH) throw new AiBusinessRenderException(INVALID_VARIABLE_VALUE);
            try { rules.validateTypedValue(variable.type(), value, "运行变量"); }
            catch (RuntimeException invalid) { throw new AiBusinessRenderException(INVALID_VARIABLE_VALUE); }
        }
        var matcher = AiPromptSceneRules.PLACEHOLDER.matcher(scene.promptTemplate());
        StringBuilder rendered = new StringBuilder();
        int cursor = 0;
        while (matcher.find()) {
            appendBounded(rendered, scene.promptTemplate().substring(cursor, matcher.start()));
            appendBounded(rendered, values.get(matcher.group(1)));
            cursor = matcher.end();
        }
        appendBounded(rendered, scene.promptTemplate().substring(cursor));
        return new RenderedPrompt(scene.appKey(), scene.sceneKey(), scene.modelId(), scene.publishedRevision(), rendered.toString());
    }

    private void appendBounded(StringBuilder target, String value) {
        if (target.length() + value.length() > MAX_RENDERED_LENGTH) throw new AiBusinessRenderException(OUTPUT_TOO_LONG);
        target.append(value);
    }

    private <T> T resolverCall(Supplier<T> operation) {
        try { return operation.get(); }
        catch (AiBusinessRenderException safe) { throw safe; }
        catch (RuntimeException failure) { throw new AiBusinessRenderException(RESOLVER_FAILED); }
    }
}
