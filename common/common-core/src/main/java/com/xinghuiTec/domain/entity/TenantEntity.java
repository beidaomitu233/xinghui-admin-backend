package com.xinghuiTec.domain.entity;

import lombok.Data;
import lombok.EqualsAndHashCode;

/**
 * 租户实体基类
 * 所有需要租户隔离的实体都应继承此类
 *
 * @author xinghuiTec
 */
@Data
@EqualsAndHashCode(callSuper = true)
public class TenantEntity extends BaseEntity {

    /**
     * 租户编号
     */
    private String tenantId;

}
