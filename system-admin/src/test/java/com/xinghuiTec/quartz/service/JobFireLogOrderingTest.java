package com.xinghuiTec.quartz.service;

import com.xinghuiTec.exception.ServiceException;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.RowMapper;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class JobFireLogOrderingTest {

    @Test
    void buildsWhitelistedOrderClauseWithStableFireIdTail() {
        var expectations = List.of(
            new String[] { "jobName", "asc", "ORDER BY COALESCE(f.job_name_snapshot,j.job_name) ASC,f.fire_id DESC" },
            new String[] { "status", "desc", "ORDER BY f.status DESC,f.fire_id DESC" },
            new String[] { "attemptCount", "asc", "ORDER BY f.attempt_count ASC,f.fire_id DESC" },
            new String[] { "startedAt", "desc", "ORDER BY f.started_at DESC,f.fire_id DESC" },
            new String[] { "completedAt", "asc", "ORDER BY f.completed_at ASC,f.fire_id DESC" },
            new String[] { "scheduledFireTime", "asc", "ORDER BY f.scheduled_fire_time ASC,f.fire_id DESC" },
            new String[] { "triggerType", "desc", "ORDER BY f.trigger_type DESC,f.fire_id DESC" },
            new String[] { "jobGroup", "asc", "ORDER BY COALESCE(f.job_group_snapshot,j.job_group) ASC,f.fire_id DESC" },
            new String[] { "fireId", "asc", "ORDER BY f.fire_id ASC" },
            new String[] { null, null, "ORDER BY f.fire_id DESC" }
        );
        for (String[] expectation : expectations) {
            JdbcTemplate jdbc = mock(JdbcTemplate.class);
            when(jdbc.queryForObject(any(String.class), any(Class.class), any(Object[].class))).thenReturn(0L);
            when(jdbc.query(any(String.class), any(RowMapper.class), any(Object[].class))).thenReturn(List.of());
            new JobFireLogService(jdbc).list(1, 10, null, null, null, null, expectation[0], expectation[1]);
            ArgumentCaptor<String> sqlCaptor = ArgumentCaptor.forClass(String.class);
            verify(jdbc).query(sqlCaptor.capture(), any(RowMapper.class), any(Object[].class));
            String sql = sqlCaptor.getValue();
            assertTrue(sql.contains(expectation[2] + " LIMIT ? OFFSET ?"), sql);
        }
    }

    @Test
    void rejectsUnknownOrderFieldWithoutTouchingSql() {
        JdbcTemplate jdbc = mock(JdbcTemplate.class);
        JobFireLogService service = new JobFireLogService(jdbc);
        assertThrows(ServiceException.class, () -> service.list(1, 10, null, null, null, null, "fireId;drop table sys_job_fire", "asc"));
        assertThrows(ServiceException.class, () -> service.list(1, 10, null, null, null, null, "tenantId", "asc"));
        verify(jdbc, org.mockito.Mockito.never()).query(any(String.class), any(RowMapper.class), any(Object[].class));
    }
}
