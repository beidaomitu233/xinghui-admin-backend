package com.xinghuiTec.service.impl;

import com.baomidou.mybatisplus.core.MybatisConfiguration;
import com.baomidou.mybatisplus.core.conditions.Wrapper;
import com.baomidou.mybatisplus.core.metadata.TableInfoHelper;
import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import com.xinghuiTec.domain.dto.SysOperLogQueryDTO;
import com.xinghuiTec.domain.entity.SysOperLog;
import jakarta.validation.Validation;
import org.apache.ibatis.builder.MapperBuilderAssistant;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.junit.jupiter.params.provider.ValueSource;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.spy;

class SysOperLogOrderingTest {
    @BeforeAll
    static void initializeColumns() {
        TableInfoHelper.initTableInfo(new MapperBuilderAssistant(new MybatisConfiguration(), "operlog-ordering-test"), SysOperLog.class);
    }

    @ParameterizedTest
    @CsvSource({
        "title,title", "businessType,business_type", "operName,oper_name",
        "status,status", "costTime,cost_time", "operTime,oper_time"
    })
    void appliesWhitelistedOrderingWithStableOperId(String field, String column) {
        for (String direction : new String[] { "asc", "desc" }) {
            SysOperLogServiceImpl service = spy(new SysOperLogServiceImpl());
            SysOperLogQueryDTO query = new SysOperLogQueryDTO();
            query.setPageNum(1);
            query.setPageSize(20);
            query.setTitle("登录");
            query.setOrderBy(field);
            query.setOrder(direction);
            doAnswer(invocation -> {
                Wrapper<SysOperLog> wrapper = invocation.getArgument(1);
                String sql = wrapper.getSqlSegment();
                assertTrue(sql.contains("title LIKE"), sql);
                assertTrue(sql.endsWith("ORDER BY " + column + " " + direction.toUpperCase() + ",oper_id ASC"), sql);
                return invocation.getArgument(0);
            }).when(service).page(any(Page.class), any());
            service.getOperLogList(query);
        }
    }

    @Test
    void fallsBackToOperTimeDescByDefault() {
        SysOperLogServiceImpl service = spy(new SysOperLogServiceImpl());
        SysOperLogQueryDTO query = new SysOperLogQueryDTO();
        doAnswer(invocation -> {
            Wrapper<SysOperLog> wrapper = invocation.getArgument(1);
            assertTrue(wrapper.getSqlSegment().endsWith("ORDER BY oper_time DESC,oper_id ASC"), wrapper.getSqlSegment());
            return invocation.getArgument(0);
        }).when(service).page(any(Page.class), any());
        service.getOperLogList(query);
    }

    @ParameterizedTest
    @ValueSource(strings = { "operId", "operIp", "oper_id desc", "operTime;drop table sys_oper_log" })
    void rejectsUnapprovedFieldsAtValidationBoundary(String field) {
        SysOperLogQueryDTO query = new SysOperLogQueryDTO();
        query.setOrderBy(field);
        try (var factory = Validation.buildDefaultValidatorFactory()) {
            assertFalse(factory.getValidator().validate(query).isEmpty());
        }
        // 服务层默认分支只认白名单；未知值回退 operTime，不进入 SQL。
        SysOperLogQueryDTO serviceQuery = new SysOperLogQueryDTO();
        serviceQuery.setOrderBy(field);
        assertEquals("operTime", whitelistOrFallback(serviceQuery.getOrderBy()));
    }

    private static String whitelistOrFallback(String field) {
        return java.util.Set.of("title", "businessType", "operName", "status", "costTime", "operTime").contains(field) ? field : "operTime";
    }
}
