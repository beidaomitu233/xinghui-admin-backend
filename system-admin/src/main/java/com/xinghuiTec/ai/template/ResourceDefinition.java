package com.xinghuiTec.ai.template;

import java.util.List;
import java.util.Set;

/** First registered source has fixed, typed formId/recordId parameters, no URLs or SQL. */
public record ResourceDefinition(String name, String domain, String owner, String source,
                                 String formId, List<String> fields, Set<String> subjects,
                                 Set<String> applications, int timeoutMillis) {
    @com.fasterxml.jackson.annotation.JsonAnySetter
    public void rejectUnknown(String name, Object value) { throw new IllegalArgumentException("不支持的资源配置字段"); }
    public ResourceDefinition {
        if (fields != null) fields = List.copyOf(fields);
        if (subjects != null) subjects = java.util.Collections.unmodifiableSortedSet(new java.util.TreeSet<>(subjects));
        if (applications != null) applications = java.util.Collections.unmodifiableSortedSet(new java.util.TreeSet<>(applications));
    }
    @Override public String toString() { return "ResourceDefinition[REDACTED]"; }
}
