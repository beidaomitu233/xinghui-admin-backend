package com.xinghuiTec.ai.prompt;

import java.util.Map;
import java.util.Set;

/** Code-owned extension only; implementations must never log context or resolved values. */
public interface AiBusinessVariableResolver<C extends AiBusinessVariableResolver.BusinessContext> {
    /** Applications define small typed contexts, usually containing only a resource reference. */
    interface BusinessContext {}

    /** Captured from authenticated server state, never a caller-supplied tenant/user. */
    record Actor(long userId, String tenantId, Set<String> authorities) {
        public Actor { authorities = Set.copyOf(authorities); }
    }

    /** Metadata lookup must not return business variable values. Tenant ownership comes from storage. */
    record Resource(String resourceId, String tenantId) {}

    String appKey();
    Class<C> contextType();
    Set<String> variableNames();
    String requiredAuthority();

    /** Validate the typed context and look up metadata; return null when the resource does not exist. */
    Resource findResource(C context, Actor actor);

    /** App-specific resource/owner/department checks, in addition to framework tenant and permission checks. */
    boolean canRead(C context, Resource resource, Actor actor);

    /**
     * Called only after all access checks. Read the exact authorized resource with tenant/access predicates;
     * implementations must preserve or recheck ownership when loading values concurrently.
     * Return exactly the requested declarations, no sample fallback. Do not log context/values.
     */
    Map<String, String> resolve(C context, Resource resource, Actor actor, Set<String> variableNames);
}
