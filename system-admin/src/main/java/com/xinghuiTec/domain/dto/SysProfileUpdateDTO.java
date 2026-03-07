package com.xinghuiTec.domain.dto;

import jakarta.validation.constraints.Email;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;
import lombok.Data;

/**
 * 个人信息修改请求DTO
 * 用于接收用户修改个人信息的请求参数
 * 
 * 注意：
 * 1. 用户只能修改自己的信息，userId 从 SecurityContext 获取
 * 2. 用户账号（username）不允许修改
 * 3. 所有字段都是可选的，只修改传入的字段
 *
 * @author beidoa23
 * @since 2026-01-22
 */
@Data
public class SysProfileUpdateDTO {

    /**
     * 用户昵称
     * 可选字段，长度2-30个字符
     */
    @Size(min = 2, max = 30, message = "昵称长度必须在2-30个字符之间")
    private String nickname;

    /**
     * 用户邮箱
     * 可选字段，必须符合邮箱格式
     * 修改时会校验邮箱唯一性（排除自己）
     */
    @Email(message = "邮箱格式不正确")
    @Size(max = 50, message = "邮箱长度不能超过50个字符")
    private String email;

    /**
     * 手机号码
     * 可选字段，必须符合中国大陆手机号格式（11位数字）
     * 修改时会校验手机号唯一性（排除自己）
     */
    @Pattern(regexp = "^1[3-9]\\d{9}$", message = "手机号格式不正确")
    private String mobile;
}
