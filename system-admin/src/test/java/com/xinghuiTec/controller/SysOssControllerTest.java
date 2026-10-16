package com.xinghuiTec.controller;

import com.xinghuiTec.domain.entity.LoginUser;
import com.xinghuiTec.domain.entity.SysUser;
import com.xinghuiTec.oss.OssService;
import com.xinghuiTec.oss.entity.SysOss;
import com.xinghuiTec.oss.service.ISysOssService;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.dromara.x.file.storage.core.FileInfo;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.mock.web.MockMultipartFile;
import org.springframework.web.multipart.MultipartFile;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.test.util.ReflectionTestUtils;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;

import java.util.List;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

class SysOssControllerTest {
    private final SysOssController controller = new SysOssController();
    private final ISysOssService records = mock(ISysOssService.class);
    private final OssService storage = mock(OssService.class);

    @BeforeEach
    void setUp() {
        ReflectionTestUtils.setField(controller, "sysOssService", records);
        ReflectionTestUtils.setField(controller, "ossService", storage);
        SysUser user = new SysUser();
        user.setUserId(10L);
        LoginUser login = new LoginUser(user, List.of());
        SecurityContextHolder.getContext().setAuthentication(
                new UsernamePasswordAuthenticationToken(login, null, List.of()));
    }

    @AfterEach
    void tearDown() {
        SecurityContextHolder.clearContext();
    }

    @Test
    void uploadRejectsInvalidFilesBeforeStorage() {
        assertThrows(IllegalArgumentException.class, () -> controller.upload(
                new MockMultipartFile("file", "bad.exe", "application/octet-stream", new byte[]{1})));
        MultipartFile oversized = mock(MultipartFile.class);
        when(oversized.getOriginalFilename()).thenReturn("large.pdf");
        when(oversized.getSize()).thenReturn(SysOssController.MAX_UPLOAD_SIZE + 1);
        assertThrows(IllegalArgumentException.class, () -> controller.upload(oversized));
        verifyNoInteractions(storage);
    }

    @Test
    void uploadReturnsThePersistedRecordId() {
        MockMultipartFile input = new MockMultipartFile("file", "report.pdf", "application/pdf", new byte[]{1});
        FileInfo stored = new FileInfo();
        stored.setId("20");
        stored.setUrl("object-url");
        when(storage.upload(input)).thenReturn(stored);
        when(records.getById(20L)).thenReturn(file(20L, "10"));
        assertEquals(20L, controller.upload(input).getData().getOssId());
    }

    @Test
    void ossIdLargerThanJavascriptSafeIntegerStaysExactAcrossJson() throws Exception {
        long largeId = 9_007_199_254_740_993L;
        ObjectMapper mapper = new ObjectMapper();
        String response = mapper.writeValueAsString(file(largeId, "10"));
        assertTrue(response.contains("\"ossId\":\"9007199254740993\""));
        List<Long> deleteIds = mapper.readValue("[\"9007199254740993\"]", new TypeReference<>() {});
        assertEquals(List.of(largeId), deleteIds);
    }

    @Test
    void uploadWithoutPersistedRecordCleansUpObject() {
        MockMultipartFile input = new MockMultipartFile("file", "report.pdf", "application/pdf", new byte[]{1});
        FileInfo stored = new FileInfo();
        stored.setUrl("object-url");
        when(storage.upload(input)).thenReturn(stored);
        assertThrows(RuntimeException.class, () -> controller.upload(input));
        verify(storage).delete("object-url");
    }

    @Test
    void otherUsersFileCannotBeReadOrDeleted() {
        SysOss foreign = file(21L, "11");
        when(records.getById(21L)).thenReturn(foreign);
        assertThrows(AccessDeniedException.class, () -> controller.getInfo(21L));
        assertThrows(AccessDeniedException.class, () -> controller.download(21L, new org.springframework.mock.web.MockHttpServletResponse()));
        assertThrows(AccessDeniedException.class, () -> controller.remove(List.of(21L)));
        verifyNoInteractions(storage);
    }

    @Test
    void batchDeletionValidatesEveryRecordBeforeDeletingAnything() {
        when(records.getById(20L)).thenReturn(file(20L, "10"));
        when(records.getById(21L)).thenReturn(file(21L, "11"));
        assertThrows(AccessDeniedException.class, () -> controller.remove(List.of(20L, 21L)));
        verifyNoInteractions(storage);
    }

    @Test
    void missingAndRepeatedDeletionHaveExplicitResult() {
        when(records.getById(20L)).thenReturn(file(20L, "10")).thenReturn(null);
        when(storage.delete("object-url")).thenReturn(true);
        assertEquals(200, controller.remove(List.of(20L)).getCode());
        assertThrows(RuntimeException.class, () -> controller.remove(List.of(20L)));
        verify(storage, times(1)).delete("object-url");
    }

    @Test
    void storageFailureDoesNotClaimDeletionSucceeded() {
        when(records.getById(20L)).thenReturn(file(20L, "10"));
        when(storage.delete("object-url")).thenReturn(false);
        assertThrows(RuntimeException.class, () -> controller.remove(List.of(20L)));
    }

    @Test
    void downloadFailureReturnsGatewayErrorWithoutAttachmentHeaders() throws Exception {
        when(records.getById(20L)).thenReturn(file(20L, "10"));
        doThrow(new RuntimeException("MinIO unavailable"))
                .when(storage).download(eq("object-url"), any(), any());

        MockMvcBuilders.standaloneSetup(controller).build()
                .perform(get("/system/oss/download/20"))
                .andExpect(status().isBadGateway())
                .andExpect(header().doesNotExist("Content-Disposition"));
    }

    private SysOss file(long id, String owner) {
        SysOss file = new SysOss();
        file.setOssId(id);
        file.setCreateBy(owner);
        file.setUrl("object-url");
        file.setOriginalName("qa.txt");
        return file;
    }
}
