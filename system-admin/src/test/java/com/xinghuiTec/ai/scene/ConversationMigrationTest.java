package com.xinghuiTec.ai.scene;

import com.xinghuiTec.ai.application.AiRequestStore;
import com.xinghuiTec.ai.conversation.*;
import org.junit.jupiter.api.Test;
import java.time.Instant;
import java.util.UUID;
import static org.junit.jupiter.api.Assertions.*;

class ConversationMigrationTest {
    @Test void historicalRequestBecomesMetadataOnlyAndNeverManufacturesAnAnswer() throws Exception {
        try (var f = new SceneTestSupport()) {
            var scene = f.save(f.create(false), "Answer", false);
            var accepted = f.service.submit(scene.detail().scene().sceneKey(), f.input(false, UUID.randomUUID().toString()), f.actor);
            f.terminal(accepted.requestId());
            f.script(f.read("V20261018_240__conversation_history.sql"));
            var store = new ConversationStore(f.jdbc, f.requests, f.manager);
            var service = new ConversationService(store, f.service, f.runtime);
            var page = service.page(f.actor, false, "OWN", null, null, null, 1, 20);
            assertEquals("1", page.total());
            var detail = service.detail(Long.parseLong(page.records().get(0).conversationId()), f.actor, false, "OWN");
            assertEquals(ConversationDtos.History.LEGACY_NO_BODY, detail.conversation().history());
            assertEquals("旧记录未保留对话", detail.notice());
            assertTrue(detail.messages().isEmpty());
            assertFalse(detail.conversation().canContinue());
            assertEquals(1, f.provider.calls.get());
        }
    }

    @Test void fullDatabaseBusinessTokenSurvivesTheMigratedRequestColumnWithoutTruncation() throws Exception {
        try (var f = new SceneTestSupport()) {
            f.script(f.read("V20261018_240__conversation_history.sql"));
            var scene = f.save(f.create(false), "Answer", false);
            var head = f.scenes.head(scene.detail().scene().sceneKey(), false);
            var version = f.scenes.version(head.currentVersion());
            String token = "db1." + "a".repeat(2044);
            var admission = new AiRequestStore.Admission(Long.parseLong(head.appId()), Long.parseLong(version.appVersion()), null,
                    AiRequestStore.Kind.BUSINESS, new AiRequestStore.Owner("101", "tenant_a"), "database.record", token,
                    UUID.randomUUID().toString(), "a".repeat(64), Instant.now().plusSeconds(30), head.key(), version.id());
            var stored = f.requests.accept(admission, Instant.now()).request();
            assertEquals(token, f.requests.owned(Long.parseLong(stored.requestId()), admission.owner()).businessId());
            assertEquals(2048, stored.businessId().length());
            assertEquals(0, f.provider.calls.get());
        }
    }
}
