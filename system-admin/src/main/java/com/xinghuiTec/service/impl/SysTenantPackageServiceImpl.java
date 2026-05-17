package com.xinghuiTec.service.impl;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.core.toolkit.Wrappers;
import com.baomidou.mybatisplus.extension.service.impl.ServiceImpl;
import com.xinghuiTec.domain.entity.SysTenantPackage;
import com.xinghuiTec.mapper.SysTenantPackageMapper;
import com.xinghuiTec.service.ISysTenantPackageService;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;

import java.util.List;

/**
 * 租户套餐Service业务层处理
 *
 * @author xinghuiTec
 */
@RequiredArgsConstructor
@Service
public class SysTenantPackageServiceImpl extends ServiceImpl<SysTenantPackageMapper, SysTenantPackage> implements ISysTenantPackageService {

    private final SysTenantPackageMapper baseMapper;

    @Override
    public SysTenantPackage queryById(Long packageId) {
        return baseMapper.selectById(packageId);
    }

    @Override
    public List<SysTenantPackage> queryList(SysTenantPackage tenantPackage) {
        LambdaQueryWrapper<SysTenantPackage> lqw = Wrappers.lambdaQuery();
        lqw.like(tenantPackage.getPackageName() != null && !tenantPackage.getPackageName().isEmpty(),
            SysTenantPackage::getPackageName, tenantPackage.getPackageName());
        lqw.eq(tenantPackage.getStatus() != null && !tenantPackage.getStatus().isEmpty(),
            SysTenantPackage::getStatus, tenantPackage.getStatus());
        lqw.orderByAsc(SysTenantPackage::getPackageId);
        return baseMapper.selectList(lqw);
    }

    @Override
    public Boolean insertByBo(SysTenantPackage tenantPackage) {
        return baseMapper.insert(tenantPackage) > 0;
    }

    @Override
    public Boolean updateByBo(SysTenantPackage tenantPackage) {
        return baseMapper.updateById(tenantPackage) > 0;
    }

    @Override
    public Boolean deleteWithValidByIds(List<Long> ids, Boolean isValid) {
        return baseMapper.deleteBatchIds(ids) > 0;
    }
}
