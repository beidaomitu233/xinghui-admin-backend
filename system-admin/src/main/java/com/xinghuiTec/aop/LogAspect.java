package com.xinghuiTec.aop;

import com.alibaba.fastjson2.JSON;
import com.xinghuiTec.annotation.Log;
import com.xinghuiTec.domain.entity.SysOperLog;
import com.xinghuiTec.domain.entity.SysUser;
import com.xinghuiTec.service.SysOperLogService;
import com.xinghuiTec.utils.IpUtils;
import com.xinghuiTec.utils.SecurityUtils;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
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
import org.springframework.web.multipart.MultipartFile;

import jakarta.annotation.Resource;
import java.lang.reflect.Method;
import java.util.Arrays;
import java.util.Date;
import java.util.List;
import java.util.stream.Collectors;

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

            // 记录响应结果（截取前 2000 字符）
            if (result != null) {
                String jsonResult = JSON.toJSONString(result);
                if (jsonResult.length() > 2000) {
                    jsonResult = jsonResult.substring(0, 2000);
                }
                operLog.setJsonResult(jsonResult);
            }

        } catch (Exception e) {
            // 记录异常信息
            operLog.setStatus(1); // 操作失败
            String errorMsg = e.getMessage();
            if (errorMsg != null && errorMsg.length() > 2000) {
                errorMsg = errorMsg.substring(0, 2000);
            }
            operLog.setErrorMsg(errorMsg);
            log.error("操作日志记录异常", e);
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

                    // 设置请求 URL
                    operLog.setOperUrl(request.getRequestURI());

                    // 设置请求方式
                    operLog.setRequestMethod(request.getMethod());

                    // 设置 IP 地址
                    operLog.setOperIp(IpUtils.getIpAddr(request));

                    // 设置请求参数（过滤掉文件、Request、Response 等对象）
                    Object[] args = joinPoint.getArgs();
                    List<Object> filteredArgs = filterArgs(args);
                    String operParam = JSON.toJSONString(filteredArgs);
                    if (operParam.length() > 2000) {
                        operParam = operParam.substring(0, 2000);
                    }
                    operLog.setOperParam(operParam);
                }

                // 获取操作人员
                try {
                    SysUser user = SecurityUtils.getUser();
                    if (user != null) {
                        operLog.setOperName(user.getUsername());
                    }
                } catch (Exception e) {
                    log.warn("获取当前用户信息失败，可能未登录: {}", e.getMessage());
                    operLog.setOperName("anonymous");
                }

                // 异步保存日志
                sysOperLogService.saveOperLog(operLog);

            } catch (Exception e) {
                log.error("操作日志保存失败", e);
                // 日志保存失败不影响业务流程
            }
        }

        return result;
    }

    /**
     * 过滤参数，移除文件、Request、Response 等对象
     * 
     * @param args 原始参数数组
     * @return 过滤后的参数列表
     */
    private List<Object> filterArgs(Object[] args) {
        return Arrays.stream(args)
                .filter(arg -> !(arg instanceof MultipartFile)
                        && !(arg instanceof HttpServletRequest)
                        && !(arg instanceof HttpServletResponse))
                .collect(Collectors.toList());
    }
}
