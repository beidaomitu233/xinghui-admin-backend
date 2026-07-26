package com.xinghuiTec.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.xinghuiTec.domain.entity.SysTenant;
import org.apache.ibatis.annotations.Mapper;

/**
 * 租户信息 Mapper
 *
 * @author xinghuiTec
 */
@Mapper
public interface SysTenantMapper extends BaseMapper<SysTenant> {
}
