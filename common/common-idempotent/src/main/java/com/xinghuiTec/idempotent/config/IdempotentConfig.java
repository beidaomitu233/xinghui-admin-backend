package com.xinghuiTec.idempotent.config;

import com.xinghuiTec.idempotent.aspect.RepeatSubmitAspect;
import org.springframework.boot.autoconfigure.AutoConfiguration;
import org.springframework.context.annotation.Bean;

/**
 * 防重提交模块自动配置
 *
 * @author xinghuiTec
 */
@AutoConfiguration
public class IdempotentConfig {

    @Bean
    public RepeatSubmitAspect repeatSubmitAspect() {
        return new RepeatSubmitAspect();
    }
}
