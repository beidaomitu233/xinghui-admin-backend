package com.xinghuiTec.domain.dto;

import jakarta.validation.constraints.*;
import lombok.Data;

/**
 * 新增/修改菜单请求DTO
 * 用于接收新增或修改菜单时的请求参数
 * 支持三种菜单类型：M(目录)、C(菜单)、F(按钮)
 *
 * @author beidoa23
 * @since 2026-01-22
 */
@Data
public class SysMenuAddDTO {

    /**
     * 菜单ID
     * 修改菜单时必填，新增菜单时不需要
     */
    private Long menuId;

    /**
     * 菜单名称
     * 必填，长度2-50字符
     */
    @NotBlank(message = "菜单名称不能为空")
    @Size(min = 2, max = 50, message = "菜单名称长度必须在2-50个字符之间")
    private String menuName;

    /**
     * 父菜单ID
     * 默认为0，表示顶级菜单
     * 非顶级菜单需要指定有效的父菜单ID
     */
    private Long parentId = 0L;

    /**
     * 显示顺序
     * 数值越小，显示越靠前
     * 默认为0
     */
    @Min(value = 0, message = "显示顺序不能小于0")
    private Integer orderNum = 0;

    /**
     * 路由地址
     * 目录(M)和菜单(C)类型必填
     * 外链则以http(s)://开头
     * 示例：system（目录的路由）、user（菜单的路由）
     */
    @Size(max = 200, message = "路由地址长度不能超过200个字符")
    private String path;

    /**
     * 组件路径
     * 菜单(C)类型必填，目录(M)和按钮(F)类型不需要
     * 示例：views/system/user/index
     */
    @Size(max = 255, message = "组件路径长度不能超过255个字符")
    private String component;

    /**
     * 是否为外链
     * 0: 是外链（点击后在新窗口打开）
     * 1: 否（默认，系统内部路由）
     */
    @Min(value = 0, message = "是否外链值只能是0或1")
    @Max(value = 1, message = "是否外链值只能是0或1")
    private Integer isFrame = 1;

    /**
     * 菜单类型
     * M: 目录（可以包含子菜单的容器）
     * C: 菜单（对应具体页面）
     * F: 按钮（页面内的操作权限）
     */
    @NotBlank(message = "菜单类型不能为空")
    @Pattern(regexp = "^[MCF]$", message = "菜单类型只能是M（目录）、C（菜单）或F（按钮）")
    private String menuType;

    /**
     * 显示状态
     * 0: 显示（默认）
     * 1: 隐藏（不在菜单中显示，但路由仍可访问）
     */
    @Pattern(regexp = "^[01]$", message = "显示状态只能是0（显示）或1（隐藏）")
    private String visible = "0";

    /**
     * 菜单状态
     * 0: 正常（默认）
     * 1: 停用（菜单不可用，无法访问）
     */
    @Pattern(regexp = "^[01]$", message = "菜单状态只能是0（正常）或1（停用）")
    private String status = "0";

    /**
     * 权限标识
     * 用于控制前端按钮显示和后端接口访问权限
     * 格式：模块:功能:操作，例如 system:user:list
     * 按钮(F)类型必须设置，目录(M)和菜单(C)类型可选
     */
    @Size(max = 100, message = "权限标识长度不能超过100个字符")
    @Pattern(regexp = "^([a-zA-Z][a-zA-Z0-9]*:)*[a-zA-Z][a-zA-Z0-9]*$|^$", message = "权限标识格式不正确，应为 module:function:action 格式")
    private String perms;

    /**
     * 菜单图标
     * 用于在菜单栏显示图标
     * 目录(M)和菜单(C)类型建议设置，按钮(F)类型不需要
     * 示例：system、user、edit
     */
    @Size(max = 100, message = "菜单图标长度不能超过100个字符")
    private String icon;
}
