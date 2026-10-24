package com.xinghuiTec.agent.api;

import com.fasterxml.jackson.databind.JsonNode;

/** Business modules register resources once, independently of pages or agent prompt wording.
 * The application router installs the freshly revalidated actor context for every method.
 * Providers must enforce their own native operation permissions and record scope.
 */
public interface AgentResourceProvider {
    String namespace();
    JsonNode catalog(AgentCall call);
    JsonNode describe(AgentCall call,String resource);
    JsonNode query(AgentCall call,String resource,JsonNode arguments);
    JsonNode mutate(AgentCall call,String resource,JsonNode arguments);
    void requireReadable(AgentActor actor,String resource);
}
