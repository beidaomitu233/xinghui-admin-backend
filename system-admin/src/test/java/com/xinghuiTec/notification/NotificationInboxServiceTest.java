package com.xinghuiTec.notification;

import com.xinghuiTec.domain.entity.LoginUser;
import com.xinghuiTec.domain.entity.SysUser;
import com.xinghuiTec.exception.ServiceException;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.jdbc.datasource.DataSourceTransactionManager;

import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

class NotificationInboxServiceTest {
    @Test
    void remoteTitleSortSpansThreePagesWithoutLeakingOtherRecipients() {
        var fixture = new NotificationDeliveryServiceTest.Fixture();
        fixture.user(1, "000000", 1, 0);
        fixture.user(2, "000000", 1, 0);
        for (int index = 0; index < 24; index++) {
            int rank = index * 7 % 24;
            fixture.delivery.deliverOne(fixture.request("000000", 1, "sample.created", "sort-" + index, String.format("N%02d", rank)));
        }
        fixture.delivery.deliverOne(fixture.request("000000", 2, "sample.created", "foreign-sort", "A-foreign"));
        login(1, "000000");
        var inbox = new NotificationInboxService(fixture.jdbc, false);
        var titles = new java.util.ArrayList<String>();
        for (int page = 1; page <= 3; page++) {
            var response = inbox.page(page, 10, false, "title", "asc");
            assertEquals(24, response.total());
            titles.addAll(response.items().stream().map(NotificationInboxService.Item::title).toList());
        }
        assertEquals(java.util.stream.IntStream.range(0, 24).mapToObj(index -> String.format("N%02d", index)).toList(), titles);
        assertEquals(List.of("N23", "N22", "N21"), inbox.page(1, 3, false, "title", "desc").items().stream().map(NotificationInboxService.Item::title).toList());
        assertThrows(ServiceException.class, () -> inbox.page(1, 10, false, "recipient_user_id", "asc"));
        assertThrows(ServiceException.class, () -> inbox.page(1, 10, false, "title", "asc; SELECT 1"));
        assertEquals(24, inbox.unreadCount());
    }

    @AfterEach
    void clearLogin() { SecurityContextHolder.clearContext(); }

    @Test
    void unauthenticatedCallerCannotReadInbox() {
        var fixture = new NotificationDeliveryServiceTest.Fixture();
        fixture.user(1, "000000", 1, 0);
        assertThrows(AccessDeniedException.class,
                () -> new NotificationInboxService(fixture.jdbc, false).page(1, 20, false));
    }

    @Test
    void ownerOnlyReadAndRepeatableMarkReadPersistAcrossServiceInstances() {
        var fixture = new NotificationDeliveryServiceTest.Fixture();
        fixture.user(1, "000000", 1, 0);
        fixture.user(2, "000000", 1, 0);
        fixture.delivery.deliverOne(fixture.request("000000", 1, "sample.created", "for-a", "A"));
        fixture.delivery.deliverOne(fixture.request("000000", 2, "sample.created", "for-b", "B"));
        long aId = fixture.jdbc.queryForObject("SELECT notification_id FROM sys_notification_inbox "
                + "WHERE recipient_user_id=1", Long.class);
        long bId = fixture.jdbc.queryForObject("SELECT notification_id FROM sys_notification_inbox "
                + "WHERE recipient_user_id=2", Long.class);

        login(1, "000000");
        var inbox = new NotificationInboxService(fixture.jdbc, false);
        assertEquals(1, inbox.unreadCount());
        assertEquals(1, inbox.page(1, 20, true).total());
        assertEquals("A", inbox.detail(aId).title());
        assertThrows(ServiceException.class, () -> inbox.detail(bId));
        assertThrows(ServiceException.class, () -> inbox.markRead(bId));
        inbox.markRead(aId);
        inbox.markRead(aId);
        assertEquals(0, new NotificationInboxService(fixture.jdbc, false).unreadCount());
        assertNotNull(inbox.detail(aId).readTime());
        assertThrows(ServiceException.class, () -> inbox.page(1, 101, false));
    }

    @Test
    void batchRejectsForeignIdWithoutPartiallyMarkingAndTenantSessionMustMatch() {
        var fixture = new NotificationDeliveryServiceTest.Fixture();
        fixture.user(1, "000000", 1, 0);
        fixture.user(2, "000000", 1, 0);
        fixture.delivery.deliverOne(fixture.request("000000", 1, "sample.created", "own", "A"));
        fixture.delivery.deliverOne(fixture.request("000000", 2, "sample.created", "foreign", "B"));
        long own = fixture.jdbc.queryForObject("SELECT notification_id FROM sys_notification_inbox "
                + "WHERE recipient_user_id=1", Long.class);
        long foreign = fixture.jdbc.queryForObject("SELECT notification_id FROM sys_notification_inbox "
                + "WHERE recipient_user_id=2", Long.class);

        login(1, "000000");
        var inbox = new NotificationInboxService(fixture.jdbc, false);
        assertThrows(ServiceException.class, () -> inbox.markReadBatch(List.of(own, foreign)));
        assertEquals(1, inbox.unreadCount());
        assertEquals(1, inbox.markReadBatch(List.of(own, own)));
        assertEquals(1, inbox.markReadBatch(List.of(own, own)));
        assertEquals(0, inbox.unreadCount());

        login(1, "000001");
        assertThrows(AccessDeniedException.class,
                () -> new NotificationInboxService(fixture.jdbc, true).page(1, 20, false));
    }

    @Test
    void enabledTenantReceivesAndReadsOnlyItsOwnPartition() {
        var fixture = new NotificationDeliveryServiceTest.Fixture();
        fixture.user(10, "000001", 1, 0);
        fixture.user(11, "000002", 1, 0);
        var delivery = new NotificationDeliveryService(fixture.jdbc,
                new DataSourceTransactionManager(fixture.source), true,
                new NotificationContentRules(""));
        delivery.deliverOne(fixture.request("000001", 10, "sample.created", "tenant-one", "One"));
        delivery.deliverOne(fixture.request("000002", 11, "sample.created", "tenant-two", "Two"));
        long otherId = fixture.jdbc.queryForObject("SELECT notification_id FROM sys_notification_inbox "
                + "WHERE recipient_user_id=11", Long.class);

        login(10, "000001", "000001");
        var inbox = new NotificationInboxService(fixture.jdbc, true);
        assertEquals(1, inbox.page(1, 20, false).total());
        assertEquals(1, inbox.unreadCount());
        assertThrows(ServiceException.class, () -> inbox.detail(otherId));
    }

    private static void login(long id, String tenantId) {
        login(id, "000000", tenantId);
    }

    private static void login(long id, String userTenantId, String sessionTenantId) {
        SysUser user = new SysUser();
        user.setUserId(id);
        user.setTenantId(userTenantId);
        LoginUser principal = new LoginUser(user, List.of());
        principal.setTenantId(sessionTenantId);
        SecurityContextHolder.getContext().setAuthentication(
                new UsernamePasswordAuthenticationToken(principal, null, principal.getAuthorities()));
    }
}
