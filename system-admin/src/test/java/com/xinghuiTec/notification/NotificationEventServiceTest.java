package com.xinghuiTec.notification;

import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.PreparedStatementCreator;
import org.springframework.jdbc.support.KeyHolder;

import java.util.List;
import java.util.Map;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

class NotificationEventServiceTest {
    @Test
    void recordsTwoBusinessEventsAsPendingWithoutClaimingDelivery() {
        JdbcTemplate jdbc = mock(JdbcTemplate.class);
        NotificationTemplateService templates = mock(NotificationTemplateService.class);
        when(templates.rules()).thenReturn(new NotificationTemplateRules(Set.of()));
        when(templates.enabledByCode("sample_a.created")).thenReturn(
                new NotificationTemplateService.View(1, "sample_a.created", "A", "创建 {name}",
                        "正文", "/a/{name}", List.of("name"), true));
        when(templates.enabledByCode("sample_b.reviewed")).thenReturn(
                new NotificationTemplateService.View(2, "sample_b.reviewed", "B", "审核 {state}",
                        "正文", "/b/{state}", List.of("state"), true));
        doAnswer(invocation -> {
            KeyHolder holder = invocation.getArgument(1);
            holder.getKeyList().add(Map.of("request_id", (long) holder.getKeyList().size() + 1));
            return 1;
        }).when(jdbc).update(any(PreparedStatementCreator.class), any(KeyHolder.class));

        NotificationEventService service = new NotificationEventService(jdbc, templates);
        var first = service.submit(new NotificationEventPublisher.Event(
                "000001", 101, "sample_a.created", "a-1", Map.of("name", "first")));
        var second = service.submit(new NotificationEventPublisher.Event(
                "000002", 202, "sample_b.reviewed", "b-1", Map.of("state", "ready")));
        assertEquals("PENDING", first.status());
        assertEquals("PENDING", second.status());
        verify(jdbc, times(2)).update(any(PreparedStatementCreator.class), any(KeyHolder.class));
    }

    @Test
    void missingVariableIsRejectedAndOnlyFailureCodeIsRecorded() {
        JdbcTemplate jdbc = mock(JdbcTemplate.class);
        NotificationTemplateService templates = mock(NotificationTemplateService.class);
        when(templates.rules()).thenReturn(new NotificationTemplateRules(Set.of()));
        when(templates.enabledByCode("sample_a.created")).thenReturn(
                new NotificationTemplateService.View(1, "sample_a.created", "A", "创建 {name}",
                        "正文", null, List.of("name"), true));
        NotificationEventService service = new NotificationEventService(jdbc, templates);
        var event = new NotificationEventPublisher.Event(
                "000001", 101, "sample_a.created", "a-2", Map.of());

        NotificationContractException failure = assertThrows(NotificationContractException.class,
                () -> service.submit(event));
        assertEquals("VARIABLE_MISSING", failure.code());
        verify(jdbc).update(contains("INSERT INTO sys_notification_event_request"),
                eq("000001"), eq(101L), eq("sample_a.created"), eq("a-2"),
                eq("REJECTED"), eq("VARIABLE_MISSING"));
        verify(jdbc, never()).update(any(PreparedStatementCreator.class), any(KeyHolder.class));
    }

    @Test
    void failedDeliveryMarkerAppliesOnlyToPendingRequest() {
        JdbcTemplate jdbc = mock(JdbcTemplate.class);
        NotificationEventService service = new NotificationEventService(jdbc,
                mock(NotificationTemplateService.class));
        when(jdbc.update(contains("SET status='FAILED'"), eq("WORKER_ERROR"), eq(5L))).thenReturn(1);
        assertDoesNotThrow(() -> service.markFailed(5, "WORKER_ERROR"));
        assertThrows(IllegalArgumentException.class, () -> service.markFailed(5, "raw error detail"));
    }
}
