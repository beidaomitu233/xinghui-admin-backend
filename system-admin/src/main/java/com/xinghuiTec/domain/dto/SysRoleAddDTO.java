package com.xinghuiTec.domain.dto;

import jakarta.validation.constraints.*;
import lombok.Data;

import java.util.List;

/**
 * 角色新增/修改 DTO
 * 
 * @author beidoa23
 * @since 2026-01-23
 */
@Data
public class SysRoleAddDTO {

    /**
     * 角色ID（修改时必填）
     */
    private Long roleId;

    /**
     * 角色名称
     */
    @NotBlank(message = "角色名称不能为空")
    @Size(min = 2, max = 30, message = "角色名称长度必须在2-30之间")
    private String roleName;

    /**
     * 角色权限字符串
     * 如：admin, common
     */
    @NotBlank(message = "角色权限字符串不能为空")
    @Pattern(regexp = "^[a-zA-Z0-9_]+$", message = "角色权限字符串只能包含字母、数字、下划线")
    @Size(max = 100, message = "角色权限字符串长度不能超过100")
    private String roleKey;

    /**
     * 数据范围
     * 1：全部数据权限
     * 2：自定义数据权限
     * 3：本部门数据权限
     * 4：本部门及以下数据权限
     */
    private String dataScope;

    /**
     * 角色状态
     * 1正常 0停用
     */
    @NotNull(message = "角色状态不能为空")
    @Min(value = 0, message = "角色状态只能是0或1")
    @Max(value = 1, message = "角色状态只能是0或1")
    private Integer status;

    /**
     * 菜单ID列表
     * 可选，用于同时分配菜单权限
     */
    private List<Long> menuIds;
}
