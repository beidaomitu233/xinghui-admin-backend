package com.xinghuiTec.service.impl;
import com.baomidou.mybatisplus.extension.service.impl.ServiceImpl;
import com.xinghuiTec.mapper.SysConfigMapper;
import com.xinghuiTec.domain.entity.SysConfig;
import com.xinghuiTec.service.SysConfigService;
import org.springframework.stereotype.Service;

/**
 * 参数配置表(SysConfig)表服务实现类
 */
@Service
public class SysConfigServiceImpl extends ServiceImpl<SysConfigMapper, SysConfig> implements SysConfigService {

}
