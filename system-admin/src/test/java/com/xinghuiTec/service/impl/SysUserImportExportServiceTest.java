package com.xinghuiTec.service.impl;

import cn.idev.excel.FastExcel;
import cn.idev.excel.context.AnalysisContext;
import cn.idev.excel.event.AnalysisEventListener;
import com.baomidou.mybatisplus.core.conditions.Wrapper;
import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import com.xinghuiTec.domain.dto.SysUserAddDTO;
import com.xinghuiTec.domain.dto.SysUserQueryDTO;
import com.xinghuiTec.domain.entity.SysUser;
import com.xinghuiTec.domain.entity.SysUserRole;
import com.xinghuiTec.domain.excel.SysUserExcel;
import com.xinghuiTec.domain.excel.SysUserImportExcel;
import com.xinghuiTec.domain.vo.UserImportSummary;
import com.xinghuiTec.service.SysRoleService;
import com.xinghuiTec.service.SysUserService;
import com.xinghuiTec.service.SysUserRoleService;
import jakarta.validation.Validation;
import jakarta.validation.Validator;
import org.junit.jupiter.api.Test;
import org.springframework.mock.web.MockHttpServletResponse;
import org.springframework.mock.web.MockMultipartFile;
import org.springframework.test.util.ReflectionTestUtils;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.util.ArrayList;
import java.util.List;
import java.util.stream.IntStream;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentCaptor.forClass;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.doReturn;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.spy;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class SysUserImportExportServiceTest {
    @Test
    void rejectsOverLimitExportsBeforeWritingResponseHeaders() {
        SysUserServiceImpl service = spy(new SysUserServiceImpl());
        doReturn(10_001L).when(service).count(any());
        MockHttpServletResponse response = new MockHttpServletResponse();

        assertThrows(IllegalArgumentException.class,
                () -> service.exportUsers(new SysUserQueryDTO(), response));

        verify(service).count(any());
        assertNull(response.getContentType());
        assertNull(response.getHeader("Content-Disposition"));
    }

    @Test
    void exportsStablePagesAndTruncatesTheLastPageToTheCountedRowBudget() throws Exception {
        SysUserServiceImpl service = spy(new SysUserServiceImpl());
        doReturn(501L).when(service).count(any());

        List<SysUser> orderedUsers = IntStream.rangeClosed(1, 600)
                .mapToObj(index -> {
                    SysUser user = new SysUser();
                    user.setUserId((long) index);
                    user.setUsername("user_" + index);
                    user.setStatus(0);
                    return user;
                })
                .toList();
        List<Long> requestedPageSizes = new ArrayList<>();
        doAnswer(invocation -> {
            @SuppressWarnings("unchecked")
            Page<SysUser> requested = (Page<SysUser>) invocation.getArgument(0);
            requestedPageSizes.add(requested.getSize());
            int start = Math.toIntExact((requested.getCurrent() - 1) * requested.getSize());
            int end = Math.min(start + Math.toIntExact(requested.getSize()), orderedUsers.size());
            Page<SysUser> result = new Page<>(requested.getCurrent(), requested.getSize(), false);
            result.setRecords(orderedUsers.subList(start, end));
            return result;
        }).when(service).page(any(Page.class), any());

        SysUserRoleService userRoleService = mock(SysUserRoleService.class);
        when(userRoleService.list(any(Wrapper.class))).thenReturn(List.<SysUserRole>of());
        ReflectionTestUtils.setField(service, "sysUserRoleService", userRoleService);

        MockHttpServletResponse response = new MockHttpServletResponse();
        service.exportUsers(new SysUserQueryDTO(), response);

        List<SysUserExcel> exported = new ArrayList<>();
        FastExcel.read(new ByteArrayInputStream(response.getContentAsByteArray()), SysUserExcel.class,
                new AnalysisEventListener<SysUserExcel>() {
                    @Override
                    public void invoke(SysUserExcel row, AnalysisContext context) {
                        exported.add(row);
                    }

                    @Override
                    public void doAfterAllAnalysed(AnalysisContext context) {
                    }
                })
                .sheet()
                .doRead();
        assertEquals(List.of(500L, 500L), requestedPageSizes);
        assertEquals(501, exported.size());
        assertEquals(IntStream.rangeClosed(1, 501).mapToObj(index -> "user_" + index).toList(),
                exported.stream().map(SysUserExcel::getUsername).toList());
    }

    @Test
    void rejectsWrongExtensionAndDamagedPackagesBeforeAccessingServices() {
        SysUserServiceImpl service = new SysUserServiceImpl();
        MockMultipartFile wrongExtension = new MockMultipartFile(
                "file", "users.xls", "application/octet-stream", new byte[]{'P', 'K', 3, 4});
        MockMultipartFile damagedXlsx = new MockMultipartFile(
                "file", "users.xlsx", "application/octet-stream", new byte[]{'n', 'o', 'p', 'e'});

        assertThrows(IllegalArgumentException.class, () -> service.importUsers(wrongExtension));
        assertThrows(IllegalArgumentException.class, () -> service.importUsers(damagedXlsx));
    }

    @Test
    void createsImportedAccountsDisabledAndExplainsTheActivationStep() throws Exception {
        ByteArrayOutputStream workbook = new ByteArrayOutputStream();
        SysUserImportExcel input = new SysUserImportExcel();
        input.setUsername("pending_user");
        input.setNickname("Preprovisioned");
        input.setMobile("13800138000");
        FastExcel.write(workbook, SysUserImportExcel.class)
                .sheet("用户导入")
                .doWrite(List.of(input));

        SysUserService selfProxy = mock(SysUserService.class);
        when(selfProxy.addUser(any(SysUserAddDTO.class))).thenReturn(42L);
        SysRoleService roleService = mock(SysRoleService.class);
        when(roleService.list()).thenReturn(List.of());
        Validator validator = Validation.buildDefaultValidatorFactory().getValidator();
        SysUserServiceImpl service = new SysUserServiceImpl();
        ReflectionTestUtils.setField(service, "selfProxy", selfProxy);
        ReflectionTestUtils.setField(service, "sysRoleService", roleService);
        ReflectionTestUtils.setField(service, "validator", validator);

        MockMultipartFile file = new MockMultipartFile("file", "users.xlsx",
                "application/vnd.openxmlformats-officedocument.spreadsheetml.sheet", workbook.toByteArray());
        UserImportSummary summary = service.importUsers(file);

        var importedUser = forClass(SysUserAddDTO.class);
        verify(selfProxy).addUser(importedUser.capture());
        assertEquals(0, importedUser.getValue().getStatus());
        assertNotNull(importedUser.getValue().getPassword());
        assertEquals(1, summary.successCount());
        assertEquals(0, summary.failureCount());
        assertFalse(summary.rows().get(0).reason().isBlank());
        assertTrue(summary.rows().get(0).reason().contains("待激活"));
        assertFalse(summary.rows().get(0).reason().contains(importedUser.getValue().getPassword()));
    }
}
