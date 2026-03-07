package com.xinghuiTec.domain.entity;

import java.io.Serializable;
import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.EqualsAndHashCode;
import lombok.NoArgsConstructor;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;

/**
 * 角色信息表(SysRole)表实体类
 *
 * @author beidoa23
 * @since 2025-12-25 19:33:19
 */
@SuppressWarnings("serial")
@Data
@EqualsAndHashCode(callSuper = false)
@AllArgsConstructor
@NoArgsConstructor
@TableName("sys_role")
public class SysRole extends BaseEntity {
    @TableId
    // 角色ID
    private Long roleId;

    // 角色名称
    private String roleName;
    // 角色权限字符串(admin, common)
    private String roleKey;
    // 数据范围（1：全部数据权限 2：自定数据权限 3：本部门数据权限 4：本部门及以下数据权限）
    private String dataScope;
    // 角色状态（1正常 0停用）
    private Integer status;

}
