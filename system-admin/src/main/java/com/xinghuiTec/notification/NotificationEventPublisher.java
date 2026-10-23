package com.xinghuiTec.notification;

/** Trusted in-process business boundary; content is a snapshot, never accepted from an unauthenticated HTTP payload. */
public interface NotificationEventPublisher {
    record Event(String tenantId, long recipientUserId, String eventCode, String eventKey,
                 String title, String body, String target) {}

    record Submission(long requestId, String status) {}

    Submission submit(Event event);

    /** For a future delivery worker to record a failed pending attempt without logging content. */
    void markFailed(long requestId, String failureCode);
}
