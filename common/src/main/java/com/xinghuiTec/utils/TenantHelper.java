package com.xinghuiTec.utils;

import com.baomidou.mybatisplus.core.plugins.IgnoreStrategy;
import com.baomidou.mybatisplus.core.plugins.InterceptorIgnoreHelper;
import com.xinghuiTec.constants.TenantConstants;
import lombok.AccessLevel;
import lombok.NoArgsConstructor;
import lombok.extern.slf4j.Slf4j;

import java.util.Stack;
import java.util.function.Supplier;

/**
 * 租户助手 - 管理租户上下文
 * 复用 SecurityUtils 获取登录用户信息
 *
 * @author xinghuiTec
 */
@Slf4j
@NoArgsConstructor(access = AccessLevel.PRIVATE)
public class TenantHelper {

    private static final ThreadLocal<String> TEMP_DYNAMIC_TENANT = new ThreadLocal<>();

    private static final ThreadLocal<Stack<Integer>> REENTRANT_IGNORE = ThreadLocal.withInitial(Stack::new);

    /**
     * 租户功能是否启用
     */
    public static boolean isEnable() {
        String property = SpringUtils.getProperty("tenant.enable");
        return Boolean.TRUE.toString().equals(property);
    }

    // ============= 忽略租户机制（查询所有租户数据） =============

    private static IgnoreStrategy getIgnoreStrategy() {
        try {
            java.lang.reflect.Field field = InterceptorIgnoreHelper.class.getDeclaredField("IGNORE_STRATEGY_LOCAL");
            field.setAccessible(true);
            Object ignoreStrategyLocal = field.get(null);
            if (ignoreStrategyLocal instanceof ThreadLocal<?> local) {
                Object val = local.get();
                if (val instanceof IgnoreStrategy ignoreStrategy) {
                    return ignoreStrategy;
                }
            }
        } catch (Exception ignore) {
        }
        return null;
    }

    private static void enableIgnore() {
        IgnoreStrategy ignoreStrategy = getIgnoreStrategy();
        if (ignoreStrategy == null) {
            InterceptorIgnoreHelper.handle(IgnoreStrategy.builder().tenantLine(true).build());
        } else {
            ignoreStrategy.setTenantLine(true);
        }
        Stack<Integer> reentrantStack = REENTRANT_IGNORE.get();
        reentrantStack.push(reentrantStack.size() + 1);
    }

    private static void disableIgnore() {
        IgnoreStrategy ignoreStrategy = getIgnoreStrategy();
        if (ignoreStrategy != null) {
            boolean noOtherIgnoreStrategy = !Boolean.TRUE.equals(ignoreStrategy.getDynamicTableName())
                && !Boolean.TRUE.equals(ignoreStrategy.getBlockAttack())
                && !Boolean.TRUE.equals(ignoreStrategy.getIllegalSql())
                && !Boolean.TRUE.equals(ignoreStrategy.getDataPermission())
                && (ignoreStrategy.getOthers() == null || ignoreStrategy.getOthers().isEmpty());
            Stack<Integer> reentrantStack = REENTRANT_IGNORE.get();
            boolean empty = reentrantStack.isEmpty() || reentrantStack.pop() == 1;
            if (noOtherIgnoreStrategy && empty) {
                InterceptorIgnoreHelper.clearIgnoreStrategy();
            } else if (empty) {
                ignoreStrategy.setTenantLine(false);
            }
        }
    }

    /**
     * 在忽略租户中执行（自动清理）
     */
    public static void ignore(Runnable handle) {
        enableIgnore();
        try {
            handle.run();
        } finally {
            disableIgnore();
        }
    }

    /**
     * 在忽略租户中执行（有返回值）
     */
    public static <T> T ignore(Supplier<T> handle) {
        enableIgnore();
        try {
            return handle.get();
        } finally {
            disableIgnore();
        }
    }

    // ============= 动态租户管理 =============

    public static void setDynamic(String tenantId) {
        setDynamic(tenantId, false);
    }

    /**
     * 设置动态租户（线程内生效）
     */
    public static void setDynamic(String tenantId, boolean global) {
        if (!isEnable()) {
            return;
        }
        TEMP_DYNAMIC_TENANT.set(tenantId);
    }

    /**
     * 获取动态租户
     */
    public static String getDynamic() {
        if (!isEnable()) {
            return null;
        }
        return TEMP_DYNAMIC_TENANT.get();
    }

    /**
     * 清除动态租户
     */
    public static void clearDynamic() {
        TEMP_DYNAMIC_TENANT.remove();
    }

    /**
     * 在动态租户中执行（自动清理）
     */
    public static void dynamic(String tenantId, Runnable handle) {
        setDynamic(tenantId);
        try {
            handle.run();
        } finally {
            clearDynamic();
        }
    }

    /**
     * 在动态租户中执行（有返回值）
     */
    public static <T> T dynamic(String tenantId, Supplier<T> handle) {
        setDynamic(tenantId);
        try {
            return handle.get();
        } finally {
            clearDynamic();
        }
    }

    /**
     * 获取当前租户ID（动态租户优先，其次登录用户）
     */
    public static String getTenantId() {
        if (!isEnable()) {
            return null;
        }
        String tenantId = getDynamic();
        if (tenantId == null || tenantId.isBlank()) {
            tenantId = SecurityUtils.getTenantId();
        }
        return tenantId;
    }

    // ============= Redis Key 工具方法 =============

    /**
     * 给 Redis key 添加租户前缀
     */
    public static String addTenantPrefix(String key) {
        if (key == null || key.isBlank()) {
            return key;
        }
        if (key.startsWith(TenantConstants.GLOBAL_REDIS_KEY)) {
            return key;
        }
        String tenantId = getTenantId();
        if (tenantId == null || tenantId.isBlank()) {
            return key;
        }
        if (key.startsWith(tenantId + ":")) {
            return key;
        }
        return tenantId + ":" + key;
    }

    /**
     * 去掉 Redis key 的租户前缀
     */
    public static String removeTenantPrefix(String key) {
        if (key == null || key.isBlank()) {
            return key;
        }
        if (key.startsWith(TenantConstants.GLOBAL_REDIS_KEY)) {
            return key;
        }
        String tenantId = getTenantId();
        if (tenantId == null || tenantId.isBlank()) {
            return key;
        }
        if (key.startsWith(tenantId + ":")) {
            return key.substring((tenantId + ":").length());
        }
        return key;
    }
}
