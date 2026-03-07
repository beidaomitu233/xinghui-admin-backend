package com.xinghuiTec.domain.dto;

import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;
import lombok.Data;

/**
 * 查询菜单请求DTO
 * 用于接收查询菜单列表时的查询条件
 * 支持多条件组合查询
 *
 * @author beidoa23
 * @since 2026-01-22
 */
@Data
public class SysMenuQueryDTO {

    /**
     * 菜单名称
     * 支持模糊查询，不区分大小写
     */
    @Size(max = 50, message = "菜单名称长度不能超过50个字符")
    private String menuName;

    /**
     * 菜单状态
     * 0: 正常
     * 1: 停用
     * null: 查询全部
     */
    @Pattern(regexp = "^[01]?$", message = "菜单状态只能是0（正常）或1（停用）")
    private String status;

    /**
     * 菜单类型
     * M: 目录
     * C: 菜单
     * F: 按钮
     * null: 查询全部
     */
    @Pattern(regexp = "^[MCF]$", message = "菜单类型只能是M（目录）、C（菜单）或F（按钮）")
    private String menuType;

    /**
     * 显示状态
     * 0: 显示
     * 1: 隐藏
     * null: 查询全部
     */
    @Pattern(regexp = "^[01]$", message = "显示状态只能是0（显示）或1（隐藏）")
    private String visible;
}
