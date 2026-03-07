package com.xinghuiTec.mapper;

import com.xinghuiTec.domain.entity.SysRoleMenu;
import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import org.apache.ibatis.annotations.Mapper;
/**
 * 角色和菜单关联表(SysRoleMenu)表数据库访问层
 *
 * @since 2025-12-25 19:33:19
 */
@Mapper
public interface SysRoleMenuMapper extends BaseMapper<SysRoleMenu>{

}
