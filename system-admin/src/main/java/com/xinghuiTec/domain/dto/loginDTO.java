package com.xinghuiTec.domain.dto;

import lombok.Data;

/**
 * 登录请求 DTO
 * 使用手机号 + 密码登录，不限制租户
 */
@Data
public class loginDTO {
    /**
     * 手机号（登录账号）
     */
    private String phone;

    /**
     * 密码 (BCrypt加密)
     */
    private String password;

    /**
     * 验证码
     */
    private String code;

    /**
     * 唯一标识（验证码关联）
     */
    private String uuid;
}
