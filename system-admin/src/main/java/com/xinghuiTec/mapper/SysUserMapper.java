package com.xinghuiTec.mapper;

import com.xinghuiTec.domain.entity.SysUser;
import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import org.apache.ibatis.annotations.Mapper;
/**
 * 用户信息表(SysUser)表数据库访问层
 *
 * @since 2025-12-25 19:33:19
 */
@Mapper
public interface SysUserMapper extends BaseMapper<SysUser>{

}
