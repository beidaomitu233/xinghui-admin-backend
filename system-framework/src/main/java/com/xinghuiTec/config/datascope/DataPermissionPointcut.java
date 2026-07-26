package com.xinghuiTec.config.datascope;

import com.xinghuiTec.annotation.datascope.DataPermission;
import org.springframework.aop.support.StaticMethodMatcherPointcut;

import java.lang.reflect.Method;

/**
 * 数据权限切入点：匹配带有 @DataPermission 注解的 Mapper 方法
 *
 * @author xinghuiTec
 */
public class DataPermissionPointcut extends StaticMethodMatcherPointcut {

    @Override
    public boolean matches(Method method, Class<?> targetClass) {
        // 方法上有注解
        if (method.isAnnotationPresent(DataPermission.class)) {
            return true;
        }
        // 类上有注解
        if (targetClass.isAnnotationPresent(DataPermission.class)) {
            return true;
        }
        // 接口上有注解（MyBatis Mapper 接口）
        for (Class<?> iface : targetClass.getInterfaces()) {
            if (iface.isAnnotationPresent(DataPermission.class)) {
                return true;
            }
            for (Method m : iface.getMethods()) {
                if (m.getName().equals(method.getName())
                        && m.isAnnotationPresent(DataPermission.class)) {
                    return true;
                }
            }
        }
        return false;
    }
}
