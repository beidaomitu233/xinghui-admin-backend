package com.xinghuiTec.ai.conversation;

import com.xinghuiTec.ai.application.AiRequestStatus;
import com.xinghuiTec.ai.scene.SceneDtos;
import java.time.Instant;
import java.util.List;

/** Practical conversation HTTP contract. Identity and tenant always come from authentication. */
public final class ConversationDtos {
    private ConversationDtos() {}
    public enum Kind { BUSINESS, TEST }
    public enum Role { USER, ASSISTANT }
    public enum History { COMPLETE, LEGACY_NO_BODY }
    public record TestSelection(int expectedSceneRevision,int modelIndex,boolean confirmedCosts) {}
    public record Start(String sceneKey,Kind kind,SceneDtos.BusinessRef businessRef,String message,
                        String clientMutationId,TestSelection test) {
        @Override public String toString(){return "ConversationStart[REDACTED]";}
    }
    public record Continue(int expectedRevision,String message,String clientMutationId,TestSelection test) {
        @Override public String toString(){return "ConversationContinue[REDACTED]";}
    }
    public record Summary(String conversationId,String title,String sceneKey,String sceneName,Kind kind,
                          SceneDtos.BusinessRef businessRef,String sourceLabel,int revision,AiRequestStatus status,
                          String lastMessage,Instant updatedAt,History history,boolean canContinue) {
        @Override public String toString(){return "ConversationSummary[REDACTED]";}
    }
    public record Message(String messageId,long sequence,Role role,String content,AiRequestStatus status,
                          String requestId,String sceneVersionId,String errorCode,boolean partial,Instant createdAt,Instant finishedAt) {
        @Override public String toString(){return "ConversationMessage[REDACTED]";}
    }
    public record Detail(Summary conversation,List<Message> messages,String notice) {
        public Detail {messages=List.copyOf(messages);}
        @Override public String toString(){return "ConversationDetail[REDACTED]";}
    }
    public record Page(List<Summary> records,String total,int pageNum,int pageSize) {
        public Page {records=List.copyOf(records);}
        @Override public String toString(){return "ConversationPage[REDACTED]";}
    }
    public record Accepted(String conversationId,int revision,String requestId,String sceneVersionId,
                           String userMessageId,String assistantMessageId,AiRequestStatus status,
                           boolean contextTruncated,String contextStartMessageId) {}
    public record ErrorDetails(String conversationId,Integer currentRevision,String activeRequestId) {}
}
