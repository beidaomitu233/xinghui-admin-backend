package com.xinghuiTec.quartz.service.impl;

import com.baomidou.mybatisplus.extension.service.impl.ServiceImpl;
import com.xinghuiTec.quartz.domain.SysJobLog;
import com.xinghuiTec.quartz.mapper.SysJobLogMapper;
import com.xinghuiTec.quartz.service.SysJobLogService;
import org.springframework.stereotype.Service;

/**
 * 定时任务调度日志表(SysJobLog)表服务实现类
 */
@Service
public class SysJobLogServiceImpl extends ServiceImpl<SysJobLogMapper, SysJobLog> implements SysJobLogService {
}
