package com.xinghuiTec.ratelimiter.enums;

/**
 * 限流类型
 *
 * @author xinghuiTec
 */
public enum LimitType {

    /** 全局限流（所有用户共享限额） */
    DEFAULT,

    /** 按请求者 IP 限流（每个IP独立限额） */
    IP
}
