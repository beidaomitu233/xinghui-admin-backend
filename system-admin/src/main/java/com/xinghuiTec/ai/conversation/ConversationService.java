package com.xinghuiTec.ai.conversation;

import com.xinghuiTec.ai.application.*;
import com.xinghuiTec.ai.contract.AiTypes.TrustedActor;
import com.xinghuiTec.ai.scene.*;
import org.springframework.stereotype.Service;
import java.util.*;
import static com.xinghuiTec.ai.conversation.ConversationFailure.require;

@Service
public class ConversationService {
    private final ConversationStore store;
    private final SceneRuntimeService scenes;
    private final AiRuntimeService runtime;
    public ConversationService(ConversationStore store, SceneRuntimeService scenes, AiRuntimeService runtime) {
        this.store = store;
        this.scenes = scenes;
        this.runtime = runtime;
    }
    public ConversationDtos.Accepted start(ConversationDtos.Start input, TrustedActor actor, boolean platform) {
        caller(actor);
        require(input != null, 400, "INVALID_INPUT");
        message(input.message(), input.clientMutationId());
        ConversationDtos.Kind kind = input.kind() == null ? ConversationDtos.Kind.BUSINESS : input.kind();
        testAccess(kind, input.test(), actor, platform);
        return dispatch(input.sceneKey(), kind, input.test(), new SceneDtos.Submission(input.businessRef(),
                Map.of("user_request", input.message()), input.clientMutationId()), actor);
    }
    public ConversationDtos.Accepted continueConversation(long id, ConversationDtos.Continue input, TrustedActor actor,
            boolean platform) {
        caller(actor);
        require(input != null && input.expectedRevision() >= 0, 400, "INVALID_INPUT");
        message(input.message(), input.clientMutationId());
        store.reconcile();
        var h = store.head(id, false);
        owner(h, actor);
        require(h.sceneKey() != null && "COMPLETE".equals(h.history()), 409, "LEGACY_NO_BODY");
        var kind = ConversationDtos.Kind.valueOf(h.kind());
        testAccess(kind, input.test(), actor, platform);
        // Revision/busy checks occur atomically with a new request insert; replay wins
        // before those checks.
        return dispatch(h.sceneKey(), kind, input.test(),
                new SceneDtos.Submission(new SceneDtos.BusinessRef(h.businessType(), h.businessId()),
                        Map.of("user_request", input.message()), input.clientMutationId(), h.id(),
                        input.expectedRevision()),
                actor);
    }
    private ConversationDtos.Accepted dispatch(String sceneKey, ConversationDtos.Kind kind,
            ConversationDtos.TestSelection test, SceneDtos.Submission submission, TrustedActor actor) {
        SceneDtos.Request request;
        try {
            request = kind == ConversationDtos.Kind.TEST
                    ? scenes.test(sceneKey,
                            new SceneDtos.TestInput(test.expectedSceneRevision(), test.modelIndex(),
                                    test.confirmedCosts(), submission),
                            actor)
                    : scenes.submit(sceneKey, submission, actor);
        } catch (AiRuntimeService.AdmissionFailure failure) {
            request = scenes.status(Long.parseLong(failure.requestId()), actor);
        }
        var link = store.link(Long.parseLong(request.requestId()));
        require(link != null, 503, "CONVERSATION_UNAVAILABLE");
        return new ConversationDtos.Accepted(link.conversationId(), link.revision(), request.requestId(),
                request.sceneVersionId(), link.userMessageId(), link.assistantMessageId(),
                AiRequestStatus.valueOf(request.status()), link.contextTruncated(), link.contextStartMessageId());
    }
    public ConversationDtos.Page page(TrustedActor actor, boolean platform, String scope, String sceneKey, String kind,
            String search, int page, int size) {
        return page(actor,platform,scope,sceneKey,kind,search,page,size,null,null);
    }
    public ConversationDtos.Page page(TrustedActor actor, boolean platform, String scope, String sceneKey, String kind,
            String search, int page, int size,String orderBy,String order) {
        boolean audit = audit(scope, actor, platform);
        require(actor.authorities().contains("ai:request:list")
                || (!audit && actor.authorities().contains("ai:scene:invoke")), 403, "FORBIDDEN");
        store.reconcile();
        var owner = new AiRequestStore.Owner(actor.subjectId(), actor.tenantId());
        var rows = store.page(owner, audit, sceneKey, kind, search, page, size,orderBy,order);
        // OWN must not leak a last-message preview after the underlying business access
        // has been revoked.
        var visible = rows.stream().filter(h -> audit || visible(h, actor)).map(h -> summary(h, actor, platform, audit))
                .toList();
        long total = store.count(owner, audit, sceneKey, kind, search);
        return new ConversationDtos.Page(visible, Long.toString(total), page, size);
    }
    public ConversationDtos.Detail detail(long id, TrustedActor actor, boolean platform, String scope) {
        require(actor.authorities().contains("ai:scene:invoke") || actor.authorities().contains("ai:request:query"),
                403, "FORBIDDEN");
        boolean audit = audit(scope, actor, platform);
        if (audit)
            require(actor.authorities().contains("ai:request:query"), 403, "FORBIDDEN");
        store.reconcile();
        var h = store.head(id, false);
        if (!audit) {
            owner(h, actor);
            authorize(h, actor);
        }
        return new ConversationDtos.Detail(summary(h, actor, platform, audit), store.messages(id),
                "LEGACY_NO_BODY".equals(h.history()) ? "旧记录未保留对话" : null);
    }
    private ConversationDtos.Summary summary(ConversationStore.Head h, TrustedActor actor, boolean platform,
            boolean audit) {
        boolean canContinue = !audit && h.sceneKey() != null && h.activeRequest() == null
                && "COMPLETE".equals(h.history()) && actor.authorities().contains("ai:scene:invoke")
                && (!"TEST".equals(h.kind()) || (platform && actor.authorities().contains("ai:scene:test")));
        return new ConversationDtos.Summary(h.id(), h.title(), h.sceneKey(), h.sceneName(),
                ConversationDtos.Kind.valueOf(h.kind()), new SceneDtos.BusinessRef(h.businessType(), h.businessId()),
                h.source(), h.revision(), AiRequestStatus.valueOf(h.status()), h.lastMessage(), h.updated(),
                ConversationDtos.History.valueOf(h.history()), canContinue);
    }
    private boolean visible(ConversationStore.Head h, TrustedActor actor) {
        try {
            authorize(h, actor);
            return true;
        } catch (com.xinghuiTec.ai.contract.AiRuntimeException | SceneFailure denied) {
            return false;
        }
    }
    private void authorize(ConversationStore.Head h, TrustedActor actor) {
        if ("LEGACY_NO_BODY".equals(h.history()))
            return;
        for (long request : store.requestIds(Long.parseLong(h.id())))
            runtime.authorizeEvents(runtime.status(request, actor), actor);
    }
    private void owner(ConversationStore.Head h, TrustedActor actor) {
        require(h.subject().equals(actor.subjectId()) && h.tenant().equals(actor.tenantId()), 404, "NOT_FOUND");
    }
    private boolean audit(String scope, TrustedActor actor, boolean platform) {
        require(scope == null || Set.of("OWN", "AUDIT").contains(scope), 400, "INVALID_INPUT");
        boolean audit = "AUDIT".equals(scope);
        if (audit)
            require(platform && actor.authorities().contains("ai:request:audit"), 403, "FORBIDDEN");
        return audit;
    }
    private void caller(TrustedActor actor) {
        require(actor != null && actor.servicePrincipalRef() == null && actor.authorities().contains("ai:scene:invoke"),
                403, "FORBIDDEN");
    }
    private void testAccess(ConversationDtos.Kind kind, ConversationDtos.TestSelection test, TrustedActor actor,
            boolean platform) {
        if (kind == ConversationDtos.Kind.TEST)
            require(platform && actor.authorities().contains("ai:scene:test") && test != null && test.confirmedCosts(),
                    403, "FORBIDDEN");
        else
            require(test == null, 400, "INVALID_INPUT");
    }
    private void message(String message, String nonce) {
        require(message != null && !message.isBlank() && message.length() <= 2048, 400, "INVALID_INPUT");
        require(nonce != null
                && nonce.matches("[a-fA-F0-9]{8}-[a-fA-F0-9]{4}-[a-fA-F0-9]{4}-[a-fA-F0-9]{4}-[a-fA-F0-9]{12}"), 400,
                "INVALID_INPUT");
    }
}
