package com.xinghuiTec.controller;

import com.alibaba.fastjson2.JSON;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.DisplayName;
import org.springframework.http.MediaType;
import org.springframework.mock.web.MockMultipartFile;

import java.util.Arrays;
import java.util.List;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;
import static org.springframework.test.web.servlet.result.MockMvcResultHandlers.*;

/**
 * 文件管理控制器测试类
 * 测试文件管理模块的所有API端点
 * 
 * @author beidoa23
 * @since 2026-01-24
 */
@DisplayName("文件管理模块API测试")
public class SysFileControllerTest extends BaseControllerTest {

        /**
         * 测试文件上传
         * POST /file/upload
         */
        @Test
        @DisplayName("测试文件上传")
        public void testUploadFile() throws Exception {
                // 创建模拟的文件
                MockMultipartFile file = new MockMultipartFile(
                                "file",
                                "test.txt",
                                "text/plain",
                                "This is a test file content".getBytes());

                mockMvc.perform(multipart("/file/upload")
                                .file(file)
                                .header("Authorization", getAuthHeader()))
                                .andDo(print())
                                .andExpect(status().isOk())
                                .andExpect(jsonPath("$.code").value(200))
                                .andExpect(jsonPath("$.data").exists())
                                .andExpect(jsonPath("$.data.id").exists())
                                .andExpect(jsonPath("$.data.url").exists())
                                .andExpect(jsonPath("$.data.originalFilename").exists());
        }

        /**
         * 测试上传图片文件
         */
        @Test
        @DisplayName("测试上传图片文件")
        public void testUploadImageFile() throws Exception {
                MockMultipartFile imageFile = new MockMultipartFile(
                                "file",
                                "test-image.jpg",
                                "image/jpeg",
                                new byte[] { 1, 2, 3, 4, 5 } // 模拟图片内容
                );

                mockMvc.perform(multipart("/file/upload")
                                .file(imageFile)
                                .header("Authorization", getAuthHeader()))
                                .andDo(print())
                                .andExpect(status().isOk())
                                .andExpect(jsonPath("$.code").value(200))
                                .andExpect(jsonPath("$.data.thUrl").exists()); // 应该有缩略图
        }

        /**
         * 测试上传空文件
         */
        @Test
        @DisplayName("测试上传空文件")
        public void testUploadEmptyFile() throws Exception {
                MockMultipartFile emptyFile = new MockMultipartFile(
                                "file",
                                "empty.txt",
                                "text/plain",
                                new byte[0]);

                mockMvc.perform(multipart("/file/upload")
                                .file(emptyFile)
                                .header("Authorization", getAuthHeader()))
                                .andDo(print())
                                .andExpect(status().isOk())
                                .andExpect(jsonPath("$.code").value(500)); // 应该失败
        }

        /**
         * 测试上传大文件
         */
        @Test
        @DisplayName("测试上传大文件")
        public void testUploadLargeFile() throws Exception {
                // 创建一个5MB的模拟文件
                byte[] largeFileContent = new byte[5 * 1024 * 1024];
                MockMultipartFile largeFile = new MockMultipartFile(
                                "file",
                                "large-file.bin",
                                "application/octet-stream",
                                largeFileContent);

                mockMvc.perform(multipart("/file/upload")
                                .file(largeFile)
                                .header("Authorization", getAuthHeader()))
                                .andDo(print())
                                .andExpect(status().isOk())
                                .andExpect(jsonPath("$.code").value(200));
        }

        /**
         * 测试获取文件列表
         * GET /file/list
         */
        @Test
        @DisplayName("测试获取文件列表")
        public void testGetFileList() throws Exception {
                mockMvc.perform(get("/file/list")
                                .header("Authorization", getAuthHeader())
                                .param("pageNum", "1")
                                .param("pageSize", "10")
                                .contentType(MediaType.APPLICATION_JSON))
                                .andDo(print())
                                .andExpect(status().isOk())
                                .andExpect(jsonPath("$.code").value(200))
                                .andExpect(jsonPath("$.data").exists())
                                .andExpect(jsonPath("$.data.records").isArray())
                                .andExpect(jsonPath("$.data.total").exists());
        }

        /**
         * 测试按文件名查询
         */
        @Test
        @DisplayName("测试按文件名查询")
        public void testGetFileListByFilename() throws Exception {
                mockMvc.perform(get("/file/list")
                                .header("Authorization", getAuthHeader())
                                .param("pageNum", "1")
                                .param("pageSize", "10")
                                .param("originalFilename", "test")
                                .contentType(MediaType.APPLICATION_JSON))
                                .andDo(print())
                                .andExpect(status().isOk())
                                .andExpect(jsonPath("$.code").value(200));
        }

        /**
         * 测试按存储平台查询
         */
        @Test
        @DisplayName("测试按存储平台查询")
        public void testGetFileListByPlatform() throws Exception {
                mockMvc.perform(get("/file/list")
                                .header("Authorization", getAuthHeader())
                                .param("pageNum", "1")
                                .param("pageSize", "10")
                                .param("platform", "local") // local, aliyun-oss, minio等
                                .contentType(MediaType.APPLICATION_JSON))
                                .andDo(print())
                                .andExpect(status().isOk())
                                .andExpect(jsonPath("$.code").value(200));
        }

