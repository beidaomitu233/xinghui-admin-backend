package com.xinghuiTec.aop;

import com.xinghuiTec.annotation.Log;
import com.xinghuiTec.domain.entity.SysOperLog;
import com.xinghuiTec.domain.entity.SysUser;
import com.xinghuiTec.service.SysOperLogService;
import com.xinghuiTec.utils.IpUtils;
import com.xinghuiTec.utils.SecurityUtils;
import com.xinghuiTec.utils.Result;
import jakarta.servlet.http.HttpServletRequest;
import lombok.extern.slf4j.Slf4j;
import org.aspectj.lang.ProceedingJoinPoint;
import org.aspectj.lang.annotation.Around;
import org.aspectj.lang.annotation.Aspect;
import org.aspectj.lang.annotation.Pointcut;
import org.aspectj.lang.reflect.MethodSignature;
import org.springframework.core.annotation.Order;
import org.springframework.stereotype.Component;
import org.springframework.web.context.request.RequestContextHolder;
import org.springframework.web.context.request.ServletRequestAttributes;

import jakarta.annotation.Resource;
import java.lang.reflect.Method;
import java.util.Date;

/**
 * 操作日志记录切面
 * 拦截所有标记了 @Log 注解的方法，自动记录操作日志
 * 
 * @author beidoa23
 * @since 2026-01-23
 */
@Aspect
@Component
@Order(2)
@Slf4j
public class LogAspect {

    @Resource
    private SysOperLogService sysOperLogService;

    /**
     * 定义切点：所有标记了 @Log 注解的方法
     */
    @Pointcut("@annotation(com.xinghuiTec.annotation.Log)")
    public void logPointcut() {
    }

    /**
     * 环绕通知：记录操作日志
     * 
     * @param joinPoint 切点
     * @return 方法执行结果
     * @throws Throwable 异常
     */
    @Around("logPointcut()")
    public Object doAround(ProceedingJoinPoint joinPoint) throws Throwable {
        // 记录开始时间
        long startTime = System.currentTimeMillis();

        // 初始化操作日志对象
        SysOperLog operLog = new SysOperLog();
        operLog.setOperTime(new Date());
        operLog.setStatus(0); // 默认操作成功

        Object result = null;
        try {
            // 执行目标方法
            result = joinPoint.proceed();

            // 仅保留非敏感结果码，不持久化可能含令牌的响应数据。
            operLog.setJsonResult(result instanceof Result<?> response
                    ? "code=" + response.getCode() : "completed");

        } catch (Exception e) {
            // 记录异常信息
            operLog.setStatus(1); // 操作失败
            operLog.setErrorMsg("exception=" + e.getClass().getSimpleName());
            throw e; // 继续抛出异常，不影响业务流程

        } finally {
            // 计算耗时
            long costTime = System.currentTimeMillis() - startTime;
            operLog.setCostTime(costTime);

            // 获取注解信息
            try {
                MethodSignature signature = (MethodSignature) joinPoint.getSignature();
                Method method = signature.getMethod();
                Log logAnnotation = method.getAnnotation(Log.class);

                if (logAnnotation != null) {
                    // 设置模块标题
                    operLog.setTitle(logAnnotation.title());
                    // 设置业务类型
                    operLog.setBusinessType(logAnnotation.businessType().getCode());
                }

                // 设置方法名称
                String className = joinPoint.getTarget().getClass().getName();
                String methodName = signature.getName();
                operLog.setMethod(className + "." + methodName);

                // 获取请求信息
                ServletRequestAttributes requestAttributes = (ServletRequestAttributes) RequestContextHolder
                        .getRequestAttributes();
                if (requestAttributes != null) {
                    HttpServletRequest request = requestAttributes.getRequest();

                    // URI 可能包含令牌或重置凭据；方法名已用于定位操作。
                    operLog.setOperUrl("[omitted]");

                    // 设置请求方式
                    operLog.setRequestMethod(request.getMethod());

                    // 设置 IP 地址
                    operLog.setOperIp(IpUtils.getIpAddr(request));

                    operLog.setOperParam("[omitted]");
                }

                // 获取操作人员
                try {
                    SysUser user = SecurityUtils.getUser();
                    if (user != null) {
                        operLog.setOperName(user.getUsername());
                    }
                } catch (Exception e) {
                    log.warn("获取当前用户信息失败，可能未登录: {}", e.getClass().getSimpleName());
                    operLog.setOperName("anonymous");
                }

                // 异步保存日志
                sysOperLogService.saveOperLog(operLog);

            } catch (Exception e) {
                log.error("操作日志保存失败: {}", e.getClass().getSimpleName());
                // 日志保存失败不影响业务流程
            }
        }

        return result;
    }

}
