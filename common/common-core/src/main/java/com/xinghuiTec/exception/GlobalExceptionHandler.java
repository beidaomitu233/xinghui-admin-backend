package com.xinghuiTec.exception;

import cn.hutool.core.util.ObjectUtil;
import jakarta.validation.ConstraintViolation;
import jakarta.validation.ConstraintViolationException;
import lombok.extern.slf4j.Slf4j;
import com.xinghuiTec.emues.ResultCodeEnum;
import com.xinghuiTec.exception.base.BaseException;
import com.xinghuiTec.exception.user.*;
import com.xinghuiTec.utils.Result;
import org.springframework.context.support.DefaultMessageSourceResolvable;
import org.springframework.dao.DataAccessException;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.security.authentication.DisabledException;
import org.springframework.security.authentication.BadCredentialsException;
import org.springframework.security.core.userdetails.UsernameNotFoundException;
import org.springframework.validation.BindException;
import org.springframework.web.HttpRequestMethodNotSupportedException;
import org.springframework.web.bind.MethodArgumentNotValidException;
import org.springframework.web.bind.MissingPathVariableException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;
import org.springframework.web.method.annotation.HandlerMethodValidationException;
import org.springframework.web.method.annotation.MethodArgumentTypeMismatchException;
import org.springframework.web.servlet.NoHandlerFoundException;

import java.util.stream.Collectors;

import static com.xinghuiTec.emues.ResultCodeEnum.ADMIN_ACCESS_FORBIDDEN;

/**
 * 全局异常处理器
 *
 * @author xinghuiTec
 */
@Slf4j
@RestControllerAdvice
public class GlobalExceptionHandler {

    // ==================== 请求方式/路径异常 ====================

    /**
     * 请求方式不支持（GET/POST 不匹配）
     */
    @ExceptionHandler(HttpRequestMethodNotSupportedException.class)
    public Result<Void> handleHttpRequestMethodNotSupported(HttpRequestMethodNotSupportedException e) {
        log.error("不支持'{}'请求", e.getMethod());
        return Result.fail(e.getMessage());
    }

    /**
     * 缺少路径变量
     */
    @ExceptionHandler(MissingPathVariableException.class)
    public Result<Void> handleMissingPathVariableException(MissingPathVariableException e) {
        log.error("缺少路径变量: {}", e.getVariableName());
        return Result.fail(String.format("缺少路径变量[%s]", e.getVariableName()));
    }

    /**
     * 请求参数类型不匹配
     */
    @ExceptionHandler(MethodArgumentTypeMismatchException.class)
    public Result<Void> handleMethodArgumentTypeMismatchException(MethodArgumentTypeMismatchException e) {
        log.error("参数类型不匹配: {} -> {}", e.getName(), e.getRequiredType());
        return Result.fail(String.format("参数[%s]类型应为'%s'，实际为'%s'",
                e.getName(), e.getRequiredType().getName(), e.getValue()));
    }

    /**
     * 找不到路由
     */
    @ExceptionHandler(NoHandlerFoundException.class)
    public Result<Void> handleNoHandlerFoundException(NoHandlerFoundException e) {
        log.error("请求地址不存在: {}", e.getRequestURL());
        return Result.fail("请求地址不存在");
    }

    // ==================== 校验异常 ====================

    /**
     * 自定义验证异常（BindException）
     */
    @ExceptionHandler(BindException.class)
    public Result<Void> handleBindException(BindException e) {
        log.error("参数校验失败: {}", e.getMessage());
        String message = e.getAllErrors().stream()
                .map(DefaultMessageSourceResolvable::getDefaultMessage)
                .collect(Collectors.joining(", "));
        return Result.fail(message);
    }

    /**
     * 自定义验证异常（ConstraintViolationException）
     */
    @ExceptionHandler(ConstraintViolationException.class)
    public Result<Void> constraintViolationException(ConstraintViolationException e) {
        log.error("参数校验失败: {}", e.getMessage());
        String message = e.getConstraintViolations().stream()
                .map(ConstraintViolation::getMessage)
                .collect(Collectors.joining(", "));
        return Result.fail(message);
    }

    /**
     * 自定义验证异常（MethodArgumentNotValidException）
     */
    @ExceptionHandler(MethodArgumentNotValidException.class)
    public Result<Void> handleMethodArgumentNotValidException(MethodArgumentNotValidException e) {
        log.error("参数校验失败: {}", e.getMessage());
        String message = e.getBindingResult().getAllErrors().stream()
                .map(DefaultMessageSourceResolvable::getDefaultMessage)
                .collect(Collectors.joining(", "));
        return Result.fail(message);
    }

    /**
     * 方法参数校验异常（@Validated on controller）
     */
    @ExceptionHandler(HandlerMethodValidationException.class)
    public Result<Void> handleHandlerMethodValidationException(HandlerMethodValidationException e) {
        log.error("方法参数校验失败: {}", e.getMessage());
        String message = e.getAllErrors().stream()
                .map(org.springframework.context.MessageSourceResolvable::getDefaultMessage)
                .collect(Collectors.joining(", "));
        return Result.fail(message);
    }

    // ==================== 业务异常 ====================

    /**
     * 基础异常
     */
    @ExceptionHandler(BaseException.class)
    public Result<Void> handleBaseException(BaseException e) {
        log.error("业务异常: {}", e.getMessage());
        return Result.fail(e.getMessage());
    }

    /**
     * 业务异常（ServiceException，支持占位符）
     */
    @ExceptionHandler(ServiceException.class)
    public Result<Void> handleServiceException(ServiceException e) {
        log.error("业务异常: {}", e.getMessage());
        Integer code = e.getCode();
        return ObjectUtil.isNotNull(code) ? Result.fail(code, e.getMessage()) : Result.fail(e.getMessage());
    }

    /**
     * 用户不存在异常
     */
    @ExceptionHandler(UserNotExistsException.class)
    public Result<Void> handleUserNotExistsException(UserNotExistsException e) {
        log.warn("用户不存在: {}", e.getMessage());
        return Result.fail(ResultCodeEnum.ADMIN_ACCOUNT_ERROR.getCode(), "用户不存在");
    }

    /**
     * 密码错误异常
     */
    @ExceptionHandler(UserPasswordNotMatchException.class)
    public Result<Void> handleUserPasswordNotMatchException(UserPasswordNotMatchException e) {
        log.warn("密码错误: {}", e.getMessage());
        return Result.fail(ResultCodeEnum.ADMIN_ACCOUNT_ERROR.getCode(), "用户名或密码错误");
    }

    /**
     * 验证码错误异常
     */
    @ExceptionHandler(CaptchaException.class)
    public Result<Void> handleCaptchaException(CaptchaException e) {
        log.warn("验证码错误: {}", e.getMessage());
        return Result.fail("验证码错误");
    }

    /**
     * 验证码过期异常
     */
    @ExceptionHandler(CaptchaExpireException.class)
    public Result<Void> handleCaptchaExpireException(CaptchaExpireException e) {
        log.warn("验证码过期: {}", e.getMessage());
        return Result.fail("验证码已过期，请刷新重试");
    }

    /**
     * 租户异常
     */
    @ExceptionHandler(TenantException.class)
    public Result<Void> handleTenantException(TenantException e) {
        log.warn("租户异常: {}", e.getMessage());
        return Result.fail(e.getMessage());
    }

    /**
     * 黑名单异常
     */
    @ExceptionHandler(BlackListException.class)
    public Result<Void> handleBlackListException(BlackListException e) {
        log.error("黑名单访问: {}", e.getMessage());
        return Result.fail("访问受限，您的IP已被加入黑名单");
    }

    /**
     * 密码重试次数超限
     */
    @ExceptionHandler(UserPasswordRetryLimitExceedException.class)
    public Result<Void> handleUserPasswordRetryLimitExceedException(UserPasswordRetryLimitExceedException e) {
        log.warn("密码重试超限: {}", e.getMessage());
        return Result.fail("密码错误次数过多，账户已锁定，请稍后再试");
    }

    // ==================== Spring Security 异常 ====================

    @ExceptionHandler(UsernameNotFoundException.class)
    public Result<Void> handleUsernameNotFoundException(UsernameNotFoundException e) {
        log.warn("用户未找到: {}", e.getMessage());
        return Result.fail(ResultCodeEnum.ADMIN_ACCOUNT_ERROR.getCode(), "用户名或密码错误");
    }

    @ExceptionHandler(AccessDeniedException.class)
    public Result<Void> handleAccessDeniedException(AccessDeniedException e) {
        return Result.fail(ADMIN_ACCESS_FORBIDDEN);
    }

    /**
     * 账号已停用（Spring Security 认证前置检查抛出）
     */
    @ExceptionHandler(DisabledException.class)
    public Result<Void> handleDisabledException(DisabledException e) {
        log.warn("停用账号尝试登录: {}", e.getMessage());
        return Result.fail("账号已被停用，请联系管理员");
    }

    @ExceptionHandler(BadCredentialsException.class)
    public Result<Void> handleBadCredentialsException(BadCredentialsException e) {
        return Result.fail(ResultCodeEnum.ADMIN_ACCOUNT_ERROR.getCode(), "手机号或密码错误");
    }

    // ==================== 数据库异常 ====================
    // 必须在 RuntimeException 兜底之前单独处理：数据库异常信息含 SQL/表结构细节，
    // 原样返回给客户端会泄漏内部实现

    /**
     * 唯一索引冲突（并发 check-then-insert 场景由数据库唯一索引兜底）
     */
    @ExceptionHandler(DuplicateKeyException.class)
    public Result<Void> handleDuplicateKeyException(DuplicateKeyException e) {
        log.warn("唯一约束冲突: {}", e.getMessage());
        return Result.fail("数据已存在，请勿重复提交");
    }

    /**
     * 其他数据库访问异常
     */
    @ExceptionHandler(DataAccessException.class)
    public Result<Void> handleDataAccessException(DataAccessException e) {
        log.error("数据库访问异常", e);
        return Result.fail("数据库操作异常，请联系管理员");
    }

    // ==================== 兜底异常 ====================

    @ExceptionHandler(RuntimeException.class)
    public Result<Void> handleRuntimeException(RuntimeException e) {
        log.error("运行时异常: {}", e.getMessage(), e);
        return Result.fail(e.getMessage() != null ? e.getMessage() : "系统运行异常");
    }

    @ExceptionHandler(Exception.class)
    public Result<Void> handleException(Exception e) {
        log.error("系统异常: {}", e.getMessage(), e);
        return Result.fail("系统错误，请联系管理员");
    }
}
