package com.xinghuiTec.mapper;

import com.xinghuiTec.domain.entity.SysMenu;
import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import org.apache.ibatis.annotations.Mapper;

import java.util.List;

/**
 * 菜单权限表(SysMenu)数据库访问层
 * 使用MyBatis-Plus的BaseMapper提供基础CRUD操作
 * 仅保留必须使用自定义SQL的方法
 *
 * @author beidoa23
 * @since 2025-12-25 19:33:19
 */
@Mapper
public interface SysMenuMapper extends BaseMapper<SysMenu> {

    /**
     * 根据角色ID查询权限标识列表
     * 需要关联查询sys_role_menu表，因此保留自定义SQL
     *
     * @param roleId 角色ID
     * @return 权限标识列表
     */
    List<String> selectPermsByUserId(Long roleId);

    /**
     * 根据多个角色ID批量查询权限标识
     */
    List<String> selectPermsByRoleIds(@org.apache.ibatis.annotations.Param("roleIds") List<Long> roleIds);

    /**
     * 根据用户ID查询路由菜单
     */
    List<SysMenu> selectMenuByUserId(@org.apache.ibatis.annotations.Param("userId") Long userId);

}
