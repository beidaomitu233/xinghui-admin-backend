package com.xinghuiTec.service.impl;

import com.baomidou.mybatisplus.core.MybatisConfiguration;
import com.baomidou.mybatisplus.core.conditions.Wrapper;
import com.baomidou.mybatisplus.core.metadata.TableInfoHelper;
import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import com.xinghuiTec.domain.dto.SysRoleAddDTO;
import com.xinghuiTec.domain.dto.SysRoleQueryDTO;
import com.xinghuiTec.domain.entity.SysRole;
import com.xinghuiTec.mapper.SysUserRoleMapper;
import com.xinghuiTec.utils.RedisCacheUtils;
import jakarta.validation.Validation;
import org.apache.ibatis.builder.MapperBuilderAssistant;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.test.util.ReflectionTestUtils;

import java.util.List;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.doReturn;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.spy;
import static org.mockito.Mockito.when;

class SysRoleOrderingTest {
    @BeforeAll
    static void initializeColumns() {
        TableInfoHelper.initTableInfo(new MapperBuilderAssistant(new MybatisConfiguration(), "role-ordering-test"), SysRole.class);
    }

    @ParameterizedTest
    @CsvSource({
        "roleName,role_name", "roleKey,role_key", "status,status",
        "createTime,create_time", "updateTime,update_time"
    })
    void appliesWhitelistedOrderingWithStableRoleId(String field, String column) {
        for (String direction : new String[] { "asc", "desc" }) {
            SysRoleServiceImpl service = spy(new SysRoleServiceImpl());
            SysRoleQueryDTO query = new SysRoleQueryDTO();
            query.setPageNum(2);
            query.setPageSize(10);
            query.setRoleName("运营");
            query.setOrderBy(field);
            query.setOrder(direction);
            doAnswer(invocation -> {
                Page<SysRole> page = invocation.getArgument(0);
                Wrapper<SysRole> wrapper = invocation.getArgument(1);
                assertEquals(2, page.getCurrent());
                assertEquals(10, page.getSize());
                String sql = wrapper.getSqlSegment();
                assertTrue(sql.contains("role_name LIKE"), sql);
                assertTrue(sql.endsWith("ORDER BY " + column + " " + direction.toUpperCase() + ",role_id ASC"), sql);
                return page;
            }).when(service).page(any(Page.class), any());
            service.getRoleList(query);
        }
    }

    @Test
    void fallsBackToCreateTimeAndDefaultDirection() {
        SysRoleServiceImpl service = spy(new SysRoleServiceImpl());
        SysRoleQueryDTO query = new SysRoleQueryDTO();
        doAnswer(invocation -> {
            Wrapper<SysRole> wrapper = invocation.getArgument(1);
            assertTrue(wrapper.getSqlSegment().endsWith("ORDER BY create_time DESC,role_id ASC"), wrapper.getSqlSegment());
            return invocation.getArgument(0);
        }).when(service).page(any(Page.class), any());
        service.getRoleList(query);
    }

    @ParameterizedTest
    @ValueSource(strings = { "dataScope", "role_id desc", "roleName;drop table sys_role", "unknown" })
    void rejectsUnapprovedFieldsAtValidationAndServiceBoundary(String field) {
        SysRoleQueryDTO query = new SysRoleQueryDTO();
        query.setOrderBy(field);
        try (var factory = Validation.buildDefaultValidatorFactory()) {
            assertFalse(factory.getValidator().validate(query).isEmpty());
        }
        SysRoleQueryDTO serviceQuery = new SysRoleQueryDTO();
        serviceQuery.setOrderBy(field);
        assertThrows(IllegalArgumentException.class, () -> new SysRoleServiceImpl().getRoleList(serviceQuery));
    }

    @Test
    void keepsExistingDataScopeWhenUpdateOmitsIt() {
        SysRoleServiceImpl service = spy(new SysRoleServiceImpl());
        SysRole existing = new SysRole();
        existing.setRoleId(5L);
        existing.setRoleName("旧名称");
        existing.setRoleKey("legacy_key");
        existing.setDataScope("2");
        existing.setStatus(0);
        doReturn(existing).when(service).getById(5L);
        doReturn(true).when(service).checkRoleNameUnique(any(), anyLong());
        doReturn(true).when(service).checkRoleKeyUnique(any(), anyLong());
        var userRoleMapper = mock(SysUserRoleMapper.class);
        when(userRoleMapper.selectList(any())).thenReturn(List.of());
        ReflectionTestUtils.setField(service, "sysUserRoleMapper", userRoleMapper);
        ReflectionTestUtils.setField(service, "redisCacheUtils", mock(RedisCacheUtils.class));
        AtomicReference<SysRole> saved = new AtomicReference<>();
        doAnswer(invocation -> {
            saved.set(invocation.getArgument(0));
            return true;
        }).when(service).updateById(any(SysRole.class));

        SysRoleAddDTO update = new SysRoleAddDTO();
        update.setRoleId(5L);
        update.setRoleName("新名称");
        update.setRoleKey("new_key");
        update.setStatus(1);
        service.updateRole(update);

        assertEquals("2", saved.get().getDataScope());
        assertEquals("新名称", saved.get().getRoleName());
        assertEquals("new_key", saved.get().getRoleKey());
        assertEquals(1, saved.get().getStatus());
    }
}
