package com.xinghuiTec.service;

import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import com.xinghuiTec.domain.dto.SysOperLogQueryDTO;
import com.xinghuiTec.domain.entity.SysOperLog;
import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.SpringBootTest;
import jakarta.annotation.Resource;

import java.util.ArrayList;
import java.util.Date;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

/**
 * 操作日志服务测试类
 * 
 * @author beidoa23
 * @since 2026-01-23
 */
@SpringBootTest
public class SysOperLogServiceTest {

    @Resource
    private SysOperLogService sysOperLogService;

    /**
     * 测试保存操作日志
     */
    @Test
    public void testSaveOperLog() {
        // 创建测试日志
        SysOperLog operLog = new SysOperLog();
        operLog.setTitle("测试模块");
        operLog.setBusinessType(1); // 新增
        operLog.setMethod("com.xinghuiTec.controller.TestController.testMethod");
        operLog.setRequestMethod("POST");
        operLog.setOperName("admin");
        operLog.setOperUrl("/test/add");
        operLog.setOperIp("127.0.0.1");
        operLog.setOperParam("{\"name\":\"test\"}");
        operLog.setJsonResult("{\"code\":200,\"msg\":\"success\"}");
        operLog.setStatus(0);
        operLog.setCostTime(100L);
        operLog.setOperTime(new Date());

        // 保存日志
        sysOperLogService.saveOperLog(operLog);

        // 等待异步操作完成
        try {
            Thread.sleep(1000);
        } catch (InterruptedException e) {
            e.printStackTrace();
        }

        // 验证日志是否保存成功
        SysOperLogQueryDTO queryDTO = new SysOperLogQueryDTO();
        queryDTO.setTitle("测试模块");
        Page<SysOperLog> page = sysOperLogService.getOperLogList(queryDTO);

        assertTrue(page.getRecords().size() > 0, "日志应该保存成功");
    }

    /**
     * 测试分页查询操作日志
     */
    @Test
    public void testGetOperLogList() {
        // 创建查询条件
        SysOperLogQueryDTO queryDTO = new SysOperLogQueryDTO();
        queryDTO.setPageNum(1);
        queryDTO.setPageSize(10);
        queryDTO.setOrder("desc");

        // 执行查询
        Page<SysOperLog> page = sysOperLogService.getOperLogList(queryDTO);

        // 验证结果
        assertNotNull(page, "分页结果不应为空");
        assertTrue(page.getSize() <= 10, "每页条数应不超过10");
    }

    /**
     * 测试按标题模糊查询
     */
    @Test
    public void testGetOperLogListByTitle() {
        // 先插入一条测试数据
        SysOperLog operLog = new SysOperLog();
        operLog.setTitle("用户管理");
        operLog.setBusinessType(1);
        operLog.setOperName("testUser");
        operLog.setOperUrl("/system/user/add");
        operLog.setOperIp("127.0.0.1");
        operLog.setStatus(0);
        operLog.setCostTime(50L);
        operLog.setOperTime(new Date());
        sysOperLogService.save(operLog);

        // 按标题查询
        SysOperLogQueryDTO queryDTO = new SysOperLogQueryDTO();
        queryDTO.setTitle("用户");
        Page<SysOperLog> page = sysOperLogService.getOperLogList(queryDTO);

        // 验证结果
        assertNotNull(page, "查询结果不应为空");
        assertTrue(page.getRecords().size() > 0, "应该能查询到包含'用户'的日志");
    }

    /**
     * 测试按业务类型查询
     */
    @Test
    public void testGetOperLogListByBusinessType() {
        // 按业务类型查询（新增）
        SysOperLogQueryDTO queryDTO = new SysOperLogQueryDTO();
        queryDTO.setBusinessType(1);
        Page<SysOperLog> page = sysOperLogService.getOperLogList(queryDTO);

        // 验证结果
        assertNotNull(page, "查询结果不应为空");
        page.getRecords().forEach(log -> {
            assertEquals(1, log.getBusinessType(), "所有日志的业务类型应为新增(1)");
        });
    }

    /**
     * 测试批量删除操作日志
     */
    @Test
    public void testDeleteOperLog() {
        // 先插入几条测试数据
        List<Long> operIds = new ArrayList<>();
        for (int i = 0; i < 3; i++) {
            SysOperLog operLog = new SysOperLog();
            operLog.setTitle("待删除日志" + i);
            operLog.setBusinessType(0);
            operLog.setOperName("testUser");
            operLog.setOperUrl("/test/delete");
            operLog.setOperIp("127.0.0.1");
            operLog.setStatus(0);
            operLog.setCostTime(10L);
            operLog.setOperTime(new Date());
            sysOperLogService.save(operLog);
            operIds.add(operLog.getOperId());
        }

        // 执行批量删除
        sysOperLogService.deleteOperLog(operIds);

        // 验证删除结果
        operIds.forEach(id -> {
            SysOperLog deleted = sysOperLogService.getById(id);
            assertNull(deleted, "日志应该已被删除");
        });
    }

    /**
     * 测试清空操作日志
     * 注意：此测试会清空所有日志，谨慎使用
     */
    // @Test
    public void testCleanOperLog() {
        // 清空所有日志
        sysOperLogService.cleanOperLog();

        // 验证清空结果
        SysOperLogQueryDTO queryDTO = new SysOperLogQueryDTO();
        Page<SysOperLog> page = sysOperLogService.getOperLogList(queryDTO);

        assertEquals(0, page.getTotal(), "清空后应该没有任何日志");
    }
}
