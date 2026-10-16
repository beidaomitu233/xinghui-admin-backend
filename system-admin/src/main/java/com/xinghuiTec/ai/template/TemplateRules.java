package com.xinghuiTec.ai.template;

import com.fasterxml.jackson.databind.JsonNode;
import com.xinghuiTec.exception.ServiceException;
import java.util.*;
import java.util.regex.Pattern;
import static com.xinghuiTec.ai.template.TemplateDefinition.*;

/** Bounded single-pass substitution. Configuration has no expression evaluation. */
public final class TemplateRules {
    private TemplateRules() {}
    private static final Pattern KEY = Pattern.compile("[A-Za-z][A-Za-z0-9_]{0,63}");
    private static final Pattern SLOT = Pattern.compile("\\{\\{([A-Za-z][A-Za-z0-9_]*)}}");
    public record Notice(String kind, String reference) {}
    public record Render(List<Message> messages, List<Notice> notices) {
        @Override public String toString() { return "Render[REDACTED]"; }
    }
    public static void validate(TemplateDefinition d) {
        if (d == null) throw invalid("请填写模板");
        text(d.name(), 100); text(d.purpose(), 500); text(d.owner(), 100);
        if (d.maxChars() < 256 || d.maxChars() > 32000 || d.variables() == null || d.variables().size() > 64
                || d.resources() == null || d.resources().size() > 16 || d.messages() == null
                || d.messages().isEmpty() || d.messages().size() > 12) throw invalid("模板数量或长度限制无效");
        Map<String, Variable> variables = new LinkedHashMap<>();
        for (var v : d.variables()) {
            if (v == null || !key(v.name()) || variables.putIfAbsent(v.name(), v) != null || v.type() == null
                    || v.maxChars() < 1 || v.maxChars() > 8000) throw invalid("变量定义无效");
            if (v.hasDefault()) {
                value(v, v.defaultValue());
                if (v.required() && v.defaultValue().isTextual() && v.defaultValue().textValue().isBlank()) throw invalid("必填变量默认值不能为空");
            }
            else if (v.defaultValue() != null && !v.defaultValue().isNull()) throw invalid("默认值必须显式启用");
        }
        Set<String> resourceVariables = new HashSet<>(), names = new HashSet<>();
        for (var b : d.resources()) {
            if (b == null || !key(b.name()) || !names.add(b.name()) || !id(b.resourceId()) || !id(b.resourceVersionId())
                    || b.parameters() == null || b.outputs() == null || b.outputs().isEmpty()
                    || (b.required() && b.omitOnMissing())) throw invalid("资源映射无效");
            if (!b.parameters().keySet().equals(Set.of("formId", "recordId"))) throw invalid("资源参数须为formId和recordId");
            for (var p : b.parameters().values()) {
                if (p == null || p.source() == null || p.key() == null) throw invalid("参数来源无效");
                if (p.source() == ParameterSource.BUSINESS_REF && !Set.of("formId", "recordId").contains(p.key())) throw invalid("业务引用字段无效");
                if (p.source() == ParameterSource.INPUT && (!variables.containsKey(p.key()) || !variables.get(p.key()).required())) throw invalid("参数须来自必填输入");
                if (p.source() == ParameterSource.ACTOR && !Set.of("subjectId", "tenantId").contains(p.key())) throw invalid("身份字段无效");
            }
            for (var entry : b.outputs().entrySet()) {
                if (entry.getKey() == null || !entry.getKey().matches("[a-z][a-z0-9_]{0,49}") || !variables.containsKey(entry.getValue())
                        || !resourceVariables.add(entry.getValue()) || variables.get(entry.getValue()).hasDefault()
                        || (!b.required() && variables.get(entry.getValue()).required())) throw invalid("返回字段映射无效，可选资源的变量也须可选");
            }
        }
        int total = 0;
        for (var m : d.messages()) {
            if (m == null || m.section() == null) throw invalid("消息分区无效");
            text(m.text(), 12000); total += m.text().length();
            var slots = SLOT.matcher(m.text());
            while (slots.find()) {
                String name = slots.group(1);
                if (!variables.containsKey(name) || m.section() == Section.INSTRUCTION
                        || (m.section() == Section.DATA) != resourceVariables.contains(name)) throw invalid("占位符必须位于对应输入或业务数据分区");
            }
            String remaining = SLOT.matcher(m.text()).replaceAll("");
            if (remaining.contains("{{") || remaining.contains("}}")) throw invalid("占位符格式无效");
        }
        if (total > d.maxChars()) throw invalid("模板原文超出总长度");
    }
    public static Render render(TemplateDefinition d, Map<String, JsonNode> input,
                                Map<String, JsonNode> data, boolean mask) {
        validate(d);
        if (input == null || data == null) throw invalid("输入无效");
        Set<String> fromResources = new HashSet<>();
        d.resources().forEach(b -> fromResources.addAll(b.outputs().values()));
        Set<String> declared = new HashSet<>(); d.variables().forEach(v -> declared.add(v.name()));
        if (!declared.containsAll(input.keySet()) || !declared.containsAll(data.keySet())
                || input.keySet().stream().anyMatch(fromResources::contains)
                || data.keySet().stream().anyMatch(n -> !fromResources.contains(n))) throw invalid("输入字段不允许");
        Map<String, String> values = new HashMap<>(); List<Notice> notices = new ArrayList<>();
        for (var v : d.variables()) {
            JsonNode node = fromResources.contains(v.name()) ? data.get(v.name()) : input.get(v.name());
            if (node == null || node.isNull() || (node.isTextual() && node.textValue().isBlank())) {
                if (v.hasDefault()) { node = v.defaultValue(); notices.add(new Notice("DEFAULT_USED", v.name())); }
                else if (v.required()) throw invalid("缺少必需变量: " + v.name());
                else { values.put(v.name(), ""); continue; }
            }
            String value = value(v, node);
            if (value.length() > v.maxChars()) { value = value.substring(0, v.maxChars()); notices.add(new Notice("TRUNCATED", v.name())); }
            values.put(v.name(), mask && (v.sensitive() || fromResources.contains(v.name())) ? "[已脱敏]" : value);
        }
        List<Message> messages = new ArrayList<>(); int length = 0;
        for (var message : d.messages()) {
            var matcher = SLOT.matcher(message.text()); StringBuilder buffer = new StringBuilder();
            while (matcher.find()) matcher.appendReplacement(buffer, java.util.regex.Matcher.quoteReplacement(values.get(matcher.group(1))));
            matcher.appendTail(buffer); length += buffer.length();
            if (length > d.maxChars()) throw invalid("渲染后超出总长度限制");
            messages.add(new Message(message.section(), buffer.toString()));
        }
        return new Render(List.copyOf(messages), List.copyOf(notices));
    }
    public static void validateInputs(TemplateDefinition d, Map<String, JsonNode> inputs) {
        Set<String> resourceVars = new HashSet<>(); d.resources().forEach(b -> resourceVars.addAll(b.outputs().values()));
        Set<String> allowed = new HashSet<>();
        for (var v : d.variables()) {
            if (resourceVars.contains(v.name())) continue;
            allowed.add(v.name()); JsonNode node = inputs.get(v.name());
            if (node == null || node.isNull() || (node.isTextual() && node.textValue().isBlank())) {
                if (v.hasDefault()) node = v.defaultValue();
                else if (v.required()) throw invalid("缺少必需变量: " + v.name());
                else continue;
            }
            value(v, node);
        }
        if (!allowed.containsAll(inputs.keySet())) throw invalid("输入字段不允许");
    }
    private static String value(Variable v, JsonNode n) {
        if (n == null || n.isNull() || !switch (v.type()) {
            case STRING -> n.isTextual(); case NUMBER -> n.isNumber(); case BOOLEAN -> n.isBoolean();
            case OBJECT -> n.isObject(); case ARRAY -> n.isArray();
        }) throw invalid("变量类型不匹配: " + v.name());
        String value = n.isTextual() ? n.textValue() : n.toString();
        if (value.length() > 65536 || (value.length() > v.maxChars() && (!v.truncate() || v.type() != Type.STRING))) throw invalid("变量超长: " + v.name());
        return value;
    }
    public static void validate(ResourceDefinition d) {
        if (d == null) throw invalid("请填写资源");
        text(d.name(), 100); text(d.domain(), 100); text(d.owner(), 100);
        if (!"lowcode.record".equals(d.source()) || !id(d.formId()) || d.fields() == null || d.fields().isEmpty()
                || d.fields().size() > 64 || new HashSet<>(d.fields()).size() != d.fields().size()
                || d.fields().stream().anyMatch(f -> f == null || !f.matches("[a-z][a-z0-9_]{0,49}"))
                || d.subjects() == null || d.subjects().isEmpty() || d.subjects().size() > 100
                || d.subjects().stream().anyMatch(s -> !id(s)) || d.applications() == null || d.applications().isEmpty()
                || d.applications().size() > 100 || d.applications().stream().anyMatch(s -> !id(s))
                || d.timeoutMillis() < 100 || d.timeoutMillis() > 5000) throw invalid("资源范围或来源无效");
    }
    public static boolean id(String id) { return id != null && id.matches("[1-9][0-9]{0,18}") && new java.math.BigInteger(id).bitLength() <= 63; }
    private static boolean key(String key) { return key != null && KEY.matcher(key).matches(); }
    private static void text(String text, int max) { if (text == null || text.isBlank() || text.length() > max) throw invalid("名称、用途、负责人或消息长度无效"); }
    public static ServiceException invalid(String message) { return new ServiceException(message); }
}
