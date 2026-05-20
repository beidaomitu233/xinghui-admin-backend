package com.xinghuiTec.domain.entity;

import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.EqualsAndHashCode;
import lombok.NoArgsConstructor;

import java.util.Date;

/**
 * 租户信息表
 *
 * @author xinghuiTec
 */
@Data
@EqualsAndHashCode(callSuper = true)
@AllArgsConstructor
@NoArgsConstructor
@TableName("sys_tenant")
public class SysTenant extends BaseEntity {
    @TableId
    private Long id;

    /** 租户编号 */
    private String tenantId;

    /** 联系人 */
    private String contactUserName;

    /** 联系电话 */
    private String contactPhone;

    /** 企业名称 */
    private String companyName;

    /** 企业地址 */
    private String address;

    /** 统一社会信用代码 */
    private String licenseNumber;

    /** 域名 */
    private String domain;

    /** 备注 */
    private String intro;

    /** 租户套餐ID */
    private Long packageId;

    /** 过期时间 */
    private Date expireTime;

    /** 用户数量（-1不限制） */
    private Long accountCount;

    /** 租户状态（0正常 1停用） */
    private String status;

}
