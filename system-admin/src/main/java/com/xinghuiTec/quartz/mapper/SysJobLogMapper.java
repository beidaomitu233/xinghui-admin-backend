package com.xinghuiTec.quartz.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.xinghuiTec.quartz.domain.SysJobLog;
import org.apache.ibatis.annotations.Mapper;

/**
 * 定时任务调度日志表(SysJobLog)表数据库访问层
 */
@Mapper
public interface SysJobLogMapper extends BaseMapper<SysJobLog> {
}
