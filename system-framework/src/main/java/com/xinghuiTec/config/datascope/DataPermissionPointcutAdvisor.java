package com.xinghuiTec.config.datascope;

import org.aopalliance.aop.Advice;
import org.springframework.aop.Pointcut;
import org.springframework.aop.support.AbstractPointcutAdvisor;

/**
 * 数据权限切面定义
 * 将 Pointcut 和 Advice 组装成 Advisor 注册到 Spring AOP
 *
 * @author xinghuiTec
 */
public class DataPermissionPointcutAdvisor extends AbstractPointcutAdvisor {

    private final Advice advice = new DataPermissionAdvice();
    private final Pointcut pointcut = new DataPermissionPointcut();

    @Override
    public Pointcut getPointcut() {
        return pointcut;
    }

    @Override
    public Advice getAdvice() {
        return advice;
    }
}
