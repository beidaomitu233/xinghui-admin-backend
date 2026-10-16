package com.xinghuiTec.ai.model;

import com.xinghuiTec.exception.ServiceException;
import org.springframework.stereotype.Component;

import java.net.Inet6Address;
import java.net.InetAddress;
import java.net.URI;
import java.net.URISyntaxException;
import java.util.Locale;
import java.util.regex.Pattern;

/** Validates stored model metadata without resolving DNS or contacting the configured endpoint. */
@Component
public class AiModelConfigRules {
    private static final Pattern PROVIDER_CODE = Pattern.compile("[a-z][a-z0-9.-]{0,63}");
    private static final Pattern MODEL_CODE = Pattern.compile("[A-Za-z0-9][A-Za-z0-9._:/-]{0,127}");
    private static final Pattern ENV_NAME = Pattern.compile("[A-Z][A-Z0-9]*(?:_[A-Z0-9]+)+");
    private static final Pattern NUMERIC_HOST = Pattern.compile("[0-9.]+");
    private static final Pattern DNS_LABEL = Pattern.compile("[A-Za-z0-9](?:[A-Za-z0-9-]{0,61}[A-Za-z0-9])?");

    public String providerCode(String value) {
        String normalized = required(value, "供应方标识");
        if (!PROVIDER_CODE.matcher(normalized).matches()) {
            throw new ServiceException("供应方标识仅允许小写字母、数字、点和连字符，且须以字母开头");
        }
        return normalized;
    }

    public String modelCode(String value) {
        String normalized = required(value, "模型代号");
        if (!MODEL_CODE.matcher(normalized).matches()) {
            throw new ServiceException("模型代号格式无效");
        }
        return normalized;
    }

    public String credentialEnvName(String value) {
        if (value == null || value.isBlank()) {
            return null;
        }
        String normalized = value.trim();
        if (!ENV_NAME.matcher(normalized).matches()) {
            throw new ServiceException("凭据引用须为大写下划线环境变量名，例如 AI_PROVIDER_API_KEY；不能填写密钥明文");
        }
        return normalized;
    }

    public int timeoutSeconds(Integer value) {
        if (value == null || value < 1 || value > 300) {
            throw new ServiceException("超时须为 1～300 秒");
        }
        return value;
    }

    public int revision(Integer value) {
        if (value == null || value < 0) {
            throw new ServiceException("配置版本无效，请刷新后重试");
        }
        return value;
    }

    public String endpointUrl(String value) {
        String input = required(value, "服务端点");
        if (input.length() > 2048) {
            throw new ServiceException("服务端点不能超过 2048 个字符");
        }

        final URI uri;
        try {
            uri = new URI(input);
        } catch (URISyntaxException invalid) {
            throw new ServiceException("服务端点 URL 格式无效");
        }
        if (!"https".equalsIgnoreCase(uri.getScheme()) || uri.isOpaque()
                || uri.getHost() == null || uri.getHost().isBlank()
                || uri.getRawUserInfo() != null || uri.getRawFragment() != null
                || uri.getRawQuery() != null || uri.getPort() == 0 || uri.getPort() > 65535) {
            throw new ServiceException("服务端点须为不含用户信息、查询参数或片段的 HTTPS URL");
        }

        validateHost(uri.getHost());
        return uri.normalize().toASCIIString();
    }

    private void validateHost(String rawHost) {
        String host = rawHost;
        if (host.startsWith("[") && host.endsWith("]")) {
            host = host.substring(1, host.length() - 1);
        }

        if (host.indexOf(':') >= 0) {
            validateIpv6(host);
            return;
        }
        if (NUMERIC_HOST.matcher(host).matches()) {
            validateIpv4(host);
            return;
        }

        if (host.length() > 253) {
            throw new ServiceException("服务端点主机名无效");
        }
        String lower = host.toLowerCase(Locale.ROOT);
        if (lower.equals("localhost") || lower.endsWith(".localhost") || lower.endsWith(".local")
                || lower.endsWith(".internal") || lower.endsWith(".lan") || lower.endsWith(".home")) {
            throw new ServiceException("服务端点不能使用本机或内网主机名");
        }
        String[] labels = host.split("\\.", -1);
        if (labels.length < 2) {
            throw new ServiceException("服务端点须使用完整 DNS 主机名或公网 IP 地址");
        }
        for (String label : labels) {
            if (!DNS_LABEL.matcher(label).matches()) {
                throw new ServiceException("服务端点主机名无效");
            }
        }
    }

