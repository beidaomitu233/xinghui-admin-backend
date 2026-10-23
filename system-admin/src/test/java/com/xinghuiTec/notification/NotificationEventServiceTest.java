package com.xinghuiTec.notification;

import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

class NotificationEventServiceTest {
    @Test
    void acceptsTwoBusinessSnapshotsAndDeliversIdempotentlyWithoutTemplates() {
        var f = new NotificationDeliveryServiceTest.Fixture();
        f.user(1, "000000", 1, 0);
        var service = new NotificationEventService(f.jdbc, new NotificationContentRules(""));
        var a = service.submit(new NotificationEventPublisher.Event("000000", 1,
                "orders.created", "o-1", "订单已创建", "编号1", "/system/user"));
        var duplicate = service.submit(new NotificationEventPublisher.Event("000000", 1,
                "orders.created", "o-1", "变更内容", "编号2", null));
        var b = service.submit(new NotificationEventPublisher.Event("000000", 1,
                "orders.reviewed", "o-1", "审核完成", "结果已保存", null));
        assertEquals("PENDING", a.status());
        assertEquals(0, f.count("SELECT COUNT(*) FROM sys_notification_inbox"));
        assertTrue(f.delivery.deliverOne(a.requestId()));
        assertTrue(f.delivery.deliverOne(duplicate.requestId()));
        assertTrue(f.delivery.deliverOne(b.requestId()));
        assertEquals(2, f.count("SELECT COUNT(*) FROM sys_notification_inbox"));
        assertEquals("订单已创建", f.jdbc.queryForObject("SELECT title FROM sys_notification_inbox WHERE event_code='orders.created'", String.class));
    }

    @Test
    void recordsOnlySafeFailureMetadataForInvalidContentAndTarget() {
        var f = new NotificationDeliveryServiceTest.Fixture();
        var service = new NotificationEventService(f.jdbc, new NotificationContentRules(""));
        var invalid = new NotificationEventPublisher.Event("000000", 1, "orders.created", "o-2", "", "private body", null);
        assertEquals("CONTENT_INVALID", assertThrows(NotificationContractException.class, () -> service.submit(invalid)).code());
        var row = f.jdbc.queryForMap("SELECT * FROM sys_notification_event_request");
        assertEquals("REJECTED", row.get("STATUS"));
        assertNull(row.get("BODY"));
        assertThrows(NotificationContractException.class, () -> service.submit(new NotificationEventPublisher.Event("000000", 1, "orders.created", "o-3", "title", "body", "//evil.example")));
        assertEquals(0, f.count("SELECT COUNT(*) FROM sys_notification_event_request WHERE status='PENDING'"));
    }

    @Test
    void failedMarkerAndCompensationCannotReopenRetiredWork() {
        var f = new NotificationDeliveryServiceTest.Fixture();
        var service = new NotificationEventService(f.jdbc, new NotificationContentRules(""));
        long id = f.request("000000", 1, "orders.created", "retired", "title");
        service.markFailed(id, "TEMPLATE_RETIRED");
        assertFalse(f.delivery.requeueTransientFailure(id));
        assertFalse(f.delivery.deliverOne(id));
        assertThrows(IllegalArgumentException.class, () -> service.markFailed(id, "raw error"));
    }
}
