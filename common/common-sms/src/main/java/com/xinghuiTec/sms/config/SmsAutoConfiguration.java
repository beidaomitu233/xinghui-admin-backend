package com.xinghuiTec.sms.config;

import com.xinghuiTec.sms.core.PlusSmsDao;
import com.xinghuiTec.sms.handler.SmsExceptionHandler;
import org.dromara.sms4j.api.dao.SmsDao;
import org.springframework.boot.autoconfigure.AutoConfiguration;
import org.springframework.boot.autoconfigure.data.redis.RedisAutoConfiguration;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Primary;

/**
 * 短信自动配置
 *
 * @author xinghuiTec
 */
@AutoConfiguration(after = {RedisAutoConfiguration.class})
public class SmsAutoConfiguration {

    @Primary
    @Bean
    public SmsDao smsDao() {
        return new PlusSmsDao();
    }

    @Bean
    public SmsExceptionHandler smsExceptionHandler() {
        return new SmsExceptionHandler();
    }
}
