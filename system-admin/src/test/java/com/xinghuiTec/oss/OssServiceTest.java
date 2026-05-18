package com.xinghuiTec.oss;

import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import com.xinghuiTec.oss.entity.SysOss;
import com.xinghuiTec.oss.service.ISysOssService;
import org.dromara.x.file.storage.core.FileInfo;
import org.junit.jupiter.api.*;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;

import java.io.ByteArrayInputStream;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;

import static org.junit.jupiter.api.Assertions.*;

@SpringBootTest
@TestMethodOrder(MethodOrderer.OrderAnnotation.class)
@DisplayName("OSS 文件增删改查测试")
class OssServiceTest {

    @Autowired
    private OssService ossService;

    @Autowired
    private ISysOssService ossRecordService;

    private static Long testOssId;
    private static String testUrl;
    private static final String TEST_CONTENT = "兴辉Admin OSS测试文件 - " + System.currentTimeMillis();

    @Test
    @Order(1)
    @DisplayName("上传文件 - 字节数组")
    void testUploadBytes() {
        byte[] data = TEST_CONTENT.getBytes(StandardCharsets.UTF_8);
        FileInfo info = ossService.upload(data, "test-oss.txt", "text/plain", null);

        assertNotNull(info);
        assertNotNull(info.getUrl());
        assertTrue(info.getSize() > 0);

        testUrl = info.getUrl();

        // 保存记录到数据库
        SysOss record = new SysOss();
        record.setOriginalName("test-oss.txt");
        record.setFileName(info.getFilename());
        record.setFileSuffix("txt");
        record.setUrl(info.getUrl());
        record.setSize(info.getSize());
        record.setPlatform(info.getPlatform());
        ossRecordService.save(record);
        testOssId = record.getOssId();

        System.out.println("✓ 上传成功");
        System.out.println("  ossId: " + testOssId);
        System.out.println("  url: " + info.getUrl());
        System.out.println("  size: " + info.getSize() + " bytes");
        System.out.println("  platform: " + info.getPlatform());
    }

    @Test
    @Order(2)
    @DisplayName("查询文件 - 文件存在性检查")
    void testExists() {
        assertNotNull(testUrl, "需要先执行上传测试");
        boolean exists = ossService.exists(testUrl);
        assertTrue(exists, "刚上传的文件应该存在");
        System.out.println("✓ 文件存在: " + testUrl);
    }

    @Test
    @Order(3)
    @DisplayName("查询文件 - 获取文件信息")
    void testGetFileInfo() {
        assertNotNull(testUrl, "需要先执行上传测试");
        FileInfo info = ossService.getFileInfo(testUrl);
        assertNotNull(info);
        assertNotNull(info.getFilename());
        assertTrue(info.getSize() > 0);
        System.out.println("✓ 文件信息: 名称=" + info.getFilename() + ", 大小=" + info.getSize());
    }

    @Test
    @Order(4)
    @DisplayName("查询文件 - 数据库记录")
    void testQueryRecord() {
        assertNotNull(testOssId, "需要先执行上传测试");
        SysOss record = ossRecordService.getById(testOssId);
        assertNotNull(record);
        assertEquals(testUrl, record.getUrl());
        assertEquals("test-oss.txt", record.getOriginalName());
        System.out.println("✓ 数据库记录: ossId=" + record.getOssId() + ", url=" + record.getUrl());
    }

    @Test
    @Order(5)
    @DisplayName("查询文件 - 分页列表")
    void testListRecords() {
        Page<SysOss> page = ossRecordService.page(new Page<>(1, 10));
        assertNotNull(page);
        assertTrue(page.getTotal() > 0);
        System.out.println("✓ 文件列表: 共 " + page.getTotal() + " 条, 当前页 " + page.getRecords().size() + " 条");
    }

    @Test
    @Order(6)
    @DisplayName("上传文件 - 输入流")
    void testUploadStream() {
        InputStream inputStream = new ByteArrayInputStream(TEST_CONTENT.getBytes(StandardCharsets.UTF_8));
        FileInfo info = ossService.upload(inputStream, "test-stream.txt", "text/plain", null);
        assertNotNull(info);
        assertNotNull(info.getUrl());

        // 记录并立即清理
        SysOss record = new SysOss();
        record.setOriginalName("test-stream.txt");
        record.setFileName(info.getFilename());
        record.setFileSuffix("txt");
        record.setUrl(info.getUrl());
        record.setSize(info.getSize());
        record.setPlatform(info.getPlatform());
        ossRecordService.save(record);

        // 清理
        ossService.delete(info.getUrl());
        ossRecordService.removeById(record.getOssId());

        System.out.println("✓ 输入流上传成功并已清理");
    }

    @Test
    @Order(7)
    @DisplayName("删除文件 - 数据库 + 云存储")
    void testDelete() {
        assertNotNull(testOssId, "需要先执行上传测试");

        // 删除数据库记录
        SysOss record = ossRecordService.getById(testOssId);
        assertNotNull(record);

        // 删除云存储文件
        ossService.delete(record.getUrl());

        // 删除数据库记录
        ossRecordService.removeById(testOssId);

        // 验证文件不存在
        assertFalse(ossService.exists(testUrl));
        assertNull(ossRecordService.getById(testOssId));

        System.out.println("✓ 删除成功: 云存储文件 + 数据库记录均已删除");
    }
}
