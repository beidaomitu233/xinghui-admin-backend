package com.xinghuiTec.service.impl;

import com.xinghuiTec.domain.entity.SysRole;
import com.xinghuiTec.domain.entity.SysUserRole;
import com.xinghuiTec.service.SysRoleService;
import com.xinghuiTec.service.SysUserRoleService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.test.util.ReflectionTestUtils;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class SysUserRoleValidationTest {
    @Mock private SysRoleService roleService;
    @Mock private SysUserRoleService userRoleService;
    private SysUserServiceImpl service;

    @BeforeEach
    void setUp() {
        service = new SysUserServiceImpl();
        ReflectionTestUtils.setField(service, "sysRoleService", roleService);
        ReflectionTestUtils.setField(service, "sysUserRoleService", userRoleService);
    }

    @Test
    void keepsExistingDisabledRoleButRejectsNewAssignment() {
        long roleId = 9007199254740993L;
        long userId = 9007199254740995L;
        SysRole disabledRole = new SysRole();
        disabledRole.setRoleId(roleId);
        disabledRole.setStatus(0);
        when(roleService.list(any(com.baomidou.mybatisplus.core.conditions.Wrapper.class)))
                .thenReturn(List.of(disabledRole));
        SysUserRole existing = new SysUserRole();
        existing.setUserId(userId);
        existing.setRoleId(roleId);
        when(userRoleService.list(any(com.baomidou.mybatisplus.core.conditions.Wrapper.class)))
                .thenReturn(List.of(existing));

        assertDoesNotThrow(() -> service.validateRoles(List.of(roleId), userId));
        assertThrows(IllegalArgumentException.class, () -> service.validateRoles(List.of(roleId), null));
    }
}
