package com.xinghuiTec.notification;

import java.net.URI;
import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/** Validates platform-authored templates and renders only declared business variables. */
public final class NotificationTemplateRules {
    private static final Pattern NAME = Pattern.compile("[a-z][A-Za-z0-9_]{0,39}");
    private static final Pattern TOKEN = Pattern.compile("\\{([a-z][A-Za-z0-9_]{0,39})\\}");
    private static final Pattern INTERNAL_TARGET = Pattern.compile("/[A-Za-z0-9/_?&=.{}}-]*");
    private final Set<String> allowedHosts;

    public record Rendered(String title, String body, String target) {}

    public NotificationTemplateRules(Set<String> allowedHosts) {
        this.allowedHosts = allowedHosts.stream()
                .map(host -> host.toLowerCase(Locale.ROOT).trim())
                .filter(host -> !host.isBlank()).collect(java.util.stream.Collectors.toUnmodifiableSet());
    }

    public void validate(String title, String body, String target, List<String> allowedVariables) {
        if (title == null || title.isBlank() || title.length() > 200
                || body == null || body.isBlank() || body.length() > 2000) {
            throw rejected("TEMPLATE_LENGTH", "模板标题或正文为空或过长");
        }
        if (allowedVariables == null || allowedVariables.size() > 16) {
            throw rejected("VARIABLE_DECLARATION", "变量声明最多 16 个");
        }
        Set<String> names = new HashSet<>();
        for (String name : allowedVariables) {
            if (name == null || !NAME.matcher(name).matches() || !names.add(name)) {
                throw rejected("VARIABLE_DECLARATION", "变量名无效或重复");
            }
        }
        Set<String> used = new HashSet<>();
        collectTokens(title, used);
        collectTokens(body, used);
        if (target != null && !target.isBlank()) {
            if (target.length() > 512) throw rejected("TARGET_INVALID", "目标链接过长");
            collectTokens(target, used);
            validateTarget(target.replaceAll("\\{[a-z][A-Za-z0-9_]{0,39}\\}", "x"), true);
        }
        if (!names.containsAll(used)) {
            throw rejected("VARIABLE_UNDECLARED", "模板包含未声明变量");
        }
    }

    public Rendered render(String title, String body, String target, List<String> allowedVariables,
                           Map<String, String> variables) {
        validate(title, body, target, allowedVariables);
        if (variables == null || variables.size() > 16) {
            throw rejected("VARIABLE_UNKNOWN", "事件变量不符合模板白名单");
        }
        Set<String> allowed = Set.copyOf(allowedVariables);
        if (!allowed.containsAll(variables.keySet())) {
            throw rejected("VARIABLE_UNKNOWN", "事件包含未授权变量");
        }
        Set<String> required = new HashSet<>();
        collectTokens(title, required);
        collectTokens(body, required);
        if (target != null) collectTokens(target, required);
        for (String key : required) {
            String value = variables.get(key);
            if (value == null || value.isBlank()) {
                throw rejected("VARIABLE_MISSING", "缺少模板参数: " + key);
            }
        }
        for (String value : variables.values()) {
            if (value == null || value.length() > 500 || value.chars().anyMatch(c -> c < 0x20 && c != '\n')) {
                throw rejected("VARIABLE_LENGTH", "事件变量为空、过长或含控制字符");
            }
        }
        String renderedTitle = replace(title, variables, false);
        String renderedBody = replace(body, variables, false);
        String renderedTarget = target == null || target.isBlank() ? null : replace(target, variables, true);
        if (renderedTitle.length() > 200 || renderedBody.length() > 4000
                || renderedTarget != null && renderedTarget.length() > 1024) {
            throw rejected("CONTENT_LENGTH", "渲染后的通知内容过长");
        }
        if (renderedTarget != null) validateTarget(renderedTarget, false);
        return new Rendered(renderedTitle, renderedBody, renderedTarget);
    }

    /** Recheck a stored target at delivery time, including the current external-host allowlist. */
    public void validateRenderedTarget(String target) {
        if (target == null) return;
        if (target.length() > 1024) throw rejected("TARGET_INVALID", "目标链接过长");
        validateTarget(target, false);
    }

    private static void collectTokens(String input, Set<String> names) {
        Matcher matcher = TOKEN.matcher(input);
        while (matcher.find()) names.add(matcher.group(1));
        String remainder = matcher.replaceAll("");
        if (remainder.indexOf('{') >= 0 || remainder.indexOf('}') >= 0) {
            throw rejected("VARIABLE_DECLARATION", "占位符格式无效");
        }
    }

    private static String replace(String template, Map<String, String> variables, boolean urlEncode) {
        Matcher matcher = TOKEN.matcher(template);
        StringBuilder output = new StringBuilder();
        while (matcher.find()) {
            String value = variables.get(matcher.group(1));
            if (urlEncode) value = URLEncoder.encode(value, StandardCharsets.UTF_8).replace("+", "%20");
            matcher.appendReplacement(output, Matcher.quoteReplacement(value));
        }
        matcher.appendTail(output);
        return output.toString();
    }

    private void validateTarget(String target, boolean template) {
        if (target.indexOf('\\') >= 0 || target.chars().anyMatch(c -> c < 0x20)) {
            throw rejected("TARGET_INVALID", "目标路径无效");
        }
        if (target.startsWith("/") && !target.startsWith("//")) {
            if (template && !INTERNAL_TARGET.matcher(target).matches()) {
                throw rejected("TARGET_INVALID", "站内路径含不允许字符");
            }
            String path = target.split("[?#]", 2)[0];
            for (String segment : path.split("/")) {
                if (segment.equals("..") || segment.equals(".")) {
                    throw rejected("TARGET_INVALID", "站内路径不可上跳");
                }
            }
            return;
        }
        if (template) {
            int schemeEnd = target.indexOf("://");
            int authorityEnd = target.length();
            if (schemeEnd >= 0) {
                for (int i = schemeEnd + 3; i < target.length(); i++) {
                    if (target.charAt(i) == '/' || target.charAt(i) == '?'
                            || target.charAt(i) == '#') {
                        authorityEnd = i;
                        break;
                    }
                }
            }
            String authority = schemeEnd < 0 ? "" : target.substring(schemeEnd + 3,
                    authorityEnd);
            if (authority.indexOf('{') >= 0 || authority.indexOf('}') >= 0) {
                throw rejected("TARGET_INVALID", "外链域名不可使用变量");
            }
        }
        try {
            URI uri = URI.create(target);
            if (!"https".equalsIgnoreCase(uri.getScheme()) || uri.getHost() == null
                    || uri.getUserInfo() != null || uri.getPort() != -1 && uri.getPort() != 443
                    || !allowedHosts.contains(uri.getHost().toLowerCase(Locale.ROOT))) {
                throw rejected("TARGET_INVALID", "外链域名未获准许");
            }
        } catch (IllegalArgumentException exception) {
            throw rejected("TARGET_INVALID", "目标链接格式无效");
        }
    }

    private static NotificationContractException rejected(String code, String message) {
        return new NotificationContractException(code, message);
    }
}
