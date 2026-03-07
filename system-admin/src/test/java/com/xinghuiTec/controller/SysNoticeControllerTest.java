package com.xinghuiTec.controller;

import com.alibaba.fastjson2.JSON;
import com.xinghuiTec.domain.dto.SysNoticeAddDTO;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.DisplayName;
import org.springframework.http.MediaType;

import java.util.Arrays;
import java.util.List;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;
import static org.springframework.test.web.servlet.result.MockMvcResultHandlers.*;

/**
 * 通知公告控制器测试类
 * 测试通知公告模块的所有API端点
 * 
 * @author beidoa23
 * @since 2026-01-24
 */
@DisplayName("通知公告模块API测试")
public class SysNoticeControllerTest extends BaseControllerTest {

    /**
     * 测试获取通知公告列表
     * GET /system/notice/list
     */
    @Test
    @DisplayName("测试获取通知公告列表")
    public void testGetNoticeList() throws Exception {
        mockMvc.perform(get("/system/notice/list")
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
     * 测试按标题查询通知
     */
    @Test
    @DisplayName("测试按标题查询通知")
    public void testGetNoticeListByTitle() throws Exception {
        mockMvc.perform(get("/system/notice/list")
                .header("Authorization", getAuthHeader())
                .param("pageNum", "1")
                .param("pageSize", "10")
                .param("noticeTitle", "系统升级")
                .contentType(MediaType.APPLICATION_JSON))
                .andDo(print())
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value(200));
    }

    /**
     * 测试按类型查询通知
     */
    @Test
    @DisplayName("测试按类型查询通知")
    public void testGetNoticeListByType() throws Exception {
        mockMvc.perform(get("/system/notice/list")
                .header("Authorization", getAuthHeader())
                .param("pageNum", "1")
                .param("pageSize", "10")
                .param("noticeType", "1") // 1=通知卡片 2=强弹窗
                .contentType(MediaType.APPLICATION_JSON))
                .andDo(print())
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value(200));
    }

