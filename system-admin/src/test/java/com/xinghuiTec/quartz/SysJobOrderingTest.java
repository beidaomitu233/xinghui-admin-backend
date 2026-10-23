package com.xinghuiTec.quartz;

import com.baomidou.mybatisplus.core.MybatisConfiguration;
import com.baomidou.mybatisplus.core.conditions.Wrapper;
import com.baomidou.mybatisplus.core.metadata.TableInfoHelper;
import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import com.xinghuiTec.quartz.controller.SysJobController;
import com.xinghuiTec.quartz.domain.SysJob;
import com.xinghuiTec.quartz.service.JobEngine;
import com.xinghuiTec.quartz.service.SysJobService;
import jakarta.validation.constraints.Pattern;
import org.apache.ibatis.builder.MapperBuilderAssistant;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.mockito.ArgumentCaptor;
import org.springframework.test.util.ReflectionTestUtils;

import java.lang.reflect.Method;
import java.lang.reflect.Parameter;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;

class SysJobOrderingTest {
    @BeforeAll
    static void initializeColumns() {
        TableInfoHelper.initTableInfo(new MapperBuilderAssistant(new MybatisConfiguration(), "job-ordering-test"), SysJob.class);
    }

    private static SysJobController controller(SysJobService service) {
        SysJobController controller = new SysJobController();
        ReflectionTestUtils.setField(controller, "sysJobService", service);
        ReflectionTestUtils.setField(controller, "jobEngine", mock(JobEngine.class));
        return controller;
    }

    @ParameterizedTest
    @CsvSource({
        "jobName,job_name", "jobGroup,job_group", "status,status",
        "nextFireTime,next_fire_time", "createTime,create_time", "updateTime,update_time"
    })
    @SuppressWarnings("unchecked")
    void appliesWhitelistedOrderingWithStableJobId(String field, String column) {
        for (String direction : new String[] { "asc", "desc" }) {
            SysJobService service = mock(SysJobService.class);
            doAnswer(invocation -> invocation.getArgument(0))
                    .when(service).page(any(Page.class), any(Wrapper.class));
            controller(service).list(new SysJob(), 2, 10, field, direction);
            ArgumentCaptor<Wrapper<SysJob>> wrapperCaptor = ArgumentCaptor.forClass(Wrapper.class);
            var pageCaptor = ArgumentCaptor.forClass(Page.class);
            verify(service).page(pageCaptor.capture(), wrapperCaptor.capture());
            assertEquals(2, pageCaptor.getValue().getCurrent());
            String sql = wrapperCaptor.getValue().getSqlSegment();
            assertTrue(sql.endsWith("ORDER BY " + column + " " + direction.toUpperCase() + ",job_id ASC"), sql);
        }
    }

    @Test
    void orderByAndOrderCarryValidationContracts() throws Exception {
        Method list = SysJobController.class.getMethod("list", SysJob.class, Integer.class, Integer.class, String.class, String.class);
        Parameter[] parameters = list.getParameters();
        Pattern orderByPattern = parameters[3].getAnnotation(Pattern.class);
        Pattern orderPattern = parameters[4].getAnnotation(Pattern.class);
        assertNotNull(orderByPattern);
        assertNotNull(orderPattern);
        assertTrue(orderByPattern.regexp().contains("jobName"));
        assertTrue(orderByPattern.regexp().contains("nextFireTime"));
        assertTrue(!orderByPattern.regexp().contains("handlerKey"));
        assertEquals("^(asc|desc)$", orderPattern.regexp());
    }
}
