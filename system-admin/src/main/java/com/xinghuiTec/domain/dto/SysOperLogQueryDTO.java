package com.xinghuiTec.domain.dto;

import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.Pattern;
import lombok.Data;

import java.util.Date;

/**
 * 操作日志查询 DTO
 * 用于操作日志的分页查询和条件过滤
 * 
 * @author beidoa23
 * @since 2026-01-23
 */
@Data
public class SysOperLogQueryDTO {

    /**
     * 模块标题（模糊查询）
     */
    private String title;

    /**
     * 操作人员（模糊查询）
     */
    private String operName;

    /**
     * 业务类型
     * 0其它 1新增 2修改 3删除 4授权 5导出 6导入
     */
    private Integer businessType;

    /**
     * 操作状态
     * 0正常 1异常
     */
    private Integer status;

    /**
     * 操作时间范围 - 开始时间
     */
    private Date operTimeStart;

    /**
     * 操作时间范围 - 结束时间
     */
    private Date operTimeEnd;

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
     * 默认按操作时间排序
     */
    private String orderBy = "operTime";

    /**
     * 排序方式
     * asc: 升序, desc: 降序
     */
    @Pattern(regexp = "^(asc|desc)$", message = "排序方式只能是 asc 或 desc")
    private String order = "desc";
}