    private void validateIpv4(String host) {
        String[] parts = host.split("\\.", -1);
        if (parts.length != 4) {
            throw new ServiceException("服务端点 IP 字面量格式无效");
        }
        int[] octets = new int[4];
        for (int index = 0; index < parts.length; index++) {
            if (parts[index].isEmpty() || (parts[index].length() > 1 && parts[index].startsWith("0"))) {
                throw new ServiceException("服务端点 IP 字面量格式无效");
            }
            try {
                octets[index] = Integer.parseInt(parts[index]);
            } catch (NumberFormatException invalid) {
                throw new ServiceException("服务端点 IP 字面量格式无效");
            }
            if (octets[index] > 255) {
                throw new ServiceException("服务端点 IP 字面量格式无效");
            }
        }
        if (isRestrictedIpv4(octets)) {
            throw new ServiceException("服务端点不能使用本机、内网或保留 IP 地址");
        }
    }

    private void validateIpv6(String host) {
        if (host.indexOf('%') >= 0) {
            throw new ServiceException("服务端点不能使用带区域标识的 IPv6 地址");
        }
        final byte[] address;
        try {
            // A colon-containing host is parsed as an IP literal; no DNS lookup is performed.
            InetAddress parsed = InetAddress.getByName(host);
            if (!(parsed instanceof Inet6Address)) {
                throw new ServiceException("服务端点 IPv6 字面量格式无效");
            }
            address = parsed.getAddress();
        } catch (java.net.UnknownHostException invalid) {
            throw new ServiceException("服务端点 IPv6 字面量格式无效");
        }

        if (isIpv4Mapped(address)) {
            int[] octets = {address[12] & 0xff, address[13] & 0xff, address[14] & 0xff, address[15] & 0xff};
            if (isRestrictedIpv4(octets)) {
                throw new ServiceException("服务端点不能使用本机、内网或保留 IP 地址");
            }
            return;
        }

        boolean globalUnicast = (address[0] & 0xff) >= 0x20 && (address[0] & 0xe0) == 0x20;
        boolean documentationRange = (address[0] & 0xff) == 0x20
                && (address[1] & 0xff) == 0x01 && (address[2] & 0xff) == 0x0d
                && (address[3] & 0xff) == 0xb8;
        if (!globalUnicast || documentationRange) {
            throw new ServiceException("服务端点不能使用本机、内网或保留 IP 地址");
        }
    }

    private boolean isIpv4Mapped(byte[] address) {
        for (int index = 0; index < 10; index++) {
            if (address[index] != 0) return false;
        }
        return (address[10] & 0xff) == 0xff && (address[11] & 0xff) == 0xff;
    }

    private boolean isRestrictedIpv4(int[] ip) {
        int a = ip[0], b = ip[1], c = ip[2];
        return a == 0 || a == 10 || a == 127 || a >= 224
                || (a == 169 && b == 254)
                || (a == 172 && b >= 16 && b <= 31)
                || (a == 192 && b == 168)
                || (a == 100 && b >= 64 && b <= 127)
                || (a == 192 && b == 0 && c == 0)
                || (a == 192 && b == 0 && c == 2)
                || (a == 192 && b == 88 && c == 99)
                || (a == 198 && (b == 18 || b == 19))
                || (a == 198 && b == 51 && c == 100)
                || (a == 203 && b == 0 && c == 113);
    }

    private String required(String value, String label) {
        if (value == null || value.isBlank()) {
            throw new ServiceException(label + "不能为空");
        }
        return value.trim();
    }
}
