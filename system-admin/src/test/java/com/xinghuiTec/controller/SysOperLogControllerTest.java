package com.xinghuiTec.controller;

import com.alibaba.fastjson2.JSON;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.DisplayName;
import org.springframework.http.MediaType;

import java.util.Arrays;
import java.util.List;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;
import static org.springframework.test.web.servlet.result.MockMvcResultHandlers.*;

/**
 * 操作日志控制器测试类
 * 测试操作日志模块的所有API端点
 * 
 * @author beidoa23
 * @since 2026-01-24
 */
@DisplayName("操作日志模块API测试")
public class SysOperLogControllerTest extends BaseControllerTest {

    /**
     * 测试查询操作日志列表
     * GET /monitor/operlog/list
     */
    @Test
    @DisplayName("测试查询操作日志列表")
    public void testGetOperLogList() throws Exception {
        mockMvc.perform(get("/monitor/operlog/list")
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
     * 测试按标题查询操作日志
     */
    @Test
    @DisplayName("测试按标题查询操作日志")
    public void testGetOperLogListWithTitle() throws Exception {
        mockMvc.perform(get("/monitor/operlog/list")
                .header("Authorization", getAuthHeader())
                .param("pageNum", "1")
                .param("pageSize", "10")
                .param("title", "用户管理")
                .contentType(MediaType.APPLICATION_JSON))
                .andDo(print())
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value(200))
                .andExpect(jsonPath("$.data.records").isArray());
    }

    /**
     * 测试按操作人员查询
     */
    @Test
    @DisplayName("测试按操作人员查询")
    public void testGetOperLogListByOperName() throws Exception {
        mockMvc.perform(get("/monitor/operlog/list")
                .header("Authorization", getAuthHeader())
                .param("pageNum", "1")
                .param("pageSize", "10")
                .param("operName", "admin")
                .contentType(MediaType.APPLICATION_JSON))
                .andDo(print())
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value(200));
    }

    /**
     * 测试按业务类型查询
     */
    @Test
    @DisplayName("测试按业务类型查询")
    public void testGetOperLogListByBusinessType() throws Exception {
        mockMvc.perform(get("/monitor/operlog/list")
                .header("Authorization", getAuthHeader())
                .param("pageNum", "1")
                .param("pageSize", "10")
                .param("businessType", "1") // 1=新增 2=修改 3=删除等
                .contentType(MediaType.APPLICATION_JSON))
                .andDo(print())
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value(200));
    }

    /**
     * 测试按状态查询
     */
    @Test
    @DisplayName("测试按状态查询")
    public void testGetOperLogListByStatus() throws Exception {
        mockMvc.perform(get("/monitor/operlog/list")
                .header("Authorization", getAuthHeader())
                .param("pageNum", "1")
                .param("pageSize", "10")
                .param("status", "0") // 0=成功 1=失败
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
    public void testGetOperLogListByDateRange() throws Exception {
        mockMvc.perform(get("/monitor/operlog/list")
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
     * 测试组合条件查询
     */
    @Test
    @DisplayName("测试组合条件查询")
    public void testGetOperLogListWithMultipleConditions() throws Exception {
        mockMvc.perform(get("/monitor/operlog/list")
                .header("Authorization", getAuthHeader())
                .param("pageNum", "1")
                .param("pageSize", "10")
                .param("title", "用户管理")
                .param("operName", "admin")
                .param("businessType", "1")
                .param("status", "0")
                .param("beginTime", "2024-01-01")
                .param("endTime", "2024-12-31")
                .contentType(MediaType.APPLICATION_JSON))
                .andDo(print())
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value(200));
    }

    /**
     * 测试删除操作日志
     * POST /monitor/operlog/remove
     */
    @Test
    @DisplayName("测试删除操作日志")
    public void testDeleteOperLog() throws Exception {
        List<Long> operIds = Arrays.asList(100L); // 需要替换为实际的日志ID

        mockMvc.perform(post("/monitor/operlog/remove")
                .header("Authorization", getAuthHeader())
                .contentType(MediaType.APPLICATION_JSON)
                .content(JSON.toJSONString(operIds)))
                .andDo(print())
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value(200));
    }

    /**
     * 测试批量删除操作日志
     */
    @Test
    @DisplayName("测试批量删除操作日志")
    public void testBatchDeleteOperLog() throws Exception {
        List<Long> operIds = Arrays.asList(100L, 101L, 102L);

        mockMvc.perform(post("/monitor/operlog/remove")
                .header("Authorization", getAuthHeader())
                .contentType(MediaType.APPLICATION_JSON)
                .content(JSON.toJSONString(operIds)))
                .andDo(print())
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value(200));
    }

    /**
     * 测试清空操作日志
     * POST /monitor/operlog/clean
     */
    @Test
    @DisplayName("测试清空操作日志")
    public void testCleanOperLog() throws Exception {
        mockMvc.perform(post("/monitor/operlog/clean")
                .header("Authorization", getAuthHeader())
                .contentType(MediaType.APPLICATION_JSON))
                .andDo(print())
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value(200));
    }

    /**
     * 测试无权限访问
     */
    @Test
    @DisplayName("测试无权限访问日志列表")
    public void testUnauthorizedAccess() throws Exception {
        mockMvc.perform(get("/monitor/operlog/list")
                .param("pageNum", "1")
                .param("pageSize", "10"))
                .andDo(print())
                .andExpect(status().isUnauthorized()); // 401未认证
    }
}
