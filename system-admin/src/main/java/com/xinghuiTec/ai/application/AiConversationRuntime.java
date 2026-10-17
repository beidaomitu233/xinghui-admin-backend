package com.xinghuiTec.ai.application;

import com.xinghuiTec.ai.contract.AiTypes.*;
import java.util.List;
import java.util.Map;

/** B's durable history hook; no provider or scene-management orchestrator dependency. */
public interface AiConversationRuntime {
    record Target(String conversationId,int expectedRevision) {}
    record Link(String conversationId,int revision,String userMessageId,String assistantMessageId,boolean contextTruncated,String contextStartMessageId) {}
    record History(List<AiMessage> messages,List<Long> requestIds) {
        public History {messages=List.copyOf(messages);requestIds=List.copyOf(requestIds);}
        @Override public String toString(){return "ConversationHistory[REDACTED]";}
    }
    /** Runs in the same transaction as the original request insert, before rendering/provider I/O. */
    void admitted(AiRequestStore.Request request,AiRequestStore.Owner owner,Map<String,Object> inputs,Target target);
    History context(long requestId,int maxBytes);
    List<Long> contextRequests(long requestId);
    Link link(long requestId);
    void delta(AiRequestStore.Request request,String text);
    void finished(AiRequestStore.Request request);
    void reconcile();
}
