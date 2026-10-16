package com.xinghuiTec.ai.template;

import com.fasterxml.jackson.databind.JsonNode;
import java.util.List;
import java.util.Map;

/** Management configuration only. Never contains credentials or an executable source. */
public record TemplateDefinition(String name, String purpose, String owner, List<Message> messages,
                                 List<Variable> variables, List<Binding> resources, int maxChars) {
    @com.fasterxml.jackson.annotation.JsonAnySetter
    public void rejectUnknown(String name, Object value) { throw new IllegalArgumentException("不支持的模板配置字段"); }
    public enum Section { INSTRUCTION, INPUT, DATA }
    public enum Type { STRING, NUMBER, BOOLEAN, OBJECT, ARRAY }
    public record Message(Section section, String text) { @Override public String toString() { return "Message[section=" + section + ",text=REDACTED]"; } }
    public record Variable(String name, Type type, boolean required, boolean hasDefault,
                           JsonNode defaultValue, int maxChars, boolean sensitive, boolean truncate) { @Override public String toString() { return "Variable[name=" + name + ",default=REDACTED]"; } }
    public enum ParameterSource { BUSINESS_REF, INPUT, ACTOR }
    public record Parameter(ParameterSource source, String key) {}
    public record Binding(String name, String resourceId, String resourceVersionId, boolean required,
                          boolean omitOnMissing, Map<String, Parameter> parameters,
                          Map<String, String> outputs) {}
    @Override public String toString() { return "TemplateDefinition[REDACTED]"; }
}
