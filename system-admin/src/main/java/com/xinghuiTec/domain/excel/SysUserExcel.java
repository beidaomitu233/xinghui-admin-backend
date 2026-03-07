package com.xinghuiTec.domain.excel;

import cn.idev.excel.annotation.ExcelProperty;
import lombok.Data;

import java.io.Serializable;

/**
 * 用户Excel导入导出数据模型
 * 用于映射Excel文件中的用户数据
 *
 * @author beidoa23
 * @since 2025-12-31
 */
@Data
public class SysUserExcel implements Serializable {

    @ExcelProperty("用户账号")
    private String username;

    @ExcelProperty("用户昵称")
    private String nickname;

    @ExcelProperty("邮箱")
    private String email;

    @ExcelProperty("手机号码")
    private String mobile;

    @ExcelProperty("用户状态")
    private String status;

    @ExcelProperty("角色标识")
    private String roleKeys;

    @ExcelProperty("角色名称")
    private String roleNames;
}
