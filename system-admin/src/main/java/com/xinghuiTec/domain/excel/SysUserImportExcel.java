package com.xinghuiTec.domain.excel;

import cn.idev.excel.annotation.ExcelProperty;
import lombok.Data;

/** Import-only worksheet contract. Export fields remain independent from this input model. */
@Data
public class SysUserImportExcel {
    @ExcelProperty("用户账号")
    private String username;

    @ExcelProperty("用户昵称")
    private String nickname;

    @ExcelProperty("邮箱")
    private String email;

    @ExcelProperty("手机号码")
    private String mobile;

    @ExcelProperty("角色标识")
    private String roleKeys;
}
