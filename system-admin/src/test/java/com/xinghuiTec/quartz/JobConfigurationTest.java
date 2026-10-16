package com.xinghuiTec.quartz;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.xinghuiTec.exception.ServiceException;
import com.xinghuiTec.quartz.domain.JobChangeRequest;
import com.xinghuiTec.quartz.domain.SysJob;
import com.xinghuiTec.quartz.handler.JobHandler;
import com.xinghuiTec.quartz.handler.JobHandlerRegistry;
import com.xinghuiTec.quartz.mapper.SysJobMapper;
import com.xinghuiTec.quartz.service.impl.SysJobServiceImpl;
import org.junit.jupiter.api.Test;
import org.springframework.context.annotation.AnnotationConfigApplicationContext;
import org.springframework.test.util.ReflectionTestUtils;

import java.util.List;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;

class JobConfigurationTest {
    private final ObjectMapper json = new ObjectMapper();

    @Test
    void rejectsUnregisteredHandlerAndInvalidFieldsBeforeWriting() {
        SysJobServiceImpl service = spy(new SysJobServiceImpl(new JobHandlerRegistry(List.of()), json));
        assertFieldError(service, request("arbitrary.Bean.method", "0 0 2 * * ?", "Asia/Shanghai", "{}"),
                "handlerKey");
        assertFieldError(service, request("safe.ping", "bad cron", "Asia/Shanghai", "{}"),
                "cronExpression");
        assertFieldError(service, request("safe.ping", "0 0 2 * * ?", "GMT+8", "{}"),
                "timeZone");
        assertFieldError(service, request("safe.ping", "0 0 2 * * ?", "Asia/Shanghai", "[]"),
                "handlerParams");
        JobChangeRequest invalidMisfire = new JobChangeRequest(null, "name", "DEFAULT", "safe.ping", "{}",
                "0 0 2 * * ?", "Asia/Shanghai", "1", "1", "1", null);
        assertFieldError(service, invalidMisfire, "misfirePolicy");
        verify(service, never()).save(any(SysJob.class));
    }

    @Test
    void registeredHandlerCanSaveOnlyManagedFieldsWithPlatformOwnership() {
        JobHandler handler = mock(JobHandler.class);
        when(handler.key()).thenReturn("safe.ping");
        SysJobServiceImpl service = spy(new SysJobServiceImpl(new JobHandlerRegistry(List.of(handler)), json));
        doAnswer(invocation -> {
            SysJob row = invocation.getArgument(0);
            row.setJobId(41L);
            return true;
        }).when(service).save(any(SysJob.class));

        assertEquals(41L, service.addManaged(request("safe.ping", "0 0 2 * * ?", "Asia/Shanghai", "{}")));
        var saved = org.mockito.ArgumentCaptor.forClass(SysJob.class);
        verify(service).save(saved.capture());
        assertEquals("000000", saved.getValue().getTenantId());
        assertNull(saved.getValue().getInvokeTarget());
        assertEquals("safe.ping", saved.getValue().getHandlerKey());
        assertNull(saved.getValue().getNextFireTime());
        assertEquals(0L, saved.getValue().getRevision());
        verify(handler).validateParameters(any(JsonNode.class));
        verify(handler, never()).execute(any(JsonNode.class));
    }

    @Test
    void legacyRowRequiresExplicitRegisteredPromotionAndRetainsOldTarget() {
        JobHandler handler = mock(JobHandler.class);
        when(handler.key()).thenReturn("safe.ping");
        SysJobServiceImpl service = spy(new SysJobServiceImpl(new JobHandlerRegistry(List.of(handler)), json));
        SysJob legacy = new SysJob();
        legacy.setJobId(7L);
        legacy.setTenantId("000000");
        legacy.setInvokeTarget("dangerous.Bean.method");
        legacy.setStatus("1");
        doReturn(legacy).when(service).getManagedForUpdate(7L);
        doReturn(true).when(service).updateById(any(SysJob.class));

        JobChangeRequest bad = new JobChangeRequest(7L, "name", "DEFAULT", "dangerous.Bean.method", "{}",
                "0 0 2 * * ?", "Asia/Shanghai", "3", "1", "1", null);
        assertFieldErrorForUpdate(service, bad, "handlerKey");
        verify(service, never()).updateById(any(SysJob.class));

        JobChangeRequest good = new JobChangeRequest(7L, "name", "DEFAULT", "safe.ping", "{}",
                "0 0 2 * * ?", "Asia/Shanghai", "3", "1", "1", null);
        service.updateManaged(good);
        var updated = org.mockito.ArgumentCaptor.forClass(SysJob.class);
        verify(service).updateById(updated.capture());
        assertNull(updated.getValue().getInvokeTarget());
        assertEquals("safe.ping", updated.getValue().getHandlerKey());
        assertNull(updated.getValue().getNextFireTime());
        assertEquals(1L, updated.getValue().getRevision());
        assertEquals("dangerous.Bean.method", legacy.getInvokeTarget());

        JobChangeRequest bypass = new JobChangeRequest(7L, "name", "DEFAULT", "safe.ping", "{}",
                "0 0 2 * * ?", "Asia/Shanghai", "3", "1", "0", null);
        assertFieldErrorForUpdate(service, bypass, "status");
    }

