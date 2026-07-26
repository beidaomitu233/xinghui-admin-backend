package com.xinghuiTec.oss.config;

import com.xinghuiTec.oss.OssService;
import org.dromara.x.file.storage.core.FileStorageService;
import org.springframework.boot.autoconfigure.AutoConfiguration;
import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean;
import org.springframework.context.annotation.Bean;

/**
 * OSS 自动配置
 * 注意: 本类位于组件扫描基包 com.xinghuiTec 内，会同时经 auto-config 与 @Configuration
 * 扫描注册；@ConditionalOnMissingBean 保证业务侧自定义 OssService 时本配置正确退让
 *
 * @author xinghuiTec
 */
@AutoConfiguration
public class OssAutoConfig {

    @Bean
    @ConditionalOnMissingBean(OssService.class)
    public OssService ossService(FileStorageService fileStorageService) {
        return new OssService(fileStorageService);
    }
}
