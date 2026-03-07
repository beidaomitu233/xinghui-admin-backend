package com.xinghuiTec.domain.dto;

import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.Pattern;
import lombok.Data;

import java.util.Date;

@Data
public class SysUserQueryDTO {

    /** 用户账号 */
    private String username;

    /** 用户名称 */
    private String nickname;

    /** 手机号码 */
    private String mobile;

    /** 帐号状态（1正常 0停用） */
    private Integer status;

    /** 创建时间范围 */
    private Date createTimeStart;
    private Date createTimeEnd;

    /** 当前页码 */
    @Min(value = 1, message = "页码不能小于1")
    private Integer pageNum = 1;

    /** 每页显示条数 */
    @Min(value = 1, message = "每页条数不能小于1")
    @Max(value = 100, message = "每页条数不能超过100")
    private Integer pageSize = 10;

    /** 排序字段 */
    private String orderBy = "createTime";

    /** 排序方式 */
    @Pattern(regexp = "^(asc|desc)$", message = "排序方式只能是 asc 或 desc")
    private String order = "desc";
}