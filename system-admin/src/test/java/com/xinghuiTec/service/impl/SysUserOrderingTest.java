package com.xinghuiTec.service.impl;

import com.baomidou.mybatisplus.core.MybatisConfiguration;
import com.baomidou.mybatisplus.core.conditions.Wrapper;
import com.baomidou.mybatisplus.core.metadata.TableInfoHelper;
import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import com.xinghuiTec.domain.dto.SysUserQueryDTO;
import com.xinghuiTec.domain.entity.SysUser;
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

class SysUserOrderingTest {
    @BeforeAll
    static void initializeColumns() {
        TableInfoHelper.initTableInfo(new MapperBuilderAssistant(new MybatisConfiguration(), "user-ordering-test"), SysUser.class);
    }

    @ParameterizedTest
    @CsvSource({
        "username,username", "nickname,nickname", "mobile,mobile", "email,email",
        "status,status", "createTime,create_time", "updateTime,update_time"
    })
    void appliesWhitelistedOrderingBeforePagingWithStableId(String field, String column) {
        for (String direction : new String[] { "asc", "desc" }) {
            SysUserServiceImpl service = spy(new SysUserServiceImpl());
            SysUserQueryDTO query = new SysUserQueryDTO();
            query.setPageNum(3);
            query.setPageSize(10);
            query.setUsername("operator");
            query.setOrderBy(field);
            query.setOrder(direction);
            doAnswer(invocation -> {
                Page<SysUser> page = invocation.getArgument(0);
                Wrapper<SysUser> wrapper = invocation.getArgument(1);
                assertEquals(3, page.getCurrent());
                assertEquals(10, page.getSize());
                String sql = wrapper.getSqlSegment();
                assertTrue(sql.contains("username LIKE"), sql);
                assertTrue(sql.endsWith("ORDER BY " + column + " " + direction.toUpperCase() + ",user_id ASC"), sql);
                return page;
            }).when(service).page(any(Page.class), any());
            service.getUserList(query);
        }
    }

    @ParameterizedTest
    @ValueSource(strings = { "password", "user_id desc", "username;drop table sys_user", "unknown" })
    void rejectsUnapprovedFieldsAtValidationAndServiceBoundary(String field) {
        SysUserQueryDTO query = new SysUserQueryDTO();
        query.setOrderBy(field);
        try (var factory = Validation.buildDefaultValidatorFactory()) {
            assertFalse(factory.getValidator().validate(query).isEmpty());
        }
        assertThrows(IllegalArgumentException.class, () -> new SysUserServiceImpl().getUserList(query));
    }

    @Test
    void rejectsUnsupportedDirectionAndAcceptsExistingDefaults() {
        try (var factory = Validation.buildDefaultValidatorFactory()) {
            var validator = factory.getValidator();
            SysUserQueryDTO query = new SysUserQueryDTO();
            assertTrue(validator.validate(query).isEmpty());
            query.setOrder("ascending");
            assertFalse(validator.validate(query).isEmpty());
        }
    }
}
