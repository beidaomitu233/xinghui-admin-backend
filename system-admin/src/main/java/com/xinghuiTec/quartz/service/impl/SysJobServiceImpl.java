package com.xinghuiTec.quartz.service.impl;

import com.baomidou.mybatisplus.extension.service.impl.ServiceImpl;
import com.xinghuiTec.quartz.domain.SysJob;
import com.xinghuiTec.quartz.mapper.SysJobMapper;
import com.xinghuiTec.quartz.service.SysJobService;
import org.springframework.stereotype.Service;

/**
 * 定时任务调度表(SysJob)表服务实现类
 */
@Service
public class SysJobServiceImpl extends ServiceImpl<SysJobMapper, SysJob> implements SysJobService {
}
