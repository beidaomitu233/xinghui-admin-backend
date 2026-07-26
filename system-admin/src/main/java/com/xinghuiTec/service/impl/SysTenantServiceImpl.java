package com.xinghuiTec.service.impl;

import cn.hutool.core.util.ObjectUtil;
import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.extension.service.impl.ServiceImpl;
import com.xinghuiTec.constants.TenantConstants;
import com.xinghuiTec.domain.entity.SysTenant;
import com.xinghuiTec.exception.TenantException;
import com.xinghuiTec.mapper.SysTenantMapper;
import com.xinghuiTec.service.ISysTenantService;
import org.springframework.stereotype.Service;

import java.util.Date;

/**
 * 租户信息 Service 实现
 *
 * @author xinghuiTec
 */
@Service
public class SysTenantServiceImpl extends ServiceImpl<SysTenantMapper, SysTenant> implements ISysTenantService {

    @Override
    public SysTenant queryByTenantId(String tenantId) {
        LambdaQueryWrapper<SysTenant> wrapper = new LambdaQueryWrapper<>();
        wrapper.eq(SysTenant::getTenantId, tenantId);
        return baseMapper.selectOne(wrapper);
    }

    @Override
    public void checkTenant(String tenantId) {
        if (tenantId == null || tenantId.isBlank()) {
            throw new TenantException("tenant.number.not.blank");
        }
        if (TenantConstants.DEFAULT_TENANT_ID.equals(tenantId)) {
            return;
        }

        SysTenant tenant = queryByTenantId(tenantId);
        if (ObjectUtil.isNull(tenant)) {
            throw new TenantException("tenant.not.exists");
        }
        if ("1".equals(tenant.getStatus())) {
            throw new TenantException("tenant.blocked");
        }
        if (tenant.getExpireTime() != null && new Date().after(tenant.getExpireTime())) {
            throw new TenantException("tenant.expired");
        }
    }
}
