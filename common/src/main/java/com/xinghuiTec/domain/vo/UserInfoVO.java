package com.xinghuiTec.domain.vo;

import com.xinghuiTec.domain.entity.SysUser;
import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.util.List;

/**
 * 用户信息响应VO
 * 用于返回当前登录用户的详细信息
 * 
 * @author 长辉
 * @since 2025-12-27
 */
@Data
@NoArgsConstructor
@AllArgsConstructor
public class UserInfoVO {

    /**
     * 用户基本信息
     */
    private SysUser user;

    /**
     * 角色列表
     * 包含角色ID、角色名称和角色标识的完整信息
     */
    private List<RoleVO> roles;

    /**
     * 权限标识列表
     * 例如: ["system:user:list", "system:user:add"]
     */
    private List<String> permissions;
}
