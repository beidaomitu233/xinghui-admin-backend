package com.xinghuiTec.recorder;

import com.xinghuiTec.oss.entity.SysOss;
import com.xinghuiTec.oss.service.ISysOssService;
import org.dromara.x.file.storage.core.FileInfo;
import org.junit.jupiter.api.Test;
import org.springframework.test.util.ReflectionTestUtils;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

class FileRecorderImplTest {
    private final FileRecorderImpl recorder = new FileRecorderImpl();
    private final ISysOssService records = mock(ISysOssService.class);

    @Test
    void restoresTheExactMinioObjectKeyFromExistingRecord() {
        ReflectionTestUtils.setField(recorder, "sysOssService", records);
        SysOss record = new SysOss();
        record.setOssId(1L);
        record.setPlatform("minio-1");
        record.setFileName("qa_accept_file.txt");
        record.setUrl("upload/2026/09/26/qa_accept_file.txt");
        when(records.getOne(any(), eq(false))).thenReturn(record);

        FileInfo restored = recorder.getByUrl(record.getUrl());
        assertEquals("", restored.getBasePath());
        assertEquals("upload/2026/09/26/", restored.getPath());
        assertEquals(record.getUrl(), restored.getBasePath() + restored.getPath() + restored.getFilename());
    }

    @Test
    void refusesUnrecoverableObjectKey() {
        ReflectionTestUtils.setField(recorder, "sysOssService", records);
        SysOss record = new SysOss();
        record.setFileName("file.txt");
        record.setUrl("https://example.invalid/file.txt");
        when(records.getOne(any(), eq(false))).thenReturn(record);
        assertThrows(IllegalStateException.class, () -> recorder.getByUrl(record.getUrl()));
    }
}
