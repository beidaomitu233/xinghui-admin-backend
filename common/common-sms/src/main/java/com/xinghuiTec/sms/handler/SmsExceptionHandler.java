package com.xinghuiTec.sms.handler;

import com.xinghuiTec.utils.Result;
import lombok.extern.slf4j.Slf4j;
import org.dromara.sms4j.comm.exception.SmsBlendException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;

/**
 * 短信异常处理器
 *
 * @author xinghuiTec
 */
@Slf4j
@RestControllerAdvice
public class SmsExceptionHandler {

    @ExceptionHandler(SmsBlendException.class)
    public Result<Void> handleSmsBlendException(SmsBlendException e) {
        log.error("短信发送异常: {}", e.getMessage());
        return Result.fail("短信发送失败，请稍后再试");
    }
}
