package com.xinghuiTec.utils;

import jakarta.servlet.http.HttpServletRequest;

/**
 * IP 地址工具类
 * 用于获取客户端真实 IP 地址（支持代理和负载均衡）
 * 
 * @author beidoa23
 * @since 2026-01-23
 */
public class IpUtils {

    private static final String UNKNOWN = "unknown";
    private static final String LOCALHOST_IPV4 = "127.0.0.1";
    private static final String LOCALHOST_IPV6 = "0:0:0:0:0:0:0:1";
    private static final int IP_LENGTH = 15;

    /**
     * 获取客户端真实 IP 地址
     * 优先从代理请求头中获取，支持多级代理
     * 
     * @param request HttpServletRequest 对象
     * @return 客户端 IP 地址
     */
    public static String getIpAddr(HttpServletRequest request) {
        if (request == null) {
            return UNKNOWN;
        }

        String ip = request.getHeader("X-Forwarded-For");
        if (isInvalidIp(ip)) {
            ip = request.getHeader("Proxy-Client-IP");
        }
        if (isInvalidIp(ip)) {
            ip = request.getHeader("WL-Proxy-Client-IP");
        }
        if (isInvalidIp(ip)) {
            ip = request.getHeader("HTTP_CLIENT_IP");
        }
        if (isInvalidIp(ip)) {
            ip = request.getHeader("HTTP_X_FORWARDED_FOR");
        }
        if (isInvalidIp(ip)) {
            ip = request.getHeader("X-Real-IP");
        }
        if (isInvalidIp(ip)) {
            ip = request.getRemoteAddr();
        }

        // 处理多级代理的情况，取第一个非 unknown 的 IP
        if (ip != null && ip.length() > IP_LENGTH) {
            int index = ip.indexOf(",");
            if (index > 0) {
                ip = ip.substring(0, index);
            }
        }

        // 将 IPv6 本地地址转换为 IPv4
        if (LOCALHOST_IPV6.equals(ip)) {
            ip = LOCALHOST_IPV4;
        }

        return ip == null ? UNKNOWN : ip;
    }

    /**
     * 检查 IP 地址是否无效
     * 
     * @param ip IP 地址
     * @return true: 无效, false: 有效
     */
    private static boolean isInvalidIp(String ip) {
        return ip == null || ip.isEmpty() || UNKNOWN.equalsIgnoreCase(ip);
    }
}
