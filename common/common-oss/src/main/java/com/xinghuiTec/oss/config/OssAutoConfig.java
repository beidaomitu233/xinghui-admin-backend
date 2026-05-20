package com.xinghuiTec.oss.config;

import com.xinghuiTec.oss.OssService;
import org.dromara.x.file.storage.core.FileStorageService;
import org.springframework.boot.autoconfigure.AutoConfiguration;
import org.springframework.context.annotation.Bean;

/**
 * OSS 自动配置
 *
 * @author xinghuiTec
 */
@AutoConfiguration
public class OssAutoConfig {

    @Bean
    public OssService ossService(FileStorageService fileStorageService) {
        return new OssService(fileStorageService);
    }
}
