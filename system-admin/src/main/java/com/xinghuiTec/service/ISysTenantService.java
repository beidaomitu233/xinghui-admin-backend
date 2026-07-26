package com.xinghuiTec.service;

import com.baomidou.mybatisplus.extension.service.IService;
import com.xinghuiTec.domain.entity.SysTenant;

/**
 * 租户信息 Service
 *
 * @author xinghuiTec
 */
public interface ISysTenantService extends IService<SysTenant> {

    /**
     * 根据租户编号查询租户
     */
    SysTenant queryByTenantId(String tenantId);

    /**
     * 校验租户是否有效
     */
    void checkTenant(String tenantId);
}
