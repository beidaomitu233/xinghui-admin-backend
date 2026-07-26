package com.xinghuiTec.config.datascope;

import com.xinghuiTec.annotation.datascope.DataPermission;
import lombok.extern.slf4j.Slf4j;
import org.aopalliance.intercept.MethodInterceptor;
import org.aopalliance.intercept.MethodInvocation;

import java.lang.reflect.Method;
import java.lang.reflect.Proxy;

/**
 * 数据权限 AOP 通知
 * 在 Mapper 方法执行前捕获 @DataPermission 注解并存入上下文
 *
 * @author xinghuiTec
 */
@Slf4j
public class DataPermissionAdvice implements MethodInterceptor {

    @Override
    public Object invoke(MethodInvocation invocation) throws Throwable {
        Object target = invocation.getThis();
        Method method = invocation.getMethod();
        DataPermission annotation = getAnnotation(target, method);
        DataPermissionHelper.setPermission(annotation);
        try {
            return invocation.proceed();
        } finally {
            DataPermissionHelper.removePermission();
        }
    }

    private DataPermission getAnnotation(Object target, Method method) {
        // 优先方法上的注解
        DataPermission dp = method.getAnnotation(DataPermission.class);
        if (dp != null) return dp;

        // 其次类上的注解
        Class<?> targetClass = target.getClass();
        if (Proxy.isProxyClass(targetClass)) {
            targetClass = targetClass.getInterfaces()[0];
        }
        dp = targetClass.getAnnotation(DataPermission.class);
        if (dp != null) return dp;

        // 最后接口方法上的注解
        for (Class<?> iface : targetClass.getInterfaces()) {
            try {
                Method ifaceMethod = iface.getMethod(method.getName(), method.getParameterTypes());
                dp = ifaceMethod.getAnnotation(DataPermission.class);
                if (dp != null) return dp;
            } catch (NoSuchMethodException ignored) {}
        }
        return null;
    }
}
