package com.xinghuiTec.exception.base;

/**
 * 基础异常类
 * 
 * @author xinghuiTec
 */
public class BaseException extends RuntimeException {
    private static final long serialVersionUID = 1L;

    /**
     * 所属模块
     */
    private String module;

    /**
     * 错误码
     */
    private String code;

    /**
     * 错误码对应的参数
     */
    private Object[] args;

    /**
     * 错误消息
     */
    private String defaultMessage;

    public BaseException(String module, String code, Object[] args, String defaultMessage) {
        this.module = module;
        this.code = code;
        this.args = args;
        this.defaultMessage = defaultMessage;
    }

    public BaseException(String module, String defaultMessage) {
        this.module = module;
        this.defaultMessage = defaultMessage;
    }

    @Override
    public String getMessage() {
        if (defaultMessage != null) {
            return defaultMessage;
        }
        return code != null ? code : super.getMessage();
    }

    public String getModule() {
        return module;
    }

    public String getCode() {
        return code;
    }

    public Object[] getArgs() {
        return args;
    }

    public String getDefaultMessage() {
        return defaultMessage;
    }
}