    @Test
    void pauseClearsPlanAndResumeCreatesFuturePlan() {
        JobHandler handler = mock(JobHandler.class);
        when(handler.key()).thenReturn("safe.ping");
        SysJobServiceImpl service = spy(new SysJobServiceImpl(new JobHandlerRegistry(List.of(handler)), json));
        SysJob existing = new SysJob();
        existing.setJobId(9L);
        existing.setTenantId("000000");
        existing.setHandlerKey("safe.ping");
        existing.setCronExpression("0 0 2 * * ?");
        existing.setTimeZone("Asia/Shanghai");
        existing.setStatus("0");
        existing.setRevision(3L);
        doReturn(existing).when(service).getManagedForUpdate(9L);
        doReturn(true).when(service).updateById(any(SysJob.class));

        service.changeStatusManaged(9L, "1");
        var changes = org.mockito.ArgumentCaptor.forClass(SysJob.class);
        verify(service).updateById(changes.capture());
        assertNull(changes.getValue().getNextFireTime());
        assertEquals(4L, changes.getValue().getRevision());

        existing.setStatus("1");
        clearInvocations(service);
        service.changeStatusManaged(9L, "0");
        verify(service).updateById(changes.capture());
        assertNotNull(changes.getValue().getNextFireTime());
    }

    @Test
    void editCannotReversePauseCommittedBeforeRowLock() {
        JobHandler handler = mock(JobHandler.class);
        when(handler.key()).thenReturn("safe.ping");
        SysJobServiceImpl service = spy(new SysJobServiceImpl(new JobHandlerRegistry(List.of(handler)), json));
        SysJobMapper mapper = mock(SysJobMapper.class);
        ReflectionTestUtils.setField(service, "baseMapper", mapper);
        SysJob paused = new SysJob();
        paused.setJobId(9L);
        paused.setTenantId("000000");
        paused.setStatus("1");
        paused.setRevision(4L);
        when(mapper.selectOne(any(com.baomidou.mybatisplus.core.conditions.Wrapper.class))).thenReturn(paused);

        JobChangeRequest staleEdit = new JobChangeRequest(9L, "name", "DEFAULT", "safe.ping", "{}",
                "0 0 2 * * ?", "Asia/Shanghai", "3", "1", "0", null);
        assertFieldErrorForUpdate(service, staleEdit, "status");
        verify(mapper, never()).updateById(any(SysJob.class));
        verify(service, never()).getById(9L);
    }

    @Test
    void duplicateRegistryKeysAreRejectedAtStartup() {
        JobHandler first = mock(JobHandler.class);
        JobHandler second = mock(JobHandler.class);
        when(first.key()).thenReturn("safe.ping");
        when(second.key()).thenReturn("safe.ping");
        assertThrows(IllegalStateException.class, () -> new JobHandlerRegistry(List.of(first, second)));
    }

    @Test
    void applicationCanStartBeforeAnyBusinessHandlerIsRegistered() {
        try (var context = new AnnotationConfigApplicationContext()) {
            context.registerBean(JobHandlerRegistry.class);
            context.refresh();
            assertThrows(ServiceException.class,
                    () -> context.getBean(JobHandlerRegistry.class).require("safe.ping"));
        }
    }

    @Test
    void legacyInvokeTargetIsNotReturnedByJobApiSerialization() throws Exception {
        SysJob legacy = new SysJob();
        legacy.setJobId(1L);
        legacy.setInvokeTarget("danger.Bean.method");
        assertFalse(json.writeValueAsString(legacy).contains("invokeTarget"));
        assertFalse(json.writeValueAsString(legacy).contains("danger.Bean.method"));
    }

    private JobChangeRequest request(String key, String cron, String zone, String params) {
        return new JobChangeRequest(null, "name", "DEFAULT", key, params, cron, zone, "3", "1", "1", null);
    }

    private void assertFieldError(SysJobServiceImpl service, JobChangeRequest request, String field) {
        assertTrue(assertThrows(ServiceException.class, () -> service.addManaged(request)).getMessage().contains(field));
    }

    private void assertFieldErrorForUpdate(SysJobServiceImpl service, JobChangeRequest request, String field) {
        assertTrue(assertThrows(ServiceException.class, () -> service.updateManaged(request)).getMessage().contains(field));
    }
}
