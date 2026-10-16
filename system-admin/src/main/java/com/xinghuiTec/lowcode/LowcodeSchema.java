package com.xinghuiTec.lowcode;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.annotation.JsonDeserialize;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.xinghuiTec.exception.ServiceException;
import org.springframework.stereotype.Component;

import java.math.BigDecimal;
import java.nio.charset.StandardCharsets;
import java.time.LocalDate;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.regex.Pattern;

/** The schema is data only. No expressions, HTML, SQL or script hooks are evaluated. */
@Component
public class LowcodeSchema {
    public record Option(String label, String value) {}
    public record Field(String fieldKey, String label, String type, boolean required,
                        JsonNode defaultValue, List<Option> options, String help,
                        @JsonDeserialize(using = LowcodeIntegerDeserializer.class) Integer maxLength, BigDecimal min, BigDecimal max,
                        boolean listVisible, boolean searchable) {}
    public record Schema(List<Field> fields) {}

    private static final Set<String> TYPES = Set.of("text", "textarea", "number", "date", "select", "multiselect", "switch");
    private static final Set<String> RESERVED = Set.of("id", "formid", "schemaversion", "tenantid", "creator",
            "revision", "createtime", "updatetime", "isdeleted", "proto", "constructor", "prototype");
    private static final Pattern KEY = Pattern.compile("[a-z][a-z0-9_]{0,49}");
    private final ObjectMapper mapper;

    public LowcodeSchema(ObjectMapper mapper) {
        this.mapper = mapper;
    }

    public Schema validate(Schema schema) {
        if (schema == null || schema.fields() == null || schema.fields().isEmpty() || schema.fields().size() > 100) {
            throw invalid("字段数量须在1至100之间");
        }
        Set<String> keys = new HashSet<>();
        for (Field field : schema.fields()) {
            if (field == null || field.fieldKey() == null || !KEY.matcher(field.fieldKey()).matches()
                    || RESERVED.contains(field.fieldKey().replace("_", "").toLowerCase()) || !keys.add(field.fieldKey())) {
                throw invalid("字段标识重复、保留或格式不正确");
            }
            if (field.label() == null || field.label().isBlank() || field.label().length() > 100
                    || !TYPES.contains(field.type() == null ? "" : field.type())) {
                throw invalid("字段名称或类型不正确");
            }
            if (field.help() != null && field.help().length() > 500) {
                throw invalid("字段帮助最多500字符");
            }
            int maxLength = limit(field);
            if (maxLength < 1 || maxLength > (field.type().equals("textarea") ? 5000 : 2000)) {
                throw invalid("文本长度限制不正确");
            }
            if ((field.min() != null && field.min().abs().compareTo(new BigDecimal("1000000000000")) > 0)
                    || (field.max() != null && field.max().abs().compareTo(new BigDecimal("1000000000000")) > 0)
                    || (field.min() != null && field.max() != null && field.min().compareTo(field.max()) > 0)) {
                throw invalid("数字范围不正确");
            }
            boolean choice = field.type().equals("select") || field.type().equals("multiselect");
            if (choice) {
                if (field.options() == null || field.options().isEmpty() || field.options().size() > 100) {
                    throw invalid("选项数量须在1至100之间");
                }
                Set<String> values = new HashSet<>();
                for (Option option : field.options()) {
                    if (option == null || option.label() == null || option.label().isBlank() || option.label().length() > 100
                            || option.value() == null || !option.value().matches("[a-zA-Z0-9_-]{1,100}") || !values.add(option.value())) {
                        throw invalid("选项名称、值或重复项不正确");
                    }
                }
            } else if (field.options() != null && !field.options().isEmpty()) {
                throw invalid("当前类型不支持选项");
            }
            if (field.searchable() && field.type().equals("multiselect")) {
                throw invalid("多选字段暂不支持查询条件");
            }
            if (field.defaultValue() != null && !field.defaultValue().isNull()) {
                validateValue(field, field.defaultValue(), false);
            }
        }
        var normalized = new Schema(schema.fields().stream().map(field -> new Field(
                field.fieldKey(), field.label(), field.type(), field.required(), field.defaultValue(),
                field.options() == null ? List.of() : field.options(), field.help() == null ? "" : field.help(),
                limit(field), field.min(), field.max(), field.listVisible(), field.searchable())).toList());
        encode(normalized, 262144, "表单配置");
        return normalized;
    }

