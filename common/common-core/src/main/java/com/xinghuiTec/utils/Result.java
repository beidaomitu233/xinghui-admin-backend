package com.xinghuiTec.utils;

import com.xinghuiTec.emues.ResultCodeEnum;
import lombok.Data;

/**
 * 全局统一返回结果类
 */
@Data
public class Result<T> {

    private Integer code;
    private String message;
    private T data;

    public Result() {}

    private static <T> Result<T> build(T data) {
        Result<T> result = new Result<>();
        if (data != null) {
            result.setData(data);
        }
        return result;
    }

    private static <T> Result<T> build() {
        return new Result<>();
    }

    public static <T> Result<T> build(T body, ResultCodeEnum resultCodeEnum) {
        Result<T> result = build(body);
        result.setCode(resultCodeEnum.getCode());
        result.setMessage(resultCodeEnum.getMessage());
        return result;
    }

    public static <T> Result<T> build(ResultCodeEnum resultCodeEnum) {
        Result<T> result = build();
        result.setCode(resultCodeEnum.getCode());
        result.setMessage(resultCodeEnum.getMessage());
        return result;
    }

    // ========== 成功 ==========

    public static <T> Result<T> ok(T data) {
        return build(data, ResultCodeEnum.SUCCESS);
    }

    public static <T> Result<T> ok() {
        return ok(null);
    }

    public static <T> Result<T> ok(String message, T data) {
        Result<T> result = build(data, ResultCodeEnum.SUCCESS);
        result.setMessage(message);
        return result;
    }

    // ========== 失败 ==========

    public static <T> Result<T> fail() {
        return build(ResultCodeEnum.FAIL);
    }

    public static <T> Result<T> fail(String message) {
        Result<T> result = build(null);
        result.setCode(ResultCodeEnum.FAIL.getCode());
        result.setMessage(message);
        return result;
    }

    public static <T> Result<T> fail(Integer code, String message) {
        Result<T> result = build(null);
        result.setCode(code);
        result.setMessage(message);
        return result;
    }

    public static <T> Result<T> fail(ResultCodeEnum resultCodeEnum) {
        return build(resultCodeEnum);
    }

}
