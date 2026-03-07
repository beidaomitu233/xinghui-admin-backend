package com.xinghuiTec.service;

import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import com.baomidou.mybatisplus.extension.service.IService;
import com.xinghuiTec.domain.dto.SysRoleAddDTO;
import com.xinghuiTec.domain.dto.SysRoleQueryDTO;
import com.xinghuiTec.domain.entity.SysRole;

/**
 * 角色信息表(SysRole)表服务接口层
 *
 * @since 2025-12-25 19:33:19
 */
public interface SysRoleService extends IService<SysRole> {

    /**
     * 分页查询角色列表
     * 
     * @param queryDTO 查询条件
     * @return 分页结果
     */
    Page<SysRole> getRoleList(SysRoleQueryDTO queryDTO);

    /**
     * 新增角色
     * 
     * @param addDTO 角色信息
     * @return 角色ID
     */
    Long addRole(SysRoleAddDTO addDTO);

    /**
     * 修改角色
     * 
     * @param addDTO 角色信息（必须包含roleId）
     */
    void updateRole(SysRoleAddDTO addDTO);

    /**
     * 删除角色
     * 
     * @param roleId 角色ID
     */
    void deleteRole(Long roleId);

    /**
     * 查询角色详情
     * 
     * @param roleId 角色ID
     * @return 角色信息
     */
    SysRole getRoleById(Long roleId);

    /**
     * 修改角色状态
     * 
     * @param roleId 角色ID
     * @param status 状态
     */
    void updateRoleStatus(Long roleId, Integer status);

    /**
     * 检查角色名称是否唯一
     * 
     * @param roleName 角色名称
     * @param roleId   角色ID（排除自己）
     * @return true-唯一，false-不唯一
     */
    boolean checkRoleNameUnique(String roleName, Long roleId);

    /**
     * 检查角色权限字符串是否唯一
     * 
     * @param roleKey 角色权限字符串
     * @param roleId  角色ID（排除自己）
     * @return true-唯一，false-不唯一
     */
    boolean checkRoleKeyUnique(String roleKey, Long roleId);
}
