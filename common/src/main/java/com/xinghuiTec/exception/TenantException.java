package com.xinghuiTec.exception;

import com.xinghuiTec.exception.base.BaseException;

/**
 * 租户异常类
 *
 * @author xinghuiTec
 */
public class TenantException extends BaseException {

    private static final long serialVersionUID = 1L;

    public TenantException(String message) {
        super("tenant", message);
    }
}
