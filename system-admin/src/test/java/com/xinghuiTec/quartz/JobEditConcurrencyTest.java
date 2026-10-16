package com.xinghuiTec.quartz;

import com.baomidou.mybatisplus.core.conditions.Wrapper;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.xinghuiTec.exception.ServiceException;
import com.xinghuiTec.quartz.domain.JobChangeRequest;
import com.xinghuiTec.quartz.domain.SysJob;
import com.xinghuiTec.quartz.handler.JobHandlerRegistry;
import com.xinghuiTec.quartz.mapper.SysJobMapper;
import com.xinghuiTec.quartz.service.impl.SysJobServiceImpl;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DataSourceTransactionManager;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import org.springframework.test.util.ReflectionTestUtils;
import org.springframework.transaction.support.TransactionTemplate;

import java.time.LocalDateTime;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;

class JobEditConcurrencyTest {
    @Test
    void editWaitsForPauseCommitAndCannotRestoreEnabledStatus() throws Exception {
        var dataSource = new DriverManagerDataSource();
        dataSource.setDriverClassName("org.h2.Driver");
        dataSource.setUrl("jdbc:h2:mem:job_edit_race;MODE=MySQL;DB_CLOSE_DELAY=-1;LOCK_TIMEOUT=5000");
        dataSource.setUsername("sa");
        var jdbc = new JdbcTemplate(dataSource);
        jdbc.execute("DROP TABLE IF EXISTS sys_job");
        jdbc.execute("CREATE TABLE sys_job (job_id BIGINT PRIMARY KEY, tenant_id VARCHAR(20), "
                + "status CHAR(1), revision BIGINT, next_fire_time DATETIME(3))");
        jdbc.update("INSERT INTO sys_job VALUES (1, '000000', '0', 0, ?)", LocalDateTime.now().plusMinutes(1));

        SysJobMapper mapper = mock(SysJobMapper.class);
        when(mapper.selectOne(any(Wrapper.class))).thenAnswer(invocation -> {
            return jdbc.queryForObject(
                "SELECT job_id, tenant_id, status, revision, next_fire_time FROM sys_job WHERE job_id=1 FOR UPDATE",
                (rs, row) -> {
                    var job = new SysJob();
                    job.setJobId(rs.getLong(1));
                    job.setTenantId(rs.getString(2));
                    job.setStatus(rs.getString(3));
                    job.setRevision(rs.getLong(4));
                    job.setNextFireTime(rs.getObject(5, LocalDateTime.class));
                    return job;
                });
        });
        when(mapper.updateById(any(SysJob.class))).thenAnswer(invocation -> {
            SysJob job = invocation.getArgument(0);
            return jdbc.update("UPDATE sys_job SET status=?, revision=?, next_fire_time=? WHERE job_id=?",
                    job.getStatus(), job.getRevision(), job.getNextFireTime(), job.getJobId());
        });
        var service = new SysJobServiceImpl(new JobHandlerRegistry(List.of()), new ObjectMapper());
        ReflectionTestUtils.setField(service, "baseMapper", mapper);
        var tx = new TransactionTemplate(new DataSourceTransactionManager(dataSource));
        var pauseWritten = new CountDownLatch(1);
        var releasePause = new CountDownLatch(1);
        var pool = Executors.newFixedThreadPool(2);
        try {
            var pause = pool.submit(() -> tx.execute(status -> {
                service.changeStatusManaged(1L, "1");
                pauseWritten.countDown();
                try {
                    if (!releasePause.await(15, TimeUnit.SECONDS)) throw new AssertionError("pause hold timed out");
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                    throw new AssertionError(e);
                }
                return null;
            }));
            assertTrue(pauseWritten.await(15, TimeUnit.SECONDS));
            var staleEdit = new JobChangeRequest(1L, "name", "DEFAULT", "system.noop", "{}",
                    "0 0 2 * * ?", "Asia/Shanghai", "3", "1", "0", null);
            var edit = pool.submit(() -> assertThrows(ServiceException.class,
                    () -> tx.execute(status -> { service.updateManaged(staleEdit); return null; })));
            Thread.sleep(200);
            assertFalse(edit.isDone(), "edit must wait for the pause transaction's row lock");
            releasePause.countDown();
            pause.get(15, TimeUnit.SECONDS);
            assertTrue(edit.get(15, TimeUnit.SECONDS).getMessage().contains("status"));
            assertEquals("1", jdbc.queryForObject("SELECT status FROM sys_job WHERE job_id=1", String.class));
            assertNull(jdbc.queryForObject("SELECT next_fire_time FROM sys_job WHERE job_id=1", LocalDateTime.class));
            assertEquals(1L, jdbc.queryForObject("SELECT revision FROM sys_job WHERE job_id=1", Long.class));
        } finally {
            releasePause.countDown();
            pool.shutdownNow();
        }
    }
}
