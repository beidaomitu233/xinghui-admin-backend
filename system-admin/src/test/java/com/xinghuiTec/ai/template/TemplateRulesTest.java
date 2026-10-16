package com.xinghuiTec.ai.template;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.xinghuiTec.exception.ServiceException;
import org.junit.jupiter.api.Test;
import java.util.*;
import static com.xinghuiTec.ai.template.TemplateDefinition.*;
import static org.junit.jupiter.api.Assertions.*;

class TemplateRulesTest {
    private final ObjectMapper mapper = new ObjectMapper();
    private Variable string(String name, boolean required, boolean sensitive, boolean truncate, int max) {
        return new Variable(name, Type.STRING, required, false, null, max, sensitive, truncate);
    }
    private TemplateDefinition template(List<Variable> vars, List<Message> messages, List<Binding> resources) {
        return new TemplateDefinition("中性模板", "定向规则测试", "业务负责人", messages, vars, resources, 8000);
    }
    @Test void substitutesOnceAndPreservesLiteralReplacementCharacters() {
        var d = template(List.of(string("input", true, false, false, 100)), List.of(new Message(Section.INPUT, "用户={{input}}")), List.of());
        var render = TemplateRules.render(d, Map.of("input", mapper.valueToTree("{{input}} $1 \\")), Map.of(), false);
        assertEquals("用户={{input}} $1 \\", render.messages().get(0).text());
        assertFalse(render.toString().contains("用户="));
    }
    @Test void rejectsPromptInjectionIntoInstructionSectionAndResourceSpoofing() {
        var v = string("input", true, true, false, 100);
        assertThrows(ServiceException.class, () -> TemplateRules.validate(template(List.of(v), List.of(new Message(Section.INSTRUCTION, "{{input}}")), List.of())));
        var binding = new Binding("record", "1", "2", true, false, Map.of("formId", new Parameter(ParameterSource.BUSINESS_REF, "formId"), "recordId", new Parameter(ParameterSource.BUSINESS_REF, "recordId")), Map.of("title", "input"));
        var d = template(List.of(v), List.of(new Message(Section.DATA, "{{input}}")), List.of(binding));
        assertThrows(ServiceException.class, () -> TemplateRules.render(d, Map.of("input", mapper.valueToTree("spoof")), Map.of(), false));
        assertEquals("[已脱敏]", TemplateRules.render(d, Map.of(), Map.of("input", mapper.valueToTree("secret")), true).messages().get(0).text());
    }
    @Test void defaultsAreExplicitAndTypesAreStrict() {
        var v = new Variable("count", Type.NUMBER, true, true, mapper.valueToTree(12), 20, false, false);
        var d = template(List.of(v), List.of(new Message(Section.INPUT, "{{count}}")), List.of());
        assertEquals("12", TemplateRules.render(d, Map.of(), Map.of(), false).messages().get(0).text());
        assertEquals("DEFAULT_USED", TemplateRules.render(d, Map.of(), Map.of(), false).notices().get(0).kind());
        assertThrows(ServiceException.class, () -> TemplateRules.render(d, Map.of("count", mapper.valueToTree("12")), Map.of(), false));
    }
    @Test void truncationRequiresPolicyAndReturnsMetadata() {
        var d = template(List.of(string("input", true, false, true, 3)), List.of(new Message(Section.INPUT, "{{input}}")), List.of());
        var rendered = TemplateRules.render(d, Map.of("input", mapper.valueToTree("abcdef")), Map.of(), false);
        assertEquals("abc", rendered.messages().get(0).text()); assertEquals("TRUNCATED", rendered.notices().get(0).kind());
        var strict = template(List.of(string("input", true, false, false, 3)), d.messages(), List.of());
        assertThrows(ServiceException.class, () -> TemplateRules.render(strict, Map.of("input", mapper.valueToTree("abcd")), Map.of(), false));
    }
    @Test void missingAndUnknownVariablesFailWithoutFilling() {
        var d = template(List.of(string("input", true, false, false, 100)), List.of(new Message(Section.INPUT, "{{input}}")), List.of());
        assertThrows(ServiceException.class, () -> TemplateRules.render(d, Map.of(), Map.of(), false));
        assertThrows(ServiceException.class, () -> TemplateRules.render(d, Map.of("input", mapper.valueToTree("ok"), "tenantId", mapper.valueToTree("spoof")), Map.of(), false));
    }
    @Test void validatesCodeRegisteredSourceAndExactDecimalIds() {
        assertTrue(TemplateRules.id("9223372036854775807")); assertFalse(TemplateRules.id("9223372036854775808"));
        assertThrows(ServiceException.class, () -> TemplateRules.validate(new ResourceDefinition("资源", "业务", "负责人", "https://attacker", "1", List.of("title"), Set.of("1"), Set.of("2"), 1000)));
        assertEquals(Map.of("formId", "9007199254740993", "recordId", "9223372036854775807"), TemplateDataService.business("9007199254740993:9223372036854775807"));
    }
}
