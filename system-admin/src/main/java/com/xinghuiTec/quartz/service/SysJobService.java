package com.xinghuiTec.quartz.service;

import com.baomidou.mybatisplus.extension.service.IService;
import com.xinghuiTec.quartz.domain.SysJob;
import com.xinghuiTec.quartz.domain.JobChangeRequest;
import java.util.List;

/**
 * 定时任务调度表(SysJob)表服务接口层
 */
public interface SysJobService extends IService<SysJob> {
    Long addManaged(JobChangeRequest request);
    void updateManaged(JobChangeRequest request);
    SysJob getManaged(Long jobId);
    void removeManaged(List<Long> jobIds);
    void changeStatusManaged(Long jobId, String status);
}
