package com.xinghuiTec.oss.service.impl;

import com.baomidou.mybatisplus.extension.service.impl.ServiceImpl;
import com.xinghuiTec.oss.entity.SysOssConfig;
import com.xinghuiTec.oss.mapper.SysOssConfigMapper;
import com.xinghuiTec.oss.service.ISysOssConfigService;
import org.springframework.stereotype.Service;

@Service
public class SysOssConfigServiceImpl extends ServiceImpl<SysOssConfigMapper, SysOssConfig> implements ISysOssConfigService {
}
