package com.xinghuiTec.domain.entity;

import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.EqualsAndHashCode;
import lombok.NoArgsConstructor;

/**
 * 租户套餐表
 *
 * @author xinghuiTec
 */
@Data
@EqualsAndHashCode(callSuper = true)
@AllArgsConstructor
@NoArgsConstructor
@TableName("sys_tenant_package")
public class SysTenantPackage extends BaseEntity {
    @TableId
    private Long packageId;

    /** 套餐名称 */
    private String packageName;

    /** 关联菜单ID列表（逗号分隔） */
    private String menuIds;

    /** 备注 */
    private String remark;

    /** 套餐状态（0正常 1停用） */
    private String status;

}
