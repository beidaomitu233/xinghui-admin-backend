package com.xinghuiTec.ai.prompt;

import com.xinghuiTec.exception.ServiceException;
import org.springframework.stereotype.Component;

import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.regex.Matcher;

/** Validates a scene's small, declarative placeholder language. No expressions or code are evaluated. */
@Component
public class AiPromptSceneRules {
    public static final int MAX_PROMPT_LENGTH = 12_000;
    public static final int MAX_VARIABLES = 64;
    private static final int MAX_EXAMPLE_LENGTH = 512;
    private static final int MAX_PREVIEW_VALUE_LENGTH = 2_048;
    static final java.util.regex.Pattern PLACEHOLDER =
            java.util.regex.Pattern.compile("\\{\\{([A-Za-z][A-Za-z0-9_]*)\\}\\}");
    private static final java.util.regex.Pattern VARIABLE_NAME =
            java.util.regex.Pattern.compile("[A-Za-z][A-Za-z0-9_]{0,63}");
    private static final java.util.regex.Pattern APP_KEY =
            java.util.regex.Pattern.compile("[a-z][a-z0-9._-]{0,63}");
    private static final java.util.regex.Pattern SCENE_KEY =
            java.util.regex.Pattern.compile("[a-z][a-z0-9._-]{0,95}");

    public enum VariableType {
        STRING,
        NUMBER,
        BOOLEAN
    }

    public record VariableDefinition(
            String name,
            VariableType type,
            String description,
            Boolean sensitive,
            String exampleValue) {}

    public String appKey(String value) {
        if (value == null || !APP_KEY.matcher(value).matches()) {
            throw new ServiceException("应用标识须为小写字母开头的 1～64 位字母、数字、点、下划线或连字符");
        }
        return value;
    }

    public String sceneKey(String value) {
        if (value == null || !SCENE_KEY.matcher(value).matches()) {
            throw new ServiceException("场景标识须为小写字母开头的 1～96 位字母、数字、点、下划线或连字符");
        }
        return value;
    }

    public String prompt(String value) {
        if (value == null || value.isBlank()) {
            throw new ServiceException("提示词草稿不能为空");
        }
        if (value.length() > MAX_PROMPT_LENGTH) {
            throw new ServiceException("提示词正文不能超过 12000 个字符");
        }
        return value;
    }

    public List<VariableDefinition> definitions(List<VariableDefinition> requested) {
        if (requested == null) {
            throw new ServiceException("变量声明须使用数组；无变量场景请传入空数组");
        }
        if (requested.size() > MAX_VARIABLES) {
            throw new ServiceException("单个场景最多声明 64 个变量");
        }

        List<VariableDefinition> definitions = new ArrayList<>(requested.size());
        Set<String> names = new HashSet<>();
        for (VariableDefinition definition : requested) {
            if (definition == null || definition.name() == null
                    || !VARIABLE_NAME.matcher(definition.name()).matches()) {
                throw new ServiceException("变量名称格式无效");
            }
            if (!names.add(definition.name())) {
                throw new ServiceException("变量名称不能重复");
            }
            if (definition.type() == null) {
                throw new ServiceException("变量类型不能为空");
            }
            if (definition.description() == null || definition.description().isBlank()
                    || definition.description().length() > 160) {
                throw new ServiceException("变量说明须为 1～160 个字符");
            }
            if (definition.sensitive() == null) {
                throw new ServiceException("变量敏感标记不能为空");
            }
            if (definition.exampleValue() == null || definition.exampleValue().isBlank()
                    || definition.exampleValue().length() > MAX_EXAMPLE_LENGTH) {
                throw new ServiceException("每个变量都须提供不超过 512 个字符的中性示例值");
            }
            validateTypedValue(definition.type(), definition.exampleValue(), "中性示例值");
            definitions.add(definition);
        }
        return List.copyOf(definitions);
    }

    public void validateTemplate(String requestedPrompt, List<VariableDefinition> requestedDefinitions) {
        String prompt = prompt(requestedPrompt);
        List<VariableDefinition> definitions = definitions(requestedDefinitions);
        Set<String> declared = new HashSet<>();
        definitions.forEach(definition -> declared.add(definition.name()));

        Matcher matcher = PLACEHOLDER.matcher(prompt);
        Set<String> referenced = new HashSet<>();
        int validPlaceholders = 0;
        while (matcher.find()) {
            validPlaceholders++;
            String name = matcher.group(1);
            if (!declared.contains(name)) {
                throw new ServiceException("提示词引用了未在当前场景声明的变量");
            }
            referenced.add(name);
        }
        if (countOccurrences(prompt, "{{") != validPlaceholders
                || countOccurrences(prompt, "}}") != validPlaceholders) {
            throw new ServiceException("变量占位符格式无效；请使用 {{variableName}}");
        }
        // Keep the set operation explicit: declarations are scoped to this single scene only.
        if (!declared.containsAll(referenced)) {
            throw new ServiceException("提示词包含未声明变量");
        }
    }

    public String render(String requestedPrompt, List<VariableDefinition> requestedDefinitions,
                         Map<String, String> requestedValues) {
        String prompt = prompt(requestedPrompt);
        List<VariableDefinition> definitions = definitions(requestedDefinitions);
        validateTemplate(prompt, definitions);

        Map<String, String> values = new LinkedHashMap<>();
        Map<String, VariableDefinition> byName = new HashMap<>();
        definitions.forEach(definition -> byName.put(definition.name(), definition));
        Map<String, String> inputs = requestedValues == null ? Map.of() : requestedValues;
        if (inputs.size() > MAX_VARIABLES) {
            throw new ServiceException("预览值数量超过场景变量上限");
        }
        for (String providedName : inputs.keySet()) {
            if (!byName.containsKey(providedName)) {
                throw new ServiceException("预览值只能对应当前场景已声明的变量");
            }
        }
        for (VariableDefinition definition : definitions) {
            String value = inputs.containsKey(definition.name())
                    ? inputs.get(definition.name()) : definition.exampleValue();
            if (value == null || value.isBlank() || value.length() > MAX_PREVIEW_VALUE_LENGTH) {
                throw new ServiceException("变量预览值不能为空且不能超过 2048 个字符");
            }
            validateTypedValue(definition.type(), value, "变量预览值");
            values.put(definition.name(), Boolean.TRUE.equals(definition.sensitive()) ? "[敏感变量已隐藏]" : value);
        }

        Matcher matcher = PLACEHOLDER.matcher(prompt);
        StringBuffer rendered = new StringBuffer();
        while (matcher.find()) {
            matcher.appendReplacement(rendered,
                    java.util.regex.Matcher.quoteReplacement(values.get(matcher.group(1))));
        }
        matcher.appendTail(rendered);
        return rendered.toString();
    }

    void validateTypedValue(VariableType type, String value, String label) {
        try {
            switch (type) {
                case STRING -> { }
                case NUMBER -> new BigDecimal(value);
                case BOOLEAN -> {
                    if (!"true".equalsIgnoreCase(value) && !"false".equalsIgnoreCase(value)) {
                        throw new IllegalArgumentException();
                    }
                }
            }
        } catch (RuntimeException invalid) {
            throw new ServiceException(label + "与声明的变量类型不匹配");
        }
    }

    private int countOccurrences(String value, String token) {
        int count = 0;
        int index = 0;
        while ((index = value.indexOf(token, index)) >= 0) {
            count++;
            index += token.length();
        }
        return count;
    }
}
