package com.xinghuiTec.ratelimiter.aspect;

import com.xinghuiTec.exception.ServiceException;
import com.xinghuiTec.ratelimiter.annotation.RateLimiter;
import com.xinghuiTec.ratelimiter.enums.LimitType;
import com.xinghuiTec.utils.SpringUtils;
import jakarta.servlet.http.HttpServletRequest;
import lombok.extern.slf4j.Slf4j;
import org.aspectj.lang.JoinPoint;
import org.aspectj.lang.annotation.Aspect;
import org.aspectj.lang.annotation.Before;
import org.aspectj.lang.reflect.MethodSignature;
import org.redisson.api.RRateLimiter;
import org.redisson.api.RateIntervalUnit;
import org.redisson.api.RateType;
import org.redisson.api.RedissonClient;
import org.springframework.context.expression.BeanFactoryResolver;
import org.springframework.context.expression.MethodBasedEvaluationContext;
import org.springframework.core.DefaultParameterNameDiscoverer;
import org.springframework.expression.ExpressionParser;
import org.springframework.expression.spel.standard.SpelExpressionParser;
import org.springframework.web.context.request.RequestContextHolder;
import org.springframework.web.context.request.ServletRequestAttributes;

import java.lang.reflect.Method;

/**
 * 限流 AOP 切面
 *
 * 使用 Redisson RRateLimiter 实现令牌桶算法
 * - 比固定窗口计数器更平滑，允许短时间突发流量
 * - 分布式环境下精确限流
 *
 * @author xinghuiTec
 */
@Slf4j
@Aspect
public class RateLimiterAspect {

    private static final String RATE_LIMIT_KEY = "rate_limit:";

    private final ExpressionParser parser = new SpelExpressionParser();
    private final DefaultParameterNameDiscoverer discoverer = new DefaultParameterNameDiscoverer();

    @Before("@annotation(rateLimiter)")
    public void doBefore(JoinPoint point, RateLimiter rateLimiter) {
        int time = rateLimiter.time();
        int count = rateLimiter.count();

        try {
            String key = buildKey(rateLimiter, point);
            RedissonClient client = SpringUtils.getBean(RedissonClient.class);

            // Redisson 令牌桶限流器
            RRateLimiter limiter = client.getRateLimiter(key);
            // 设置速率：count 个令牌 / time 秒
            limiter.trySetRate(RateType.OVERALL, count, time, RateIntervalUnit.SECONDS);

            // 尝试获取一个令牌
            if (!limiter.tryAcquire()) {
                log.warn("限流触发: key={}, 限制{}/{}秒", key, count, time);
                throw new ServiceException(rateLimiter.message());
            }

            log.debug("限流通行: key={}, 限制{}/{}秒", key, count, time);

        } catch (ServiceException e) {
            throw e;
        } catch (Exception e) {
            log.error("限流处理异常: {}", e.getMessage(), e);
            throw new RuntimeException("服务器限流异常，请稍候再试", e);
        }
    }

    /**
     * 构建 Redis key
     * 格式: rate_limit:{URI}:{IP}:{customKey}
     */
    private String buildKey(RateLimiter rateLimiter, JoinPoint point) {
        StringBuilder sb = new StringBuilder(RATE_LIMIT_KEY);

        HttpServletRequest request = getRequest();
        if (request != null) {
            sb.append(request.getRequestURI()).append(":");
        }

        if (rateLimiter.limitType() == LimitType.IP) {
            sb.append(getClientIP(request)).append(":");
        }

        String key = parseKey(rateLimiter.key(), point);
        if (key != null && !key.isBlank()) {
            sb.append(key);
        }

        return sb.toString();
    }

    private String parseKey(String key, JoinPoint point) {
        if (key == null || key.isBlank() || !key.contains("#")) {
            return key;
        }
        try {
            MethodSignature signature = (MethodSignature) point.getSignature();
            Method method = signature.getMethod();
            Object[] args = point.getArgs();
            MethodBasedEvaluationContext context =
                new MethodBasedEvaluationContext(null, method, args, discoverer);
            context.setBeanResolver(new BeanFactoryResolver(SpringUtils.getApplicationContext()));
            return parser.parseExpression(key).getValue(context, String.class);
        } catch (Exception e) {
            log.warn("SpEL 解析失败: key={}, error={}", key, e.getMessage());
            return key;
        }
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

    private String getClientIP(HttpServletRequest request) {
        if (request == null) return "unknown";
        String ip = request.getHeader("X-Forwarded-For");
        if (ip == null || ip.isBlank()) ip = request.getHeader("X-Real-IP");
        if (ip == null || ip.isBlank()) ip = request.getRemoteAddr();
        return ip != null ? ip : "unknown";
    }
}
