package com.xinghuiTec.domain.entity;

import java.util.Date;

import java.io.Serializable;
import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.EqualsAndHashCode;
import lombok.NoArgsConstructor;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;

/**
 * 操作日志记录(SysOperLog)表实体类
 *
 * @author beidoa23
 * @since 2025-12-25 19:33:19
 */
@SuppressWarnings("serial")
@Data
@EqualsAndHashCode(callSuper = false)
@AllArgsConstructor
@NoArgsConstructor
@TableName("sys_oper_log")
public class SysOperLog implements Serializable {
    @TableId
    // 日志主键
    private Long operId;

    // 模块标题(如: 用户管理)
    private String title;
    // 业务类型(0其它 1新增 2修改 3删除 4授权 5导出 6导入)
    private Integer businessType;
    // 方法名称
    private String method;
    // 请求方式(GET/POST)
    private String requestMethod;
    // 操作人员
    private String operName;
    // 请求URL
    private String operUrl;
    // 主机地址
    private String operIp;
    // 请求参数
    private String operParam;
    // 返回参数
    private String jsonResult;
    // 操作状态(0正常 1异常)
    private Integer status;
    // 错误消息
    private String errorMsg;
    // 消耗时间(毫秒)
    private Long costTime;
    // 操作时间
    private Date operTime;

}
