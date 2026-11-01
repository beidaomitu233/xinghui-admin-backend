package com.xinghuiTec.mapper;

import com.xinghuiTec.domain.entity.SysOperLog;
import com.baomidou.mybatisplus.annotation.InterceptorIgnore;
import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import org.apache.ibatis.annotations.Mapper;
/**
 * 操作日志记录(SysOperLog)表数据库访问层
 *
 * @since 2025-12-25 19:33:19
 */
@Mapper
// Platform-only global ledger; its schema has no tenant_id. Controller method
// authorization still requires platform identity AND the requested log action.
@InterceptorIgnore(tenantLine = "true")
public interface SysOperLogMapper extends BaseMapper<SysOperLog>{

}
