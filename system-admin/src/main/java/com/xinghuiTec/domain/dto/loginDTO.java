package com.xinghuiTec.domain.dto;

import lombok.Data;

@Data
public class loginDTO {
    // 用户账号
    private String username;
    // 密码(BCrypt加密)
    private String password;
    /**
     * 验证码
     */
    private String code;
    /**
     * 唯一标识
     */
    private String uuid;
}
