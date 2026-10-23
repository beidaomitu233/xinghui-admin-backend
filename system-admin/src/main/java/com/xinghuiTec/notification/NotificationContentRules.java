package com.xinghuiTec.notification;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import java.net.URI;
import java.util.Arrays;
import java.util.Locale;
import java.util.Set;
import java.util.stream.Collectors;

/** Validates immutable content supplied by trusted in-process business event publishers. */
@Component
public final class NotificationContentRules {
    private final Set<String> allowedHosts;

    public NotificationContentRules(@Value("${notification.allowed-link-hosts:}") String hosts) {
        allowedHosts = Arrays.stream(hosts.split(",")).map(String::trim)
                .map(s -> s.toLowerCase(Locale.ROOT)).filter(s -> !s.isBlank()).collect(Collectors.toUnmodifiableSet());
    }

    public void validate(String title, String body, String target) {
        if (title == null || title.isBlank() || title.length() > 200
                || body == null || body.isBlank() || body.length() > 4000
                || title.chars().anyMatch(c -> Character.isISOControl(c))
                || body.chars().anyMatch(c -> Character.isISOControl(c) && c != '\n' && c != '\t')) {
            throw new NotificationContractException("CONTENT_INVALID", "通知标题或正文为空、过长或含无效字符");
        }
        validateTarget(target);
    }

    /** Recheck targets at delivery, including changes to the external-host allowlist. */
    public void validateTarget(String target) {
        if (target == null) return;
        if (target.isBlank() || target.length() > 1024 || target.indexOf('\\') >= 0
                || target.chars().anyMatch(Character::isISOControl)) throw invalidTarget();
        try {
            URI uri = URI.create(target);
            String decoded = uri.getPath();
            if (decoded != null) {
                if (decoded.indexOf('\\') >= 0 || decoded.chars().anyMatch(Character::isISOControl)) throw invalidTarget();
                for (String segment : decoded.split("/")) {
                    if (segment.equals(".") || segment.equals("..")) throw invalidTarget();
                }
            }
            if (target.startsWith("/") && !target.startsWith("//") && uri.getRawAuthority() == null) return;
            if (!"https".equalsIgnoreCase(uri.getScheme()) || uri.getHost() == null || uri.getUserInfo() != null
                    || uri.getPort() != -1 && uri.getPort() != 443
                    || !allowedHosts.contains(uri.getHost().toLowerCase(Locale.ROOT))) throw invalidTarget();
        } catch (IllegalArgumentException exception) {
            throw invalidTarget();
        }
    }

    private static NotificationContractException invalidTarget() {
        return new NotificationContractException("TARGET_INVALID", "通知关联目标无效或未获准许");
    }
}
