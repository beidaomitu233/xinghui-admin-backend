package com.xinghuiTec.ai.prompt;

import com.xinghuiTec.ai.prompt.AiPromptSceneRules.VariableDefinition;
import com.xinghuiTec.ai.prompt.AiPromptSceneRules.VariableType;
import com.xinghuiTec.exception.ServiceException;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

class AiPromptSceneRulesTest {
    private final AiPromptSceneRules rules = new AiPromptSceneRules();

    @Test
    void rendersOnlyDeclaredVariablesWithoutEvaluatingExpressionText() {
        String rendered = rules.render("Name={{recordName}}; flag={{active}}; literal=$1",
                List.of(string("recordName", "Example"),
                        new VariableDefinition("active", VariableType.BOOLEAN, "Neutral flag", false, "true")),
                Map.of("recordName", "$1\\private", "active", "false"));

        assertEquals("Name=$1\\private; flag=false; literal=$1", rendered);
    }

    @Test
    void rejectsUndeclaredOrMalformedPlaceholdersEvenIfAnotherSceneCouldDeclareThem() {
        var definitions = List.of(string("recordName", "Example"));
        ServiceException crossScene = assertThrows(ServiceException.class,
                () -> rules.validateTemplate("{{otherSceneValue}}", definitions));
        assertTrue(crossScene.getMessage().contains("未在当前场景声明"));
        assertThrows(ServiceException.class, () -> rules.validateTemplate("{{recordName", definitions));
        assertThrows(ServiceException.class, () -> rules.validateTemplate("recordName}}", definitions));
    }

    @Test
    void rejectsDuplicateNamesBadTypesMissingExamplesAndOverlongPrompts() {
        assertThrows(ServiceException.class, () -> rules.definitions(List.of(
                string("recordName", "One"), string("recordName", "Two"))));
        assertThrows(ServiceException.class, () -> rules.definitions(List.of(
                new VariableDefinition("amount", VariableType.NUMBER, "Amount", false, "many"))));
        assertThrows(ServiceException.class, () -> rules.definitions(List.of(
                new VariableDefinition("recordName", VariableType.STRING, "Name", false, " "))));
        assertThrows(ServiceException.class, () -> rules.prompt("x".repeat(AiPromptSceneRules.MAX_PROMPT_LENGTH + 1)));
    }

    @Test
    void rejectsCrossTypeOrUndeclaredPreviewValuesAndAcceptsScenesWithoutVariables() {
        var number = new VariableDefinition("amount", VariableType.NUMBER, "Neutral amount", false, "12.5");
        assertThrows(ServiceException.class,
                () -> rules.render("{{amount}}", List.of(number), Map.of("amount", "twelve")));
        assertThrows(ServiceException.class,
                () -> rules.render("{{amount}}", List.of(number), Map.of("other", "12")));
        assertEquals("No variables in this scene", rules.render("No variables in this scene", List.of(), Map.of()));
    }

    @Test
    void sensitivePreviewValuesAreRedactedFromRenderedOutput() {
        var sensitive = new VariableDefinition("accountLabel", VariableType.STRING,
                "Sensitive neutral sample", true, "neutral-sensitive-sample");

        String rendered = rules.render("Account={{accountLabel}}", List.of(sensitive),
                Map.of("accountLabel", "entered-sensitive-preview"));

        assertEquals("Account=[敏感变量已隐藏]", rendered);
        assertFalse(rendered.contains("entered-sensitive-preview"));
        assertFalse(rendered.contains("neutral-sensitive-sample"));
    }

    private VariableDefinition string(String name, String example) {
        return new VariableDefinition(name, VariableType.STRING, "Neutral example", false, example);
    }
}
