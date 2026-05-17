package com.xinghuiTec.utils;

import cn.hutool.core.convert.Convert;
import com.baomidou.mybatisplus.core.plugins.IgnoreStrategy;
import com.baomidou.mybatisplus.core.plugins.InterceptorIgnoreHelper;
import com.xinghuiTec.constants.TenantConstants;
import com.xinghuiTec.domain.entity.SysUser;
import jakarta.annotation.Resource;
import lombok.AccessLevel;
import lombok.NoArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;

import java.util.Stack;

/**
 * 租户助手
 * 功能:
 * 1. ThreadLocal 存储当前线程租户上下文
 * 2. Redis 存储全局动态租户（用于超级管理员切换租户）
 * 3. 忽略租户机制（可重入）
 * 4. 获取当前租户 ID
 *
 * @author xinghuiTec
 */
@Slf4j
@NoArgsConstructor(access = AccessLevel.PRIVATE)
public class TenantHelper {

    private static final String DYNAMIC_TENANT_KEY = TenantConstants.GLOBAL_REDIS_KEY + "dynamicTenant";

    private static final ThreadLocal<String> TEMP_DYNAMIC_TENANT = new ThreadLocal<>();

    private static final ThreadLocal<Stack<Integer>> REENTRANT_IGNORE = ThreadLocal.withInitial(Stack::new);

    private static RedisCacheUtils redisCache;

    @Resource
    public void setRedisCache(RedisCacheUtils redisCache) {
        TenantHelper.redisCache = redisCache;
    }

    /**
     * 租户功能是否启用
     */
    public static boolean isEnable() {
        try {
            String enable = System.getProperty("tenant.enable");
            if (enable == null) {
                enable = System.getenv("TENANT_ENABLE");
            }
            return Convert.toBool(enable, false);
        } catch (Exception e) {
            return false;
        }
    }

    /**
     * 获取当前租户ID（动态租户优先）
     */
    public static String getTenantId() {
        // 1. 优先获取动态租户
        String tenantId = getDynamic();
        if (tenantId == null || tenantId.isEmpty()) {
            // 2. 获取登录用户租户
            tenantId = getLoginTenantId();
        }
        return tenantId;
    }

    /**
     * 从 Security 上下文中获取登录用户的租户 ID
     */
    private static String getLoginTenantId() {
        try {
            SysUser user = SecurityUtils.getUser();
            if (user != null) {
                return user.getTenantId();
            }
        } catch (Exception e) {
            // 未登录或获取失败
        }
        return null;
    }

    /**
     * 设置动态租户（一直有效，需手动清理）
     *
     * @param tenantId 租户ID
     * @param global   是否全局生效
     */
    public static void setDynamic(String tenantId, boolean global) {
        if (!isEnable()) {
            return;
        }

        String userId = getUserIdSafely();

        if (userId == null || !global) {
            // 未登录或非全局模式：只在线程内生效
            TEMP_DYNAMIC_TENANT.set(tenantId);
            return;
        }

        // 全局模式：写入 Redis
        String cacheKey = DYNAMIC_TENANT_KEY + ":" + userId;
        redisCache.setCacheObject(cacheKey, tenantId);
    }

    /**
     * 设置动态租户（默认非全局）
     */
    public static void setDynamic(String tenantId) {
        setDynamic(tenantId, false);
    }

    /**
     * 获取动态租户
     */
    public static String getDynamic() {
        if (!isEnable()) {
            return null;
        }

        // 优先获取线程内的值
        String tenantId = TEMP_DYNAMIC_TENANT.get();
        if (tenantId != null && !tenantId.isEmpty()) {
            return tenantId;
        }

        // 尝试从 Redis 获取（全局设置）
        String userId = getUserIdSafely();
        if (userId == null) {
            return null;
        }

        String cacheKey = DYNAMIC_TENANT_KEY + ":" + userId;
        return redisCache.getCacheObject(cacheKey);
    }

    /**
     * 清除动态租户
     */
    public static void clearDynamic() {
        if (!isEnable()) {
            return;
        }

        TEMP_DYNAMIC_TENANT.remove();

        String userId = getUserIdSafely();
        if (userId != null) {
            String cacheKey = DYNAMIC_TENANT_KEY + ":" + userId;
            redisCache.deleteObject(cacheKey);
        }
    }

    /**
     * 在指定租户中执行（自动清理）
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
     * 在指定租户中执行并返回值（自动清理）
     */
    public static <T> T dynamic(String tenantId, java.util.function.Supplier<T> handle) {
        setDynamic(tenantId);
        try {
            return handle.get();
        } finally {
            clearDynamic();
        }
    }

    /**
     * 开启忽略租户
     */
    private static void enableIgnore() {
        InterceptorIgnoreHelper.handle(IgnoreStrategy.builder().tenantLine(true).build());
        Stack<Integer> stack = REENTRANT_IGNORE.get();
        stack.push(stack.size() + 1);
    }

    /**
     * 关闭忽略租户
     */
    private static void disableIgnore() {
        Stack<Integer> stack = REENTRANT_IGNORE.get();
        if (!stack.isEmpty()) {
            stack.pop();
        }
        if (stack.isEmpty()) {
            InterceptorIgnoreHelper.clearIgnoreStrategy();
        }
    }

    /**
     * 在忽略租户中执行
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
     * 在忽略租户中执行并返回值
     */
    public static <T> T ignore(java.util.function.Supplier<T> handle) {
        enableIgnore();
        try {
            return handle.get();
        } finally {
            disableIgnore();
        }
    }

    /**
     * 安全获取用户ID
     */
    private static String getUserIdSafely() {
        try {
            SysUser user = SecurityUtils.getUser();
            if (user != null) {
                return user.getUserId();
            }
        } catch (Exception e) {
            // 未登录
        }
        return null;
    }
}
