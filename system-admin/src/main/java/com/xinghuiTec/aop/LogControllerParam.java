package com.xinghuiTec.aop;

import com.alibaba.fastjson2.JSONObject;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import lombok.extern.slf4j.Slf4j;
import org.aspectj.lang.JoinPoint;
import org.aspectj.lang.ProceedingJoinPoint;
import org.aspectj.lang.annotation.Around;
import org.aspectj.lang.annotation.Aspect;
import org.aspectj.lang.annotation.Before;
import org.aspectj.lang.annotation.Pointcut;
import org.springframework.core.annotation.Order;
import org.springframework.stereotype.Component;
import org.springframework.web.context.request.RequestContextHolder;
import org.springframework.web.context.request.ServletRequestAttributes;
import org.springframework.web.multipart.MultipartFile;

import java.util.Arrays;
import java.util.List;
import java.util.Optional;
import java.util.stream.Collectors;

@Aspect
@Component
@Order(1)
@Slf4j
public class LogControllerParam {
    @Pointcut("execution(* com.*.controller..*.*(..))")
    private void webLog() {
    }

    @Before(value = "webLog()")
    public void before(JoinPoint joinPoint) {
        ServletRequestAttributes requestAttributes = (ServletRequestAttributes) RequestContextHolder
                .getRequestAttributes();
        HttpServletRequest request = requestAttributes.getRequest();
        System.out.println();
        System.out.println("===================================请求参数===========================");
        System.out.println("地址 : " + Optional.ofNullable(request.getRequestURI().toString()).orElse(null));
        System.out.println("方式 : " + request.getMethod());
        System.out.println("方法 : " + joinPoint.getSignature());
        System.out.println("参数 : " + JSONObject.toJSONString(JSONObject.toJSONString(filterArgs(joinPoint.getArgs()))));
        System.out.println();
    }

    private List<Object> filterArgs(Object[] objects) {
        return Arrays.stream(objects).filter(obj -> !(obj instanceof MultipartFile)
                && !(obj instanceof HttpServletResponse)
                && !(obj instanceof HttpServletRequest)).collect(Collectors.toList());
    }

    /**
     * 环绕通知 - 计算方法执行耗时
     *
     * @param proceedingJoinPoint 切点
     * @return 方法执行结果
     * @throws Throwable 异常
     */
    @Around("webLog()")
    public Object doAround(ProceedingJoinPoint proceedingJoinPoint) throws Throwable {
        // 记录开始时间
        long startTime = System.currentTimeMillis();

        // 执行目标方法
        Object result = proceedingJoinPoint.proceed();

        // 计算执行耗时
        long endTime = System.currentTimeMillis();
        long duration = endTime - startTime;

        // 打印出参
        String resultJson = JSONObject.toJSONString(result.toString());
        System.out.println();
        System.out.println("===================================返回数据===========================");
        System.out.println(resultJson);
        System.out.println();
        System.out.println("===================================执行耗时===========================");
        System.out.println("方法执行耗时: " + duration + " ms (" + duration / 1000.0 + " s)");

        return result;
    }

}