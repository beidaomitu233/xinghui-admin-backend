package com.xinghuiTec.notification;

import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

class NotificationContentRulesTest {
    private final NotificationContentRules rules = new NotificationContentRules("trusted.example");
    @Test void acceptsBoundedSnapshotAndApprovedTargets() {
        assertDoesNotThrow(() -> rules.validate("标题", "正文\n第二行", "/system/user?name=test"));
        assertDoesNotThrow(() -> rules.validate("标题", "正文", "https://trusted.example/path"));
        assertDoesNotThrow(() -> rules.validate("标题", "正文", null));
    }
    @Test void rejectsOversizeEmptyAndControlCharacters() {
        for (String title : new String[]{"", " ", "x".repeat(201), "a\nb"})
            assertThrows(NotificationContractException.class, () -> rules.validate(title, "body", null));
        assertThrows(NotificationContractException.class, () -> rules.validate("title", "x".repeat(4001), null));
        assertThrows(NotificationContractException.class, () -> rules.validate("title", "a\u0000b", null));
    }
    @Test void rejectsUnsafeOrUnapprovedTargets() {
        for (String target : new String[]{"//evil.example", "javascript:alert(1)", "http://trusted.example", "https://evil.example", "https://user@trusted.example", "/../private", "/%2e%2e/private", "/%5cevil", "/bad%00", "/bad\\path", "/bad%"})
            assertThrows(NotificationContractException.class, () -> rules.validate("title", "body", target), target);
    }
}