    public ObjectNode validateData(Schema schema, JsonNode input, boolean defaults) {
        if (input == null || !input.isObject()) {
            throw invalid("填写数据须为对象");
        }
        Set<String> keys = schema.fields().stream().map(Field::fieldKey).collect(java.util.stream.Collectors.toSet());
        input.fieldNames().forEachRemaining(key -> {
            if (!keys.contains(key)) {
                throw invalid("包含未定义或系统字段");
            }
        });
        ObjectNode result = mapper.createObjectNode();
        for (Field field : schema.fields()) {
            JsonNode value = input.get(field.fieldKey());
            if ((value == null || value.isNull()) && defaults) {
                value = field.defaultValue();
            }
            validateValue(field, value, field.required());
            if (value != null && !value.isNull()) {
                result.set(field.fieldKey(), value.deepCopy());
            }
        }
        encode(result, 262144, "填写数据");
        return result;
    }

    public void validateValue(Field field, JsonNode value, boolean required) {
        boolean empty = value == null || value.isNull() || (value.isTextual() && value.textValue().isBlank())
                || (value.isArray() && value.isEmpty());
        if (empty) {
            if (required) {
                throw invalid(field.label() + "为必填项");
            }
            if (value == null || value.isNull()) {
                return;
            }
        }
        boolean valid = switch (field.type()) {
            case "text", "textarea" -> value.isTextual() && value.textValue().length() <= limit(field);
            case "number" -> value.isNumber() && Double.isFinite(value.doubleValue())
                    && value.decimalValue().abs().compareTo(new BigDecimal("1000000000000")) <= 0
                    && (field.min() == null || value.decimalValue().compareTo(field.min()) >= 0)
                    && (field.max() == null || value.decimalValue().compareTo(field.max()) <= 0)
                    && value.decimalValue().scale() <= 6;
            case "date" -> validDate(value);
            case "select" -> value.isTextual() && field.options().stream().anyMatch(o -> o.value().equals(value.textValue()));
            case "multiselect" -> validChoices(field, value);
            case "switch" -> value.isBoolean();
            default -> false;
        };
        if (!valid) {
            throw invalid(field.label() + "类型、范围或选项不正确");
        }
    }

    private static boolean validDate(JsonNode value) {
        if (!value.isTextual() || !value.textValue().matches("\\d{4}-\\d{2}-\\d{2}")) {
            return false;
        }
        try {
            LocalDate.parse(value.textValue());
            return true;
        } catch (java.time.DateTimeException invalid) {
            return false;
        }
    }

    private static boolean validChoices(Field field, JsonNode value) {
        if (!value.isArray() || value.size() > 100) {
            return false;
        }
        Set<String> selected = new HashSet<>();
        for (JsonNode item : value) {
            if (!item.isTextual() || !selected.add(item.textValue())
                    || field.options().stream().noneMatch(o -> o.value().equals(item.textValue()))) {
                return false;
            }
        }
        return true;
    }

    public String encode(Object value, int limit, String label) {
        try {
            String json = mapper.writeValueAsString(value);
            if (json.getBytes(StandardCharsets.UTF_8).length > limit) {
                throw invalid(label + "最多256KiB");
            }
            return json;
        } catch (com.fasterxml.jackson.core.JsonProcessingException failure) {
            throw invalid(label + "格式不正确");
        }
    }

    public Schema decode(String json) {
        try {
            return mapper.readValue(json, Schema.class);
        } catch (com.fasterxml.jackson.core.JsonProcessingException failure) {
            throw invalid("表单配置无法读取");
        }
    }

    public JsonNode data(String json) {
        try {
            return mapper.readTree(json);
        } catch (com.fasterxml.jackson.core.JsonProcessingException failure) {
            throw invalid("记录无法读取");
        }
    }

    public static int limit(Field field) {
        return field.maxLength() == null ? (field.type().equals("textarea") ? 5000 : 200) : field.maxLength();
    }

    public static ServiceException invalid(String message) {
        return new ServiceException(message);
    }
}
