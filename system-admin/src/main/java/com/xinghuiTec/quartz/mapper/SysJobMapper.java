package com.xinghuiTec.quartz.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.xinghuiTec.quartz.domain.SysJob;
import org.apache.ibatis.annotations.Mapper;

/**
 * 定时任务调度表(SysJob)表数据库访问层
 */
@Mapper
public interface SysJobMapper extends BaseMapper<SysJob> {
}
