package com.xinghuiTec.aop;

import com.xinghuiTec.utils.Result;
import jakarta.servlet.http.HttpServletRequest;
import lombok.extern.slf4j.Slf4j;
import org.aspectj.lang.ProceedingJoinPoint;
import org.aspectj.lang.annotation.Around;
import org.aspectj.lang.annotation.Aspect;
import org.aspectj.lang.annotation.Pointcut;
import org.springframework.core.annotation.Order;
import org.springframework.stereotype.Component;
import org.springframework.web.context.request.RequestContextHolder;
import org.springframework.web.context.request.ServletRequestAttributes;

/** Controller 摘要日志。请求参数、返回数据及 URI 可能包含凭据，均不写入日志。 */
@Aspect
@Component
@Order(1)
@Slf4j
public class LogControllerParam {
    @Pointcut("execution(* com.*.controller..*.*(..))")
    private void webLog() {
    }

    @Around("webLog()")
    public Object doAround(ProceedingJoinPoint joinPoint) throws Throwable {
        long start = System.currentTimeMillis();
        String method = joinPoint.getSignature().toShortString();
        String httpMethod = "unknown";
        ServletRequestAttributes attributes = (ServletRequestAttributes) RequestContextHolder.getRequestAttributes();
        if (attributes != null) {
            HttpServletRequest request = attributes.getRequest();
            httpMethod = request.getMethod();
        }

        try {
            Object result = joinPoint.proceed();
            String outcome = result instanceof Result<?> response
                    ? "code=" + response.getCode() : "completed";
            log.info("Controller {} {} {} durationMs={}", httpMethod, method, outcome,
                    System.currentTimeMillis() - start);
            return result;
        } catch (Throwable failure) {
            log.warn("Controller {} {} failed={} durationMs={}", httpMethod, method,
                    failure.getClass().getSimpleName(), System.currentTimeMillis() - start);
            throw failure;
        }
    }
}
