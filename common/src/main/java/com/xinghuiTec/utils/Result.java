package com.xinghuiTec.utils;

import com.xinghuiTec.emues.ResultCodeEnum;
import lombok.Data;

/**
 * 全局统一返回结果类
 * 用于封装 API 响应的数据，包括状态码、消息和数据本身。
 */
@Data
public class Result<T> {

    // 返回码，表示请求的处理结果
    private Integer code;

    // 返回消息，用于描述请求的处理情况
    private String message;

    // 返回数据，用于携带实际的业务数据
    private T data;

    /**
     * 构造函数，无参构造函数
     */
    public Result() {
    }

    /**
     * 私有静态方法，用于构建 Result 对象
     * @param data 业务数据
     * @return 构建的 Result 对象
     */
    private static <T> Result<T> build(T data) {
        Result<T> result = new Result<>();
        if (data != null) {
            result.setData(data);
        }
        return result;
    }

    /**
     * 私有静态方法，用于构建 Result 对象
     * @return 构建的 Result 对象
     */
    private static <T> Result<T> build() {
        return new Result<>();
    }

    /**
     * 构建带有状态码和消息的 Result 对象
     * @param body 业务数据
     * @param resultCodeEnum 状态码枚举对象
     * @return 构建的 Result 对象
     */
    public static <T> Result<T> build(T body, ResultCodeEnum resultCodeEnum) {
        Result<T> result = build(body);
        result.setCode(resultCodeEnum.getCode());
        result.setMessage(resultCodeEnum.getMessage());
        return result;
    }

    /**
     * 构建带有状态码Result 对象
     * @param resultCodeEnum 状态码枚举对象
     * @return 构建的 Result 对象
     */
    public static <T> Result<T> build(ResultCodeEnum resultCodeEnum) {
        Result<T> result = build();
        result.setCode(resultCodeEnum.getCode());
        result.setMessage(resultCodeEnum.getMessage());
        return result;
    }

    /**
     * 构建成功的 Result 对象，携带业务数据
     * @param data 业务数据
     * @return 构建的 Result 对象
     */
    public static <T> Result<T> ok(T data) {
        return build(data, ResultCodeEnum.SUCCESS);
    }


    /**
     * 构建成功的 Result 对象，不携带业务数据
     * @return 构建的 Result 对象
     */
    public static <T> Result<T> ok() {
        return Result.ok(null);
    }

    /**
     * 构建失败的 Result 对象，不携带业务数据
     * @return 构建的 Result 对象
     */
    public static <T> Result<T> fail() {
        return build(null, ResultCodeEnum.FAIL);
    }

    /**
     * 构建失败的 Result 对象，不携带业务数据
     * @return 构建的 Result 对象
     */
    public static <T> Result<T> fail(T data) {
        return build(data, ResultCodeEnum.FAIL);
    }

    /**
     * 构建成功的 Result 对象，携带业务数据
     * @return 构建的 Result 对象
     */
    public static <T> Result<T> fail(ResultCodeEnum resultCodeEnum) {
        return build(resultCodeEnum);
    }


    /**
     * 构建失败的 Result 对象，自定义状态码和消息
     * @param code 自定义状态码
     * @param message 自定义消息
     * @return 构建的 Result 对象
     */
    public static <T> Result<T> fail(Integer code, String message) {
        Result<T> result = build(null);
        result.setCode(code);
        result.setMessage(message);
        return result;
    }
}