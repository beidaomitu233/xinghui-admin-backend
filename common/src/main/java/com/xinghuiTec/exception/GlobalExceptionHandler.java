package com.xinghuiTec.exception;

import com.xinghuiTec.emues.ResultCodeEnum;
import com.xinghuiTec.exception.base.BaseException;
import com.xinghuiTec.exception.user.*;
import com.xinghuiTec.utils.Result;
import lombok.extern.slf4j.Slf4j;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.security.core.userdetails.UsernameNotFoundException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;

import static com.xinghuiTec.emues.ResultCodeEnum.ADMIN_ACCESS_FORBIDDEN;

/**
 * 全局异常处理器
 * 
 * @author xinghuiTec
 */
@Slf4j
@RestControllerAdvice
public class GlobalExceptionHandler {

    /**
     * 基础异常处理
     */
    @ExceptionHandler(BaseException.class)
    public Result<String> handleBaseException(BaseException e) {
        log.error("业务异常: {}", e.getMessage(), e);
        String message = e.getDefaultMessage() != null ? e.getDefaultMessage() : e.getMessage();
        return Result.fail(message);
    }

    /**
     * 用户不存在异常
     */
    @ExceptionHandler(UserNotExistsException.class)
    public Result<String> handleUserNotExistsException(UserNotExistsException e) {
        log.warn("用户不存在异常: {}", e.getMessage());
        return Result.fail(ResultCodeEnum.ADMIN_ACCOUNT_ERROR.getCode(), "用户不存在");
    }

    /**
     * 用户密码错误异常
     */
    @ExceptionHandler(UserPasswordNotMatchException.class)
    public Result<String> handleUserPasswordNotMatchException(UserPasswordNotMatchException e) {
        log.warn("用户密码错误: {}", e.getMessage());
        return Result.fail(ResultCodeEnum.ADMIN_ACCOUNT_ERROR.getCode(), "用户名或密码错误");
    }

    /**
     * 验证码错误异常
     */
    @ExceptionHandler(CaptchaException.class)
    public Result<String> handleCaptchaException(CaptchaException e) {
        log.warn("验证码错误: {}", e.getMessage());
        return Result.fail("验证码错误");
    }

    /**
     * 验证码过期异常
     */
    @ExceptionHandler(CaptchaExpireException.class)
    public Result<String> handleCaptchaExpireException(CaptchaExpireException e) {
        log.warn("验证码已过期: {}", e.getMessage());
        return Result.fail("验证码已过期，请刷新重试");
    }

    /**
     * 黑名单IP异常
     */
    @ExceptionHandler(BlackListException.class)
    public Result<String> handleBlackListException(BlackListException e) {
        log.error("黑名单IP访问: {}", e.getMessage());
        return Result.fail("访问受限，您的IP已被加入黑名单");
    }

    /**
     * 密码重试次数超限异常
     */
    @ExceptionHandler(UserPasswordRetryLimitExceedException.class)
    public Result<String> handleUserPasswordRetryLimitExceedException(UserPasswordRetryLimitExceedException e) {
        log.warn("密码重试次数超限: {}", e.getMessage());
        return Result.fail("密码输入错误次数过多，账户已被锁定，请稍后再试");
    }

    /**
     * Spring Security 用户名未找到异常
     */
    @ExceptionHandler(UsernameNotFoundException.class)
    public Result<String> handleUsernameNotFoundException(UsernameNotFoundException e) {
        log.warn("用户名未找到: {}", e.getMessage());
        return Result.fail(ResultCodeEnum.ADMIN_ACCOUNT_ERROR.getCode(), "用户名或密码错误");
    }

    /**
     * 运行时异常
     */
    @ExceptionHandler(RuntimeException.class)
    public Result<String> handleRuntimeException(RuntimeException e) {
        log.error("运行时异常: {}", e.getMessage(), e);
        return Result.fail(e.getMessage() != null ? e.getMessage() : "系统运行异常");
    }

    @ExceptionHandler(AccessDeniedException.class)
    public Result<Void> handleAccessDeniedException(AccessDeniedException e) {
        // 返回 403 状态码和友好的错误提示
        return Result.fail(ADMIN_ACCESS_FORBIDDEN);
    }

    /**
     * 通用异常处理（兜底）
     */
    @ExceptionHandler(Exception.class)
    public Result<String> handleException(Exception e) {
        log.error("系统异常: {}", e.getMessage(), e);
        e.printStackTrace();
        String message = e.getMessage() != null ? e.getMessage() : "系统错误，请联系管理员";
        return Result.fail(message);
    }
}