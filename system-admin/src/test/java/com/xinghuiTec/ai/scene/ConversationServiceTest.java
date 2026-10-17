package com.xinghuiTec.ai.scene;

import com.xinghuiTec.ai.application.*;
import com.xinghuiTec.ai.conversation.*;
import com.xinghuiTec.ai.contract.AiTypes.*;
import com.xinghuiTec.ai.contract.AiProviderRuntime.ExecutionStatus;
import org.junit.jupiter.api.*;
import java.time.Instant;
import java.util.*;
import java.util.concurrent.*;
import static org.junit.jupiter.api.Assertions.*;

/** Actual H2 persistence/current scene compiler/worker, with an explicitly simulated model provider. */
class ConversationServiceTest {
    private SceneTestSupport f;
    private ConversationStore store;
    private ConversationService service;
    private String scene;

    @BeforeEach void setup() throws Exception {
        f = new SceneTestSupport();
        f.script(f.read("V20261018_240__conversation_history.sql"));
        wire();
        var initial = f.create(false);
        var definition = f.definition("Reply to {{user_request}}", false);
        var policy = definition.modelPolicy();
        var single = new SceneDtos.ModelPolicy(policy.mode(), policy.models(), policy.fallback(), policy.outputFormat(),
                policy.timeoutSeconds(), 1, policy.maxConcurrent(), policy.maxTokens());
        scene = f.management.save(initial.scene().sceneKey(), new SceneDtos.Save(0, UUID.randomUUID().toString(),
                new SceneDtos.Definition("Chat", "", definition.promptText(), "", List.of(), single)), f.actor).detail().scene().sceneKey();
    }

    private void wire() {
        store = new ConversationStore(f.jdbc, f.requests, f.manager);
        f.runtime.conversations(store);
        f.lifecycle.conversations(store);
        f.service.conversations(store);
        service = new ConversationService(store, f.service, f.runtime);
    }

    @AfterEach void close() {
        f.provider.release.countDown();
        f.close();
    }

    private ConversationDtos.Start start(String message) {
        return new ConversationDtos.Start(scene, ConversationDtos.Kind.BUSINESS, null, message,
                UUID.randomUUID().toString(), null);
    }

    @Test void invokerCanListOnlyOwnConversationsWithoutTechnicalLogPermission() throws Exception {
        var accepted = service.start(start("my own conversation"), f.actor, false);
        finished(accepted);
        var invoker = new TrustedActor(f.actor.subjectId(), f.actor.tenantId(), Set.of("ai:scene:invoke"), null);
        var page = service.page(invoker, false, "OWN", null, null, null, 1, 20);
        assertEquals("1", page.total());
        assertEquals(accepted.conversationId(), page.records().get(0).conversationId());
        var other = new TrustedActor("999", f.actor.tenantId(), Set.of("ai:scene:invoke"), null);
        assertEquals("0", service.page(other, false, "OWN", null, null, null, 1, 20).total());
        var forbidden = assertThrows(ConversationFailure.class,
                () -> service.page(invoker, true, "AUDIT", null, null, null, 1, 20));
        assertEquals(403, forbidden.status());
    }

    private ConversationDtos.Detail finished(ConversationDtos.Accepted accepted) throws Exception {
        Instant until = Instant.now().plusSeconds(5);
        while (!f.runtime.status(Long.parseLong(accepted.requestId()), f.actor).status().terminal()) {
            if (Instant.now().isAfter(until)) fail("simulated worker timeout");
            Thread.sleep(5);
        }
        return service.detail(Long.parseLong(accepted.conversationId()), f.actor, false, "OWN");
    }

    @Test void twoRoundsUsePersistedSuccessfulContextAndBothMutationRetriesReturnOriginalRequests() throws Exception {
        var body = start("first question");
        var first = service.start(body, f.actor, false);
        var detail = finished(first);
        assertEquals(2, detail.messages().size());
        assertEquals("first question", detail.messages().get(0).content());
        assertEquals("controlled fixture result", detail.messages().get(1).content());
        assertEquals(AiRequestStatus.SUCCEEDED, detail.messages().get(1).status());
        assertEquals(first.requestId(), service.start(body, f.actor, false).requestId());
        var followup = new ConversationDtos.Continue(detail.conversation().revision(), "follow up",
                UUID.randomUUID().toString(), null);
        var second = service.continueConversation(Long.parseLong(first.conversationId()), followup, f.actor, false);
        var completed = finished(second);
        assertEquals(4, completed.messages().size());
        assertEquals(second.requestId(), service.continueConversation(Long.parseLong(first.conversationId()), followup, f.actor, false).requestId());
        var messages = f.provider.observed.get(1).messages();
        assertTrue(messages.stream().anyMatch(m -> m.role() == MessageRole.USER && m.content().equals("first question")));
        assertTrue(messages.stream().anyMatch(m -> m.role() == MessageRole.ASSISTANT && m.content().equals("controlled fixture result")));
        var reloaded = new ConversationService(new ConversationStore(f.jdbc, f.requests, f.manager), f.service, f.runtime);
        assertEquals(4, reloaded.detail(Long.parseLong(first.conversationId()), f.actor, false, "OWN").messages().size());
        assertEquals(2, f.provider.calls.get());
        assertEquals(2, f.jdbc.queryForObject("SELECT COUNT(*) FROM sys_ai_request", Integer.class));
    }

    @Test void concurrentNewTurnRollsBackAdmissionWhileFirstRoundIsRunning() throws Exception {
        f.provider.entered = new CountDownLatch(1);
        f.provider.release = new CountDownLatch(1);
        var first = service.start(start("held"), f.actor, false);
        assertTrue(f.provider.entered.await(2, TimeUnit.SECONDS));
        var error = assertThrows(ConversationFailure.class, () -> service.continueConversation(
                Long.parseLong(first.conversationId()), new ConversationDtos.Continue(first.revision(), "parallel",
                        UUID.randomUUID().toString(), null), f.actor, false));
        assertEquals("CONVERSATION_BUSY", error.getMessage());
        assertEquals(first.requestId(), error.details().activeRequestId());
        assertEquals(1, f.jdbc.queryForObject("SELECT COUNT(*) FROM sys_ai_request", Integer.class));
        assertEquals(2, f.jdbc.queryForObject("SELECT COUNT(*) FROM sys_ai_conversation_message", Integer.class));
        f.provider.release.countDown();
        finished(first);
    }

    @Test void partialFailurePersistsTruthButIsExcludedFromLaterSuccessfulContext() throws Exception {
        f.provider.resultStatus = ExecutionStatus.INCOMPLETE;
        f.provider.resultError = ErrorCode.NETWORK_ERROR;
        f.provider.resultText = "partial response";
        var body = start("failed question");
        var first = service.start(body, f.actor, false);
        var detail = finished(first);
        assertEquals(AiRequestStatus.INCOMPLETE, detail.messages().get(1).status());
        assertTrue(detail.messages().get(1).partial());
        assertEquals("partial response", detail.messages().get(1).content());
        assertEquals(first.requestId(), service.start(body, f.actor, false).requestId());
        f.provider.resultStatus = ExecutionStatus.SUCCEEDED;
        f.provider.resultError = null;
        f.provider.resultText = "complete response";
        var second = service.continueConversation(Long.parseLong(first.conversationId()), new ConversationDtos.Continue(
                detail.conversation().revision(), "new question", UUID.randomUUID().toString(), null), f.actor, false);
        finished(second);
        assertFalse(f.provider.observed.get(1).messages().stream().anyMatch(m -> m.content().contains("partial response") || m.content().contains("failed question")));
        assertEquals(2, f.provider.calls.get());
    }

    @Test void ownerTenantAuditAndCapacityRulesCannotBeBypassedByConversationIds() throws Exception {
        var first = service.start(start("private body"), f.actor, false);
        var detail = finished(first);
        long id = Long.parseLong(first.conversationId());
        var other = new TrustedActor("102", "tenant_b", f.actor.authorities(), null);
        assertEquals(404, assertThrows(ConversationFailure.class, () -> service.detail(id, other, false, "OWN")).status());
        var auditor = new TrustedActor("900", "000000", Set.of("ai:request:audit", "ai:request:query", "ai:request:list", "ai:scene:invoke"), null);
        assertEquals(2, service.detail(id, auditor, true, "AUDIT").messages().size());
        assertFalse(service.detail(id, auditor, true, "AUDIT").conversation().canContinue());
        assertEquals(404, assertThrows(ConversationFailure.class, () -> service.continueConversation(id,
                new ConversationDtos.Continue(detail.conversation().revision(), "impersonate", UUID.randomUUID().toString(), null), auditor, true)).status());
        f.jdbc.update("UPDATE sys_ai_conversation SET last_sequence=400 WHERE conversation_id=?", id);
        assertEquals("CONVERSATION_FULL", assertThrows(ConversationFailure.class, () -> service.continueConversation(id,
                new ConversationDtos.Continue(detail.conversation().revision(), "more", UUID.randomUUID().toString(), null), f.actor, false)).getMessage());
        assertEquals(1, f.jdbc.queryForObject("SELECT COUNT(*) FROM sys_ai_request", Integer.class));
        assertEquals(1, f.provider.calls.get());
    }

    @Test void replayAndStreamingRecheckTheDataScopeOfIncludedHistoryEvenAfterPromptStopsUsingThatRecord() throws Exception {
        var created = f.create(false);
        var recordScene = f.save(created, "Use {{record_subject}} {{user_request}}", true);
        var first = service.start(new ConversationDtos.Start(recordScene.detail().scene().sceneKey(), ConversationDtos.Kind.BUSINESS,
                new SceneDtos.BusinessRef("lowcode.record", "1:11"), "original question", UUID.randomUUID().toString(), null), f.actor, false);
        var history = finished(first);
        f.save(recordScene.detail(), "Reply {{user_request}}", false);
        var followup = new ConversationDtos.Continue(history.conversation().revision(), "followup", UUID.randomUUID().toString(), null);
        var second = service.continueConversation(Long.parseLong(first.conversationId()), followup, f.actor, false);
        finished(second);
        f.jdbc.update("UPDATE sys_lowcode_record SET is_deleted=1 WHERE id=11");
        var request = f.runtime.status(Long.parseLong(second.requestId()), f.actor);
        assertEquals(ErrorCode.RESOURCE_FORBIDDEN, assertThrows(com.xinghuiTec.ai.contract.AiRuntimeException.class,
                () -> f.runtime.authorizeEvents(request, f.actor)).code());
        assertEquals(ErrorCode.RESOURCE_FORBIDDEN, assertThrows(com.xinghuiTec.ai.contract.AiRuntimeException.class,
                () -> service.continueConversation(Long.parseLong(first.conversationId()), followup, f.actor, false)).code());
        assertEquals(2, f.provider.calls.get());
    }
}
