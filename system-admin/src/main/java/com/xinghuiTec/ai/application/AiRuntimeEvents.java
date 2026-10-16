package com.xinghuiTec.ai.application;

import com.xinghuiTec.ai.contract.AiTypes.ErrorCode;
import java.util.List;
import java.util.Locale;

/** Ephemeral bounded stream data only; the request journal never stores the text field. */
public interface AiRuntimeEvents {
    enum Name {
        ACCEPTED, DELTA, USAGE, COMPLETED, FAILED, CANCELLED;
        public String wireName() { return name().toLowerCase(Locale.ROOT); }
    }
    record Event(Name name, String requestId, long sequence, AiRequestStatus status, String text,
                 Long inputTokens, Long outputTokens, ErrorCode errorCode) {
        public Event {
            if (name == null || requestId == null || !requestId.matches("[1-9][0-9]{0,18}")
                    || sequence < 0 || (name == Name.DELTA && (text == null || text.length() > 4096))
                    || (name == Name.COMPLETED && (status != AiRequestStatus.SUCCEEDED || errorCode != null))) {
                throw new IllegalArgumentException("Invalid runtime event");
            }
        }
        public Event sequenced(long value) {
            return new Event(name, requestId, value, status, text, inputTokens, outputTokens, errorCode);
        }
        @Override public String toString() { return "AiRuntimeEvent[REDACTED]"; }
    }

    Event append(Event event);
    List<Event> after(String requestId, long sequence);
}
