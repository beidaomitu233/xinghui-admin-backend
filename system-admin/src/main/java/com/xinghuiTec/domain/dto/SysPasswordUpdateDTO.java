package com.xinghuiTec.domain.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;
import lombok.Data;

/**
 * 修改密码请求DTO
 * 用于接收用户修改密码的请求参数
 * 
 * 安全要求：
 * 1. 必须验证旧密码，防止恶意修改
 * 2. 新密码必须符合强度要求
 * 3. 确认密码必须与新密码一致
 * 4. 修改密码后建议清除登录缓存，强制重新登录
 *
 * @author beidoa23
 * @since 2026-01-22
 */
@Data
public class SysPasswordUpdateDTO {

    /**
     * 旧密码
     * 必填，用于验证用户身份
     */
    @NotBlank(message = "旧密码不能为空")
    private String oldPassword;

    /**
     * 新密码
     * 必填，长度6-20个字符
     * 建议包含数字、字母、特殊字符
     */
    @NotBlank(message = "新密码不能为空")
    @Size(min = 6, max = 20, message = "新密码长度必须在6-20个字符之间")
    @Pattern(regexp = "^(?=.*[0-9])(?=.*[a-zA-Z]).{6,20}$", message = "新密码必须包含数字和字母")
    private String newPassword;

    /**
     * 确认密码
     * 必填，必须与新密码一致
     * 一致性校验在 Service 层进行
     */
    @NotBlank(message = "确认密码不能为空")
    private String confirmPassword;
}
