package com.xinghuiTec.oss;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;

import static org.junit.jupiter.api.Assertions.*;

@SpringBootTest
@DisplayName("OSS 模块测试")
public class OssServiceTest {

    @Autowired(required = false)
    private OssService ossService;

    @Test
    @DisplayName("OssService Bean 是否注册")
    void testOssServiceBean() {
        assertNotNull(ossService, "OssService Bean 应已注册");
        System.out.println("✓ OssService Bean 存在");
    }

    @Test
    @DisplayName("文件存在性检查")
    void testExists() {
        assertFalse(ossService.exists("https://fake-url/nonexistent-file.txt"));
        System.out.println("✓ 不存在文件返回 false");
    }
}
