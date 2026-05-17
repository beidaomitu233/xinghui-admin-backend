package com.xinghuiTec.service;

import com.baomidou.mybatisplus.extension.service.IService;
import com.xinghuiTec.domain.entity.SysTenantPackage;

import java.util.List;

/**
 * 租户套餐服务接口
 *
 * @author xinghuiTec
 */
public interface ISysTenantPackageService extends IService<SysTenantPackage> {

    /**
     * 查询租户套餐
     */
    SysTenantPackage queryById(Long packageId);

    /**
     * 查询租户套餐列表
     */
    List<SysTenantPackage> queryList(SysTenantPackage tenantPackage);

    /**
     * 新增租户套餐
     */
    Boolean insertByBo(SysTenantPackage tenantPackage);

    /**
     * 修改租户套餐
     */
    Boolean updateByBo(SysTenantPackage tenantPackage);

    /**
     * 批量删除租户套餐
     */
    Boolean deleteWithValidByIds(List<Long> ids, Boolean isValid);
}
