package com.xinghuiTec.config.datascope;

import com.baomidou.mybatisplus.core.plugins.IgnoreStrategy;
import com.baomidou.mybatisplus.core.plugins.InterceptorIgnoreHelper;
import com.xinghuiTec.annotation.datascope.DataPermission;
import lombok.AccessLevel;
import lombok.NoArgsConstructor;

import java.util.Stack;
import java.util.function.Supplier;

/**
 * 数据权限助手 - 管理数据权限上下文
 *
 * @author xinghuiTec
 */
@NoArgsConstructor(access = AccessLevel.PRIVATE)
public class DataPermissionHelper {

    private static final ThreadLocal<DataPermission> PERMISSION_CACHE = new ThreadLocal<>();
    private static final ThreadLocal<Stack<Integer>> REENTRANT_IGNORE = ThreadLocal.withInitial(Stack::new);

    /** 设置当前执行的权限注解 */
    public static void setPermission(DataPermission dataPermission) {
        PERMISSION_CACHE.set(dataPermission);
    }

    /** 获取当前执行的权限注解 */
    public static DataPermission getPermission() {
        return PERMISSION_CACHE.get();
    }

    /** 清除权限注解 */
    public static void removePermission() {
        PERMISSION_CACHE.remove();
    }

    // ========== 忽略权限机制 ==========

    private static IgnoreStrategy getIgnoreStrategy() {
        try {
            java.lang.reflect.Field field = InterceptorIgnoreHelper.class.getDeclaredField("IGNORE_STRATEGY_LOCAL");
            field.setAccessible(true);
            Object obj = field.get(null);
            if (obj instanceof ThreadLocal<?> local) {
                Object val = local.get();
                if (val instanceof IgnoreStrategy strategy) {
                    return strategy;
                }
            }
        } catch (Exception ignore) {}
        return null;
    }

    private static void enableIgnore() {
        IgnoreStrategy strategy = getIgnoreStrategy();
        if (strategy == null) {
            InterceptorIgnoreHelper.handle(IgnoreStrategy.builder().dataPermission(true).build());
        } else {
            strategy.setDataPermission(true);
        }
        REENTRANT_IGNORE.get().push(REENTRANT_IGNORE.get().size() + 1);
    }

    private static void disableIgnore() {
        IgnoreStrategy strategy = getIgnoreStrategy();
        if (strategy != null) {
            boolean noOther = !Boolean.TRUE.equals(strategy.getDynamicTableName())
                && !Boolean.TRUE.equals(strategy.getBlockAttack())
                && !Boolean.TRUE.equals(strategy.getIllegalSql())
                && !Boolean.TRUE.equals(strategy.getTenantLine())
                && (strategy.getOthers() == null || strategy.getOthers().isEmpty());
            Stack<Integer> stack = REENTRANT_IGNORE.get();
            boolean empty = stack.isEmpty() || stack.pop() == 1;
            if (noOther && empty) {
                InterceptorIgnoreHelper.clearIgnoreStrategy();
            } else if (empty) {
                strategy.setDataPermission(false);
            }
        }
    }

    public static void ignore(Runnable handle) {
        enableIgnore();
        try { handle.run(); } finally { disableIgnore(); }
    }

    public static <T> T ignore(Supplier<T> handle) {
        enableIgnore();
        try { return handle.get(); } finally { disableIgnore(); }
    }
}
