package com.xinghuiTec.domain.entity;

import java.io.Serializable;
import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.EqualsAndHashCode;
import lombok.NoArgsConstructor;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;

/**
 * 菜单权限表(SysMenu)表实体类
 *
 * @author beidoa23
 * @since 2025-12-25 19:33:19
 */
@SuppressWarnings("serial")
@Data
@EqualsAndHashCode(callSuper = false)
@AllArgsConstructor
@NoArgsConstructor
@TableName("sys_menu")
public class SysMenu extends BaseEntity {
    @TableId
    // 菜单ID
    private Long menuId;

    // 菜单名称
    private String menuName;
    // 父菜单ID
    private Long parentId;
    // 显示顺序
    private Integer orderNum;
    // 路由地址
    private String path;
    // 组件路径(views/system/user/index)
    private String component;
    // 是否为外链（0是 1否）
    private Integer isFrame;
    // 菜单类型（M目录 C菜单 F按钮）
    private String menuType;
    // 菜单状态（0显示 1隐藏）
    private String visible;
    // 菜单状态（0正常 1停用）
    private String status;
    // 权限标识(system:user:list)
    private String perms;
    // 菜单图标
    private String icon;

}
