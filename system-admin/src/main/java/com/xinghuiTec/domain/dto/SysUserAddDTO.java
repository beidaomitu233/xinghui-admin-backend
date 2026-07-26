package com.xinghuiTec.domain.dto;

import jakarta.validation.constraints.*;
import lombok.Data;

import java.util.List;

/**
 * 新增用户请求DTO
 * 用于接收新增用户时的请求参数，包含用户基本信息和角色分配
 *
 * @author beidoa23
 * @since 2025-12-31
 */
@Data
public class SysUserAddDTO {

    /** 用户ID（修改用户时必填，新增用户时不需要） */
    private Long userId;

    /** 用户账号（必填） */
    @NotBlank(message = "用户账号不能为空")
    @Size(min = 2, max = 30, message = "用户账号长度必须在2-30个字符之间")
    @Pattern(regexp = "^[a-zA-Z0-9_]+$", message = "用户账号只能包含字母、数字和下划线")
    private String username;

    /** 密码（必填） */
    @NotBlank(message = "密码不能为空")
    @Size(min = 6, max = 20, message = "密码长度必须在6-20个字符之间")
    private String password;

    /** 用户昵称 */
    @Size(max = 30, message = "用户昵称长度不能超过30个字符")
    private String nickname;

    /** 用户邮箱 */
    @Email(message = "邮箱格式不正确")
    @Size(max = 50, message = "邮箱长度不能超过50个字符")
    private String email;

    /** 手机号码 */
    @Pattern(regexp = "^1[3-9]\\d{9}$", message = "手机号码格式不正确")
    private String mobile;

    /** 头像地址 */
    @Size(max = 200, message = "头像地址长度不能超过200个字符")
    private String avatar;

    /** 帐号状态（1正常 0停用），默认为1 */
    @Min(value = 0, message = "状态值只能是0或1")
    @Max(value = 1, message = "状态值只能是0或1")
    private Integer status = 1;

    /** 角色ID列表 */
    private List<Long> roleIds;
}
