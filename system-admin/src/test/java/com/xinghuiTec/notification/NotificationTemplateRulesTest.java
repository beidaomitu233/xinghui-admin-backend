package com.xinghuiTec.notification;

import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.*;

class NotificationTemplateRulesTest {
    private final NotificationTemplateRules rules = new NotificationTemplateRules(Set.of("trusted.example"));

    @Test
    void twoIndependentEventsUseTheSameContractWithoutBusinessBranches() {
        var created = rules.render("记录 {name} 已创建", "编号 {number}", "/records/{number}",
                List.of("name", "number"), Map.of("name", "示例", "number", "A/12"));
        assertEquals("记录 示例 已创建", created.title());
        assertEquals("/records/A%2F12", created.target());

        var reviewed = rules.render("审核 {state}", "请查看 {item}",
                "https://trusted.example/reviews/{item}", List.of("state", "item"),
                Map.of("state", "待处理", "item", "R-42"));
        assertEquals("审核 待处理", reviewed.title());
        assertEquals("https://trusted.example/reviews/R-42", reviewed.target());
    }

    @Test
    void rejectsMissingUnknownAndUndeclaredVariables() {
        assertCode("VARIABLE_MISSING", () -> rules.render("{name}", "正文", null,
                List.of("name"), Map.of()));
        assertCode("VARIABLE_UNKNOWN", () -> rules.render("{name}", "正文", null,
                List.of("name"), Map.of("name", "ok", "secret", "value")));
        assertCode("VARIABLE_UNDECLARED", () -> rules.validate("{secret}", "正文", null,
                List.of("name")));
        assertCode("VARIABLE_DECLARATION", () -> rules.validate("{bad-name}", "正文", null,
                List.of("bad-name")));
    }

    @Test
    void restrictsTargetsAndRenderedLengths() {
        assertCode("TARGET_INVALID", () -> rules.validate("标题", "正文", "//evil.example/path", List.of()));
        assertCode("TARGET_INVALID", () -> rules.validate("标题", "正文", "/a/../admin", List.of()));
        assertCode("TARGET_INVALID", () -> rules.validate("标题", "正文",
                "https://evil.example/path", List.of()));
        assertCode("TARGET_INVALID", () -> rules.validate("标题", "正文",
                "https://{host}/path", List.of("host")));
        assertCode("CONTENT_LENGTH", () -> rules.render("{text}", "正文", null,
                List.of("text"), Map.of("text", "x".repeat(201))));
    }

    private static void assertCode(String code, Runnable action) {
        NotificationContractException error = assertThrows(NotificationContractException.class, action::run);
        assertEquals(code, error.code());
    }
}
