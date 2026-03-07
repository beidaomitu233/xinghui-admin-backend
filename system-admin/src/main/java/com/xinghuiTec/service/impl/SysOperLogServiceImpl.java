package com.xinghuiTec.service.impl;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import com.baomidou.mybatisplus.extension.service.impl.ServiceImpl;
import com.xinghuiTec.domain.dto.SysOperLogQueryDTO;
import com.xinghuiTec.mapper.SysOperLogMapper;
import com.xinghuiTec.domain.entity.SysOperLog;
import com.xinghuiTec.service.SysOperLogService;
import lombok.extern.slf4j.Slf4j;
import org.springframework.scheduling.annotation.Async;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;

/**
 * 操作日志记录(SysOperLog)表服务实现类
 */
@Service
@Slf4j
public class SysOperLogServiceImpl extends ServiceImpl<SysOperLogMapper, SysOperLog> implements SysOperLogService {

    /**
     * 分页查询操作日志
     * 支持多条件组合查询和动态排序
     * 
     * @param queryDTO 查询条件
     * @return 分页结果
     */
    @Override
    public Page<SysOperLog> getOperLogList(SysOperLogQueryDTO queryDTO) {
        // 构建分页对象
        Page<SysOperLog> page = new Page<>(queryDTO.getPageNum(), queryDTO.getPageSize());

        // 构建查询条件
        LambdaQueryWrapper<SysOperLog> wrapper = new LambdaQueryWrapper<>();

        // 模块标题模糊查询
        wrapper.like(queryDTO.getTitle() != null && !queryDTO.getTitle().isEmpty(),
                SysOperLog::getTitle, queryDTO.getTitle());

        // 操作人员模糊查询
        wrapper.like(queryDTO.getOperName() != null && !queryDTO.getOperName().isEmpty(),
                SysOperLog::getOperName, queryDTO.getOperName());

        // 业务类型精确查询
        wrapper.eq(queryDTO.getBusinessType() != null,
                SysOperLog::getBusinessType, queryDTO.getBusinessType());

        // 操作状态精确查询
        wrapper.eq(queryDTO.getStatus() != null,
                SysOperLog::getStatus, queryDTO.getStatus());

        // 操作时间范围查询
        wrapper.ge(queryDTO.getOperTimeStart() != null,
                SysOperLog::getOperTime, queryDTO.getOperTimeStart());
        wrapper.le(queryDTO.getOperTimeEnd() != null,
                SysOperLog::getOperTime, queryDTO.getOperTimeEnd());

        // 动态排序
        if ("asc".equalsIgnoreCase(queryDTO.getOrder())) {
            wrapper.orderByAsc(SysOperLog::getOperTime);
        } else {
            wrapper.orderByDesc(SysOperLog::getOperTime);
        }

        // 执行查询
        return this.page(page, wrapper);
    }

    /**
     * 批量删除操作日志
     * 
     * @param operIds 日志ID列表
     */
    @Override
    @Transactional(rollbackFor = Exception.class)
    public void deleteOperLog(List<Long> operIds) {
        if (operIds == null || operIds.isEmpty()) {
            return;
        }
        this.removeByIds(operIds);
        log.info("批量删除操作日志，删除数量: {}", operIds.size());
    }

    /**
     * 清空操作日志
     */
    @Override
    @Transactional(rollbackFor = Exception.class)
    public void cleanOperLog() {
        this.remove(new LambdaQueryWrapper<>());
        log.info("清空操作日志表");
    }

    /**
     * 异步保存操作日志
     * 使用异步方式保存，避免影响主业务性能
     * 如果保存失败，只记录错误日志，不影响业务流程
     * 
     * @param operLog 操作日志对象
     */
    @Override
    @Async
    public void saveOperLog(SysOperLog operLog) {
        try {
            this.save(operLog);
            log.debug("操作日志保存成功: {}", operLog.getTitle());
        } catch (Exception e) {
            log.error("操作日志保存失败: {}", operLog.getTitle(), e);
        }
    }
}
