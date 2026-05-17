package com.xinghuiTec.service;

import com.baomidou.mybatisplus.extension.service.IService;
import com.xinghuiTec.domain.entity.SysTenant;

import java.util.List;

/**
 * 租户服务接口
 *
 * @author xinghuiTec
 */
public interface ISysTenantService extends IService<SysTenant> {

    /**
     * 查询租户
     */
    SysTenant queryById(Long id);

    /**
     * 基于租户ID查询租户
     */
    SysTenant queryByTenantId(String tenantId);

    /**
     * 查询租户列表
     */
    List<SysTenant> queryList(SysTenant tenant);

    /**
     * 新增租户
     */
    Boolean insertByBo(SysTenant tenant, String username, String password);

    /**
     * 修改租户
     */
    Boolean updateByBo(SysTenant tenant);

    /**
     * 修改租户状态
     */
    int updateTenantStatus(SysTenant tenant);

    /**
     * 批量删除租户
     */
    Boolean deleteWithValidByIds(List<Long> ids, Boolean isValid);

    /**
     * 校验企业名称是否唯一
     */
    boolean checkCompanyNameUnique(SysTenant tenant);

    /**
     * 校验账号余额
     */
    boolean checkAccountBalance(String tenantId);

    /**
     * 校验有效期
     */
    boolean checkExpireTime(String tenantId);
}
