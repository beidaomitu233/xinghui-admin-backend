package com.xinghuiTec.domain.dto;

import jakarta.validation.constraints.*;
import lombok.Data;

import java.util.Date;

/**
 * 角色查询 DTO
 * 用于角色的分页查询和条件过滤
 * 
 * @author beidoa23
 * @since 2026-01-23
 */
@Data
public class SysRoleQueryDTO {

    /**
     * 角色名称（模糊查询）
     */
    private String roleName;

    /**
     * 角色权限字符串（模糊查询）
     */
    private String roleKey;

    /**
     * 角色状态
     * 1正常 0停用
     */
    private Integer status;

    /**
     * 创建时间范围 - 开始时间
     */
    private Date createTimeStart;

    /**
     * 创建时间范围 - 结束时间
     */
    private Date createTimeEnd;

    /**
     * 当前页码
     */
    @Min(value = 1, message = "页码不能小于1")
    private Integer pageNum = 1;

    /**
     * 每页显示条数
     */
    @Min(value = 1, message = "每页条数不能小于1")
    @Max(value = 100, message = "每页条数不能超过100")
    private Integer pageSize = 10;

    /**
     * 排序字段
     * 默认按创建时间排序
     */
    private String orderBy = "createTime";

    /**
     * 排序方式
     * asc: 升序, desc: 降序
     */
    @Pattern(regexp = "^(asc|desc)$", message = "排序方式只能是 asc 或 desc")
    private String order = "desc";
}
