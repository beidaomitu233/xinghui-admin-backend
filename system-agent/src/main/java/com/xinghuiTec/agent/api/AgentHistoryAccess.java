package com.xinghuiTec.agent.api;

import java.util.Set;

/** Revalidate current resource permissions before returning historical answers or tool results. */
public interface AgentHistoryAccess {
    void requireReadable(AgentActor actor, Set<String> resources);
}