    /**
     * 测试按状态查询通知
     */
    @Test
    @DisplayName("测试按状态查询通知")
    public void testGetNoticeListByStatus() throws Exception {
        mockMvc.perform(get("/system/notice/list")
                .header("Authorization", getAuthHeader())
                .param("pageNum", "1")
                .param("pageSize", "10")
                .param("status", "1") // 1=正常 0=关闭
                .contentType(MediaType.APPLICATION_JSON))
                .andDo(print())
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value(200));
    }

    /**
     * 测试按创建者查询
     */
    @Test
    @DisplayName("测试按创建者查询")
    public void testGetNoticeListByCreator() throws Exception {
        mockMvc.perform(get("/system/notice/list")
                .header("Authorization", getAuthHeader())
                .param("pageNum", "1")
                .param("pageSize", "10")
                .param("createBy", "admin")
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
    public void testGetNoticeListByDateRange() throws Exception {
        mockMvc.perform(get("/system/notice/list")
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
     * 测试获取通知详情
     * GET /system/notice/{noticeId}
     */
    @Test
    @DisplayName("测试获取通知详情")
    public void testGetNoticeDetail() throws Exception {
        Long noticeId = 1L; // 需要替换为实际的通知ID

        mockMvc.perform(get("/system/notice/" + noticeId)
                .header("Authorization", getAuthHeader())
                .contentType(MediaType.APPLICATION_JSON))
                .andDo(print())
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value(200))
                .andExpect(jsonPath("$.data.noticeId").exists())
                .andExpect(jsonPath("$.data.noticeTitle").exists())
                .andExpect(jsonPath("$.data.noticeType").exists())
                .andExpect(jsonPath("$.data.noticeContent").exists());
    }

    /**
     * 测试新增通知
     * POST /system/notice/add
     */
    @Test
    @DisplayName("测试新增通知")
    public void testAddNotice() throws Exception {
        SysNoticeAddDTO noticeAddDTO = new SysNoticeAddDTO();
        noticeAddDTO.setNoticeTitle("系统维护通知_" + System.currentTimeMillis());
        noticeAddDTO.setNoticeType(1); // 1=通知卡片 2=强弹窗
        noticeAddDTO.setNoticeContent("<p>系统将于今晚22:00-23:00进行维护，期间可能无法访问。</p>");
        noticeAddDTO.setStatus(1); // 1=正常 0=关闭

        mockMvc.perform(post("/system/notice/add")
                .header("Authorization", getAuthHeader())
                .contentType(MediaType.APPLICATION_JSON)
                .content(JSON.toJSONString(noticeAddDTO)))
                .andDo(print())
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value(200));
    }

    /**
     * 测试新增公告
     */
    @Test
    @DisplayName("测试新增公告")
    public void testAddAnnouncement() throws Exception {
        SysNoticeAddDTO noticeAddDTO = new SysNoticeAddDTO();
        noticeAddDTO.setNoticeTitle("新功能发布公告_" + System.currentTimeMillis());
        noticeAddDTO.setNoticeType(2); // 2=强弹窗
        noticeAddDTO.setNoticeContent("<h3>我们很高兴地宣布新功能上线</h3><p>详细内容...</p>");
        noticeAddDTO.setStatus(1);

        mockMvc.perform(post("/system/notice/add")
                .header("Authorization", getAuthHeader())
                .contentType(MediaType.APPLICATION_JSON)
                .content(JSON.toJSONString(noticeAddDTO)))
                .andDo(print())
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value(200));
    }

    /**
     * 测试新增通知 - 参数验证失败
     */
    @Test
    @DisplayName("测试新增通知-参数验证失败")
    public void testAddNoticeWithInvalidParams() throws Exception {
        SysNoticeAddDTO noticeAddDTO = new SysNoticeAddDTO();
        // 缺少必填字段

        mockMvc.perform(post("/system/notice/add")
                .header("Authorization", getAuthHeader())
                .contentType(MediaType.APPLICATION_JSON)
                .content(JSON.toJSONString(noticeAddDTO)))
                .andDo(print())
                .andExpect(status().is4xxClientError()); // 参数验证失败
    }

    /**
     * 测试编辑通知
     * POST /system/notice/edit
     */
    @Test
    @DisplayName("测试编辑通知")
    public void testUpdateNotice() throws Exception {
        SysNoticeAddDTO noticeAddDTO = new SysNoticeAddDTO();
        noticeAddDTO.setNoticeId(1L); // 需要替换为实际的通知ID
        noticeAddDTO.setNoticeTitle("系统维护通知(已修改)");
        noticeAddDTO.setNoticeType(1);
        noticeAddDTO.setNoticeContent("<p>维护时间已调整为今晚23:00-24:00。</p>");
        noticeAddDTO.setStatus(1);

        mockMvc.perform(post("/system/notice/edit")
                .header("Authorization", getAuthHeader())
                .contentType(MediaType.APPLICATION_JSON)
                .content(JSON.toJSONString(noticeAddDTO)))
                .andDo(print())
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value(200));
    }

    /**
     * 测试删除通知
     * POST /system/notice/remove/{noticeId}
     */
    @Test
    @DisplayName("测试删除通知")
    public void testDeleteNotice() throws Exception {
        Long noticeId = 100L; // 需要替换为实际的通知ID

        mockMvc.perform(post("/system/notice/remove/" + noticeId)
                .header("Authorization", getAuthHeader())
                .contentType(MediaType.APPLICATION_JSON))
                .andDo(print())
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value(200));
    }

    /**
     * 测试批量删除通知
     */
    @Test
    @DisplayName("测试批量删除通知")
    public void testBatchDeleteNotices() throws Exception {
        List<Long> noticeIds = Arrays.asList(100L, 101L, 102L);

        mockMvc.perform(post("/system/notice/removeBatch")
                .header("Authorization", getAuthHeader())
                .contentType(MediaType.APPLICATION_JSON)
                .content(JSON.toJSONString(noticeIds)))
                .andDo(print())
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value(200));
    }

    /**
     * 测试修改通知状态
     * POST /system/notice/changeStatus
     */
    @Test
    @DisplayName("测试修改通知状态")
    public void testChangeNoticeStatus() throws Exception {
        mockMvc.perform(post("/system/notice/changeStatus")
                .header("Authorization", getAuthHeader())
                .param("noticeId", "1")
                .param("status", "0") // 关闭
                .contentType(MediaType.APPLICATION_JSON))
                .andDo(print())
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value(200));
    }

    /**
     * 测试发布通知（修改状态为正常）
     */
    @Test
    @DisplayName("测试发布通知")
    public void testPublishNotice() throws Exception {
        mockMvc.perform(post("/system/notice/changeStatus")
                .header("Authorization", getAuthHeader())
                .param("noticeId", "1")
                .param("status", "1") // 正常/发布
                .contentType(MediaType.APPLICATION_JSON))
                .andDo(print())
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value(200));
    }

    /**
     * 测试撤回通知（修改状态为关闭）
     */
    @Test
    @DisplayName("测试撤回通知")
    public void testWithdrawNotice() throws Exception {
        mockMvc.perform(post("/system/notice/changeStatus")
                .header("Authorization", getAuthHeader())
                .param("noticeId", "1")
                .param("status", "0") // 关闭
                .contentType(MediaType.APPLICATION_JSON))
                .andDo(print())
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value(200));
    }

    /**
     * 测试获取不存在的通知
     */
    @Test
    @DisplayName("测试获取不存在的通知")
    public void testGetNonExistentNotice() throws Exception {
        Long noticeId = 999999L;

        mockMvc.perform(get("/system/notice/" + noticeId)
                .header("Authorization", getAuthHeader())
                .contentType(MediaType.APPLICATION_JSON))
                .andDo(print())
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value(500)); // 通知不存在
    }

    /**
     * 测试无权限访问
     */
    @Test
    @DisplayName("测试无权限访问通知列表")
    public void testUnauthorizedAccess() throws Exception {
        mockMvc.perform(get("/system/notice/list")
                .param("pageNum", "1")
                .param("pageSize", "10"))
                .andDo(print())
                .andExpect(status().isUnauthorized()); // 401未认证
    }

    /**
     * 测试组合条件查询
     */
    @Test
    @DisplayName("测试组合条件查询")
    public void testGetNoticeListWithMultipleConditions() throws Exception {
        mockMvc.perform(get("/system/notice/list")
                .header("Authorization", getAuthHeader())
                .param("pageNum", "1")
                .param("pageSize", "10")
                .param("noticeTitle", "系统")
                .param("noticeType", "1")
                .param("status", "1")
                .param("createBy", "admin")
                .param("beginTime", "2024-01-01")
                .param("endTime", "2024-12-31")
                .contentType(MediaType.APPLICATION_JSON))
                .andDo(print())
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value(200));
    }
}
