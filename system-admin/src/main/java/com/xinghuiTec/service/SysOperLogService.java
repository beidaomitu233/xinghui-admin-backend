package com.xinghuiTec.service;

import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import com.baomidou.mybatisplus.extension.service.IService;
import com.xinghuiTec.domain.dto.SysOperLogQueryDTO;
import com.xinghuiTec.domain.entity.SysOperLog;
import org.springframework.scheduling.annotation.Async;

import java.util.List;

/**
 * 操作日志记录(SysOperLog)表服务接口层
 *
 * @since 2025-12-25 19:33:19
 */
public interface SysOperLogService extends IService<SysOperLog> {

    /**
     * 分页查询操作日志
     * 
     * @param queryDTO 查询条件
     * @return 分页结果
     */
    Page<SysOperLog> getOperLogList(SysOperLogQueryDTO queryDTO);

    /**
     * 批量删除操作日志
     * 
     * @param operIds 日志ID列表
     */
    void deleteOperLog(List<Long> operIds);

    /**
     * 清空操作日志
     */
    void cleanOperLog();

    /**
     * 异步保存操作日志
     * 使用异步方式保存，避免影响主业务性能
     * 
     * @param operLog 操作日志对象
     */
    @Async
    void saveOperLog(SysOperLog operLog);
}
