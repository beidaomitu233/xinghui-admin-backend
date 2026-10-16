package com.xinghuiTec.notification;

import java.util.Map;

/** Internal business-module boundary. Submission persists a pending request; NTF-02 owns delivery. */
public interface NotificationEventPublisher {
    record Event(String tenantId, long recipientUserId, String eventCode, String eventKey,
                 Map<String, String> variables) {}

    record Submission(long requestId, String status) {}

    Submission submit(Event event);

    /** For a future delivery worker to record a failed pending attempt without logging content. */
    void markFailed(long requestId, String failureCode);
}
