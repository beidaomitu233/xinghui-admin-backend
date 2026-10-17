package com.xinghuiTec.ai.conversation;

/**
 * Fixed public code only; content, SQL and provider exception messages never
 * cross this boundary.
 */
public final class ConversationFailure extends RuntimeException {
    private final int status;
    private final ConversationDtos.ErrorDetails details;
    public ConversationFailure(int status, String code) {
        this(status, code, null);
    }
    public ConversationFailure(int status, String code, ConversationDtos.ErrorDetails details) {
        super(code);
        this.status = status;
        this.details = details;
    }
    public int status() {
        return status;
    }
    public ConversationDtos.ErrorDetails details() {
        return details;
    }
    public static void require(boolean condition, int status, String code) {
        if (!condition)
            throw new ConversationFailure(status, code);
    }
}
