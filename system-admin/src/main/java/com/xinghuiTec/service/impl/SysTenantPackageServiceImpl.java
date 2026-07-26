package com.xinghuiTec.service.impl;

import com.baomidou.mybatisplus.extension.service.impl.ServiceImpl;
import com.xinghuiTec.domain.entity.SysTenantPackage;
import com.xinghuiTec.mapper.SysTenantPackageMapper;
import com.xinghuiTec.service.ISysTenantPackageService;
import org.springframework.stereotype.Service;

/**
 * 租户套餐 Service 实现
 *
 * @author xinghuiTec
 */
@Service
public class SysTenantPackageServiceImpl extends ServiceImpl<SysTenantPackageMapper, SysTenantPackage>
        implements ISysTenantPackageService {
}
