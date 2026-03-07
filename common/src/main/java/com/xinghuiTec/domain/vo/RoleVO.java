package com.xinghuiTec.domain.vo;

import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;

/**
 * 角色信息VO
 * 用于返回角色的完整信息
 * 
 * @author 长辉
 * @since 2025-12-31
 */
@Data
@NoArgsConstructor
@AllArgsConstructor
public class RoleVO {

    /**
     * 角色ID
     */
    private Long roleId;

    /**
     * 角色名称
     * 例如: "超级管理员"
     */
    private String roleName;

    /**
     * 角色标识
     * 例如: "admin"
     */
    private String roleKey;
}