        /**
         * 测试按文件扩展名查询
         */
        @Test
        @DisplayName("测试按文件扩展名查询")
        public void testGetFileListByExtension() throws Exception {
                mockMvc.perform(get("/file/list")
                                .header("Authorization", getAuthHeader())
                                .param("pageNum", "1")
                                .param("pageSize", "10")
                                .param("ext", ".jpg")
                                .contentType(MediaType.APPLICATION_JSON))
                                .andDo(print())
                                .andExpect(status().isOk())
                                .andExpect(jsonPath("$.code").value(200));
        }

        /**
         * 测试按日期范围查询
         */
        @Test
        @DisplayName("测试按日期范围查询")
        public void testGetFileListByDateRange() throws Exception {
                mockMvc.perform(get("/file/list")
                                .header("Authorization", getAuthHeader())
                                .param("pageNum", "1")
                                .param("pageSize", "10")
                                .param("beginTime", "2024-01-01")
                                .param("endTime", "2024-12-31")
                                .contentType(MediaType.APPLICATION_JSON))
                                .andDo(print())
                                .andExpect(status().isOk())
                                .andExpect(jsonPath("$.code").value(200));
        }

        /**
         * 测试删除文件
         * POST /file/remove?fileId=xxx
         */
        @Test
        @DisplayName("测试删除文件")
        public void testDeleteFile() throws Exception {
                String fileId = "1"; // 需要替换为实际的文件ID

                mockMvc.perform(post("/file/remove")
                                .header("Authorization", getAuthHeader())
                                .param("fileId", fileId)
                                .contentType(MediaType.APPLICATION_JSON))
                                .andDo(print())
                                .andExpect(status().isOk())
                                .andExpect(jsonPath("$.code").value(200));
        }

        /**
         * 测试批量删除文件
         * POST /file/removeBatch
         */
        @Test
        @DisplayName("测试批量删除文件")
        public void testBatchDeleteFiles() throws Exception {
                List<Long> fileIds = Arrays.asList(1L, 2L, 3L);

                mockMvc.perform(post("/file/removeBatch")
                                .header("Authorization", getAuthHeader())
                                .contentType(MediaType.APPLICATION_JSON)
                                .content(JSON.toJSONString(fileIds)))
                                .andDo(print())
                                .andExpect(status().isOk())
                                .andExpect(jsonPath("$.code").value(200));
        }

        /**
         * 测试删除不存在的文件
         */
        @Test
        @DisplayName("测试删除不存在的文件")
        public void testDeleteNonExistentFile() throws Exception {
                String fileId = "999999";

                mockMvc.perform(post("/file/remove")
                                .header("Authorization", getAuthHeader())
                                .param("fileId", fileId)
                                .contentType(MediaType.APPLICATION_JSON))
                                .andDo(print())
                                .andExpect(status().isOk())
                                .andExpect(jsonPath("$.code").value(500)); // 文件不存在
        }

        /**
         * 测试下载文件
         * GET /file/download?fileId=xxx
         */
        @Test
        @DisplayName("测试下载文件")
        public void testDownloadFile() throws Exception {
                String fileId = "1"; // 需要替换为实际的文件ID

                mockMvc.perform(get("/file/download")
                                .header("Authorization", getAuthHeader())
                                .param("fileId", fileId))
                                .andDo(print())
                                .andExpect(status().isOk())
                                .andExpect(header().exists("Content-Disposition"));
        }

        /**
         * 测试获取文件URL
         * GET /file/url/{fileId}
         */
        @Test
        @DisplayName("测试获取文件URL")
        public void testGetFileUrl() throws Exception {
                String fileId = "1"; // 需要替换为实际的文件ID

                mockMvc.perform(get("/file/url/" + fileId)
                                .header("Authorization", getAuthHeader())
                                .contentType(MediaType.APPLICATION_JSON))
                                .andDo(print())
                                .andExpect(status().isOk())
                                .andExpect(jsonPath("$.code").value(200))
                                .andExpect(jsonPath("$.data").exists()); // 返回文件URL
        }

        /**
         * 测试获取不存在文件的URL
         */
        @Test
        @DisplayName("测试获取不存在文件的URL")
        public void testGetNonExistentFileUrl() throws Exception {
                String fileId = "999999";

                mockMvc.perform(get("/file/url/" + fileId)
                                .header("Authorization", getAuthHeader())
                                .contentType(MediaType.APPLICATION_JSON))
                                .andDo(print())
                                .andExpect(status().isOk())
                                .andExpect(jsonPath("$.code").value(500)); // 文件不存在
        }

        /**
         * 测试无权限访问
         */
        @Test
        @DisplayName("测试无权限访问文件列表")
        public void testUnauthorizedAccess() throws Exception {
                mockMvc.perform(get("/file/list")
                                .param("pageNum", "1")
                                .param("pageSize", "10"))
                                .andDo(print())
                                .andExpect(status().isUnauthorized()); // 401未认证
        }

        /**
         * 测试无权限上传文件
         */
        @Test
        @DisplayName("测试无权限上传文件")
        public void testUnauthorizedUpload() throws Exception {
                MockMultipartFile file = new MockMultipartFile(
                                "file",
                                "test.txt",
                                "text/plain",
                                "test content".getBytes());

                mockMvc.perform(multipart("/file/upload")
                                .file(file))
                                .andDo(print())
                                .andExpect(status().isUnauthorized()); // 401未认证
        }
}
