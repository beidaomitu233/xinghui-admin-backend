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
    /** Native read-only snapshot for the concrete change, never an arbitrary query supplied by a model. */
    default JsonNode changeSnapshot(AgentCall call,String resource,JsonNode arguments) {
        return arguments.hasNonNull("id")?query(call,resource,com.fasterxml.jackson.databind.node.JsonNodeFactory.instance.objectNode().set("id",arguments.get("id"))):null;
    }
    void requireReadable(AgentActor actor,String resource);
}
