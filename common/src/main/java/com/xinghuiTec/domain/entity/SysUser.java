package com.xinghuiTec.domain.entity;

import java.util.Date;

import java.io.Serializable;

import com.fasterxml.jackson.annotation.JsonIgnore;
import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.EqualsAndHashCode;
import lombok.NoArgsConstructor;
import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;

/**
 * 用户信息表(SysUser)表实体类
 *
 * @author beidoa23
 * @since 2025-12-25 19:33:19
 */
@SuppressWarnings("serial")
@Data
@EqualsAndHashCode(callSuper = true)
@AllArgsConstructor
@NoArgsConstructor
@TableName("sys_user")
public class SysUser extends TenantEntity {
    // 用户ID (UUID)
    @TableId(type = IdType.INPUT)
    private String userId;
    // 用户账号
    private String username;
    // 密码(BCrypt加密) - 不返回给前端
    @JsonIgnore
    private String password;
    // 用户昵称
    private String nickname;
    // 用户邮箱
    private String email;
    // 手机号码
    private String mobile;
    // 头像地址
    private String avatar;
    // 帐号状态（1正常 0停用）
    private Integer status;
    // 最后登录IP
    private String loginIp;
    // 最后登录时间
    private Date loginDate;

}
