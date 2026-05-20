package com.xinghuiTec.idempotent.aspect;

import cn.hutool.core.util.ArrayUtil;
import cn.hutool.core.util.ObjectUtil;
import cn.hutool.crypto.SecureUtil;
import com.xinghuiTec.constants.HttpConstants;
import com.xinghuiTec.emues.ResultCodeEnum;
import com.xinghuiTec.exception.ServiceException;
import com.xinghuiTec.idempotent.annotation.RepeatSubmit;
import com.xinghuiTec.utils.RedisCacheUtils;
import com.xinghuiTec.utils.SpringUtils;
import com.xinghuiTec.utils.Result;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import lombok.extern.slf4j.Slf4j;
import org.aspectj.lang.JoinPoint;
import org.aspectj.lang.annotation.AfterReturning;
import org.aspectj.lang.annotation.AfterThrowing;
import org.aspectj.lang.annotation.Aspect;
import org.aspectj.lang.annotation.Before;
import org.springframework.validation.BindingResult;
import org.springframework.web.context.request.RequestContextHolder;
import org.springframework.web.context.request.ServletRequestAttributes;
import org.springframework.web.multipart.MultipartFile;

import java.util.Collection;
import java.util.Map;
import java.util.StringJoiner;
import java.util.concurrent.TimeUnit;

/**
 * 防重提交 AOP 切面
 *
 * 实现原理：
 *   Before:   MD5(Token + URL + 请求参数) → Redis SETNX 加锁
 *             若 key 已存在 → 拒绝（重复提交）
 *             若 key 不存在 → 放行，缓存 key 到 ThreadLocal
 *   AfterReturning: 成功 → 保留 key（间隔内不能重复提交）
 *                    失败 → 删除 key（允许重试）
 *   AfterThrowing:  删除 key（允许重试）
 *
 * @author xinghuiTec
 */
@Slf4j
@Aspect
public class RepeatSubmitAspect {

    private static final ThreadLocal<String> KEY_CACHE = new ThreadLocal<>();

    private static final String REPEAT_SUBMIT_KEY = "repeat_submit:";

    @Before("@annotation(repeatSubmit)")
    public void doBefore(JoinPoint point, RepeatSubmit repeatSubmit) {
        long interval = repeatSubmit.timeUnit().toMillis(repeatSubmit.interval());
        if (interval < 1000) {
            throw new ServiceException("重复提交间隔时间不能小于1秒");
        }

        HttpServletRequest request = getRequest();
        if (request == null) {
            return;  // 非 Web 环境跳过
        }

        // 请求地址 + 参数 MD5
        String url = request.getRequestURI();
        String params = argsArrayToString(point.getArgs());

        // Token 标识（从 Authorization 头获取）
        String token = request.getHeader(HttpConstants.HEADER_AUTHORIZATION);
        if (token == null || token.isBlank()) {
            token = url;  // 未登录场景用 URL 代替
        }

        // MD5 混合签名
        String sign = SecureUtil.md5(token + ":" + url + ":" + params);
        String cacheKey = REPEAT_SUBMIT_KEY + sign;

        RedisCacheUtils redis = SpringUtils.getBean(RedisCacheUtils.class);
        if (redis.setObjectIfAbsent(cacheKey, "", interval, TimeUnit.MILLISECONDS)) {
            KEY_CACHE.set(cacheKey);
        } else {
            log.warn("重复提交: url={}", url);
            throw new ServiceException(repeatSubmit.message());
        }
    }

    /**
     * 请求成功：保留 key，防止间隔内重复提交
     */
    @AfterReturning(pointcut = "@annotation(repeatSubmit)", returning = "result")
    public void doAfterReturning(JoinPoint point, RepeatSubmit repeatSubmit, Object result) {
        try {
            if (result instanceof Result<?> r) {
                // 成功 → 保留 key（不允许重复提交）
                if (r.getCode() != null && r.getCode().equals(ResultCodeEnum.SUCCESS.getCode())) {
                    return;
                }
                // 失败 → 删除 key（允许重试）
                String key = KEY_CACHE.get();
                if (key != null) {
                    RedisCacheUtils redis = SpringUtils.getBean(RedisCacheUtils.class);
                    redis.deleteObject(key);
                }
            }
        } finally {
            KEY_CACHE.remove();
        }
    }

    /**
     * 请求异常：删除 key，允许重试
     */
    @AfterThrowing(value = "@annotation(repeatSubmit)", throwing = "e")
    public void doAfterThrowing(JoinPoint point, RepeatSubmit repeatSubmit, Exception e) {
        try {
            String key = KEY_CACHE.get();
            if (key != null) {
                RedisCacheUtils redis = SpringUtils.getBean(RedisCacheUtils.class);
                redis.deleteObject(key);
            }
        } finally {
            KEY_CACHE.remove();
        }
    }

    /**
     * 将请求参数拼接为字符串
     */
    private String argsArrayToString(Object[] paramsArray) {
        StringJoiner params = new StringJoiner(" ");
        if (ArrayUtil.isEmpty(paramsArray)) {
            return params.toString();
        }
        for (Object o : paramsArray) {
            if (ObjectUtil.isNotNull(o) && !isFilterObject(o)) {
                params.add(com.alibaba.fastjson2.JSON.toJSONString(o));
            }
        }
        return params.toString();
    }

    /**
     * 过滤不需要参与签名的对象（文件、请求/响应对象等）
     */
    private boolean isFilterObject(Object o) {
        Class<?> clazz = o.getClass();
        if (clazz.isArray()) {
            return MultipartFile.class.isAssignableFrom(clazz.getComponentType());
        }
        if (o instanceof Collection<?> col) {
            return col.stream().anyMatch(v -> v instanceof MultipartFile);
        }
        if (o instanceof Map<?, ?> map) {
            return map.values().stream().anyMatch(v -> v instanceof MultipartFile);
        }
        return o instanceof MultipartFile
            || o instanceof HttpServletRequest
            || o instanceof HttpServletResponse
            || o instanceof BindingResult;
    }

    private HttpServletRequest getRequest() {
        try {
            ServletRequestAttributes attrs =
                (ServletRequestAttributes) RequestContextHolder.getRequestAttributes();
            return attrs != null ? attrs.getRequest() : null;
        } catch (Exception e) {
            return null;
        }
    }
}
