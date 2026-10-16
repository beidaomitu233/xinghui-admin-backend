package com.xinghuiTec.oss;

import com.xinghuiTec.oss.service.ISysOssService;
import org.dromara.x.file.storage.core.FileInfo;
import org.dromara.x.file.storage.core.FileStorageService;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.mock.web.MockHttpServletResponse;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;

import static org.junit.jupiter.api.Assertions.*;

/** Only runs when explicitly enabled against the local QA MySQL and MinIO. */
@SpringBootTest(properties = {
        "spring.flyway.enabled=false",
        "spring.data.redis.database=14"
})
@EnabledIfEnvironmentVariable(named = "FND03_LIVE_TEST", matches = "true")
class OssLiveFlowIT {
    @Autowired private OssService ossService;
    @Autowired private ISysOssService records;
    @Autowired private FileStorageService storage;

    @Test
    void uploadDownloadAndDeleteTheSameRealObject() throws Exception {
        byte[] expected = "qa_accept_fnd03_live_20260926".getBytes(StandardCharsets.UTF_8);
        FileInfo uploaded = null;
        try {
            uploaded = ossService.upload(expected, "qa_accept_fnd03_live_20260926.txt", "text/plain", null);
            assertNotNull(uploaded.getId());
            assertNotNull(records.getById(Long.parseLong(uploaded.getId())));
            assertTrue(storage.exists(uploaded));
            URI publicUrl = URI.create(System.getenv("MINIO_ENDPOINT")
                    + "/xinghui-admin/" + uploaded.getUrl());
            HttpClient http = HttpClient.newHttpClient();
            HttpResponse<byte[]> beforeDelete = http.send(
                    HttpRequest.newBuilder(publicUrl).GET().build(),
                    HttpResponse.BodyHandlers.ofByteArray());
            assertEquals(200, beforeDelete.statusCode());
            assertArrayEquals(expected, beforeDelete.body());

            MockHttpServletResponse response = new MockHttpServletResponse();
            ossService.download(uploaded.getUrl(), uploaded.getOriginalFilename(), response);
            assertArrayEquals(MessageDigest.getInstance("SHA-256").digest(expected),
                    MessageDigest.getInstance("SHA-256").digest(response.getContentAsByteArray()));

            assertTrue(ossService.delete(uploaded.getUrl()));
            assertFalse(storage.exists(uploaded), "MinIO object must be absent after DB removal");
            assertEquals(404, http.send(HttpRequest.newBuilder(publicUrl).GET().build(),
                    HttpResponse.BodyHandlers.discarding()).statusCode());
            assertNull(records.getById(Long.parseLong(uploaded.getId())));
        } finally {
            // Clean up only this test's unique QA object, including a partially failed run.
            if (uploaded != null) {
                if (storage.exists(uploaded)) {
                    storage.getFileStorageVerify(uploaded).delete(uploaded);
                }
                if (uploaded.getId() != null) {
                    records.removeById(Long.parseLong(uploaded.getId()));
                }
            }
        }
    }
}
