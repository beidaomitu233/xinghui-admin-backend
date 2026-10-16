package com.xinghuiTec.oss;

import org.dromara.x.file.storage.core.FileInfo;
import org.dromara.x.file.storage.core.FileStorageService;
import org.dromara.x.file.storage.core.platform.FileStorage;
import org.dromara.x.file.storage.core.recorder.FileRecorder;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

class OssServiceDeletionTest {
    private final FileStorageService storage = mock(FileStorageService.class);
    private final FileStorage platform = mock(FileStorage.class);
    private final FileRecorder recorder = mock(FileRecorder.class);
    private final OssService service = new OssService(storage);

    @Test
    void deletesRecordOnlyAfterObjectNoLongerExists() {
        FileInfo info = new FileInfo().setUrl("upload/qa.txt").setPlatform("minio-1");
        when(storage.getFileInfoByUrl(info.getUrl())).thenReturn(info);
        when(storage.exists(info)).thenReturn(true, false);
        when(storage.getFileStorageVerify(info)).thenReturn(platform);
        when(storage.getFileRecorder()).thenReturn(recorder);
        when(platform.delete(info)).thenReturn(true);
        when(recorder.delete(info.getUrl())).thenReturn(true);

        assertTrue(service.delete(info.getUrl()));
        var order = inOrder(storage, platform, recorder);
        order.verify(storage).exists(info);
        order.verify(platform).delete(info);
        order.verify(storage).exists(info);
        order.verify(recorder).delete(info.getUrl());
    }

    @Test
    void missingObjectDoesNotDeleteRecord() {
        FileInfo info = new FileInfo().setUrl("upload/qa.txt");
        when(storage.getFileInfoByUrl(info.getUrl())).thenReturn(info);
        when(storage.exists(info)).thenReturn(false);
        assertFalse(service.delete(info.getUrl()));
        verifyNoInteractions(platform, recorder);
    }

    @Test
    void objectStillPresentDoesNotDeleteRecord() {
        FileInfo info = new FileInfo().setUrl("upload/qa.txt");
        when(storage.getFileInfoByUrl(info.getUrl())).thenReturn(info);
        when(storage.exists(info)).thenReturn(true, true);
        when(storage.getFileStorageVerify(info)).thenReturn(platform);
        when(platform.delete(info)).thenReturn(true);
        assertFalse(service.delete(info.getUrl()));
        verifyNoInteractions(recorder);
    }
}
