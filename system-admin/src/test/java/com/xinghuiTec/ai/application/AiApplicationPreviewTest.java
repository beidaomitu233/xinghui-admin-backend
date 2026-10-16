package com.xinghuiTec.ai.application;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.xinghuiTec.ai.contract.*;
import com.xinghuiTec.ai.contract.AiTypes.*;
import com.xinghuiTec.controller.AiRuntimeController;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.support.StaticListableBeanFactory;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;
import org.springframework.security.access.prepost.PreAuthorize;

import java.nio.charset.StandardCharsets;
import java.util.*;

import static org.junit.jupiter.api.Assertions.*;

/** Exact B/C seam with a test-local renderer; not actual C permission/JWT/server acceptance. */
class AiApplicationPreviewTest {
    private final TrustedActor actor = new TrustedActor("101", "platform", Set.of("lowcode:record:query"), null);
    private final String trace = UUID.randomUUID().toString();
    private JdbcTemplate jdbc;
    private AiApplicationStore store;
    private AiApplicationPreview service;
    private AiTemplateRuntime.RenderRequest received;
    private Runnable duringRender = () -> {};
    private int calls;
    private long id;

    @BeforeEach void setup() throws Exception {
        jdbc = new JdbcTemplate(AiApplicationTestDatabase.create());
        store = new AiApplicationStore(jdbc, new ObjectMapper());
        var policy = new AiApplicationPolicy(AiApplicationPolicy.ModelMode.FIXED, List.of(), false,
                new TemplateVersionRef("11", "12"), Set.of("user_request"), new Requirements(OutputFormat.TEXT, true, false),
                AiApplicationPolicy.Scope.BACKGROUND, Set.of("101"), Set.of(), "7", 30, 2, 2, 100);
        id = Long.parseLong(store.create(new AiApplicationStore.Draft("Summary", "Record summary", "lowcode.record.summary/7", policy), 101));
        var beans = new StaticListableBeanFactory();
        beans.addBean("preview", (AiTemplatePreviewRuntime) (request, control) -> {
            calls++; received = request; control.check(); duringRender.run();
            return new AiTemplatePreviewRuntime.PublishedPreview(request.version(), List.of(new AiMessage(MessageRole.USER, "{{user_request}}")),
                    List.of(new AiMessage(MessageRole.USER, "[MASKED]")), Map.of("user_request", "PROVIDED"), Map.of(), List.of());
        });
        service = new AiApplicationPreview(store, beans.getBeanProvider(AiTemplatePreviewRuntime.class));
    }

    private AiApplicationPreview.Input input(int revision, String businessId) {
        return new AiApplicationPreview.Input(revision, new BusinessRef("lowcode.record", businessId), Map.of("user_request", "sensitive test fixture"));
    }

    @Test void ordinaryInputOnlyPreviewUsesTheBoundTemplateAndStillPropagatesResourceDenial() {
        var source = store.get(id).draft();
        var policy = new AiApplicationPolicy(source.modelMode(), source.models(), source.modelFallback(), source.template(), source.inputNames(),
                source.requirements(), source.scope(), source.allowedSubjects(), source.allowedAuthorities(), null,
                source.timeoutSeconds(), source.maxAttempts(), source.maxConcurrent(), source.maxTokens());
        long genericId = Long.parseLong(store.create(new AiApplicationStore.Draft("ordinary", "input-only rewrite", "business.rewrite", policy), 101));
        var input = new AiApplicationPreview.Input(0, new BusinessRef("business.rewrite", "page-ABC"), Map.of("user_request", "neutral fixture"));
        assertEquals(policy.template(), service.preview(genericId, input, actor, trace).version());
        assertSame(actor, received.invocation().actor());
        assertEquals(input.businessRef(), received.invocation().businessRef());
        assertEquals("draft:1", received.invocation().appVersionId());
        assertEquals(ErrorCode.FORBIDDEN, assertThrows(AiRuntimeException.class, () -> service.preview(genericId, input,
                new TrustedActor("102", "platform", Set.of("ai:application:test"), null), trace)).code());
        duringRender = () -> { throw new AiRuntimeException(ErrorCode.RESOURCE_FORBIDDEN); };
        assertEquals(ErrorCode.RESOURCE_FORBIDDEN, assertThrows(AiRuntimeException.class, () -> service.preview(genericId, input, actor, trace)).code());
        assertEquals(0, jdbc.queryForObject("SELECT COUNT(*) FROM sys_ai_request", Integer.class));
    }

    @Test void exactBoundPreviewUsesActualActorAndDraftTagWithoutRequestOrAttemptOrEvidence() {
        var result = service.preview(id, input(0, "7:8"), actor, trace);
        assertEquals(new TemplateVersionRef("11", "12"), received.version());
        assertSame(actor, received.invocation().actor());
        assertEquals("draft:1", received.invocation().appVersionId());
        assertEquals(trace, received.invocation().requestId());
        assertEquals("[MASKED]", result.renderedMessages().get(0).content());
        assertFalse(result.toString().contains("sensitive"));
        for (String table : List.of("sys_ai_request", "sys_ai_request_attempt", "sys_ai_application_version"))
            assertEquals(0, jdbc.queryForObject("SELECT COUNT(*) FROM " + table, Integer.class));
        assertEquals(1, calls); // BACKGROUND business scope does not bypass or block management subject check
    }

    @Test void staleRevisionUnauthorizedActorAndOtherFormCannotReachRenderer() {
        assertEquals(ErrorCode.VERSION_CONFLICT, assertThrows(AiRuntimeException.class, () -> service.preview(id, input(1, "7:8"), actor, trace)).code());
        assertEquals(ErrorCode.FORBIDDEN, assertThrows(AiRuntimeException.class, () -> service.preview(id, input(0, "7:8"),
                new TrustedActor("102", "platform", Set.of("ai:application:test"), null), trace)).code());
        assertEquals(ErrorCode.INVALID_INPUT, assertThrows(AiRuntimeException.class, () -> service.preview(id, input(0, "9:8"), actor, trace)).code());
        assertEquals(0, calls);
    }

    @Test void concurrentDraftChangeOrMissingRendererFailsClosed() {
        duringRender = () -> {
            var app = store.get(id);
            store.updateDraft(id, 0, new AiApplicationStore.Draft(app.name(), app.purpose(), app.entryKey(), app.draft()));
        };
        assertEquals(ErrorCode.VERSION_CONFLICT, assertThrows(AiRuntimeException.class, () -> service.preview(id, input(0, "7:8"), actor, trace)).code());
        var unavailable = new AiApplicationPreview(store, new StaticListableBeanFactory().getBeanProvider(AiTemplatePreviewRuntime.class));
        assertEquals(ErrorCode.DEPENDENCY_UNAVAILABLE, assertThrows(AiRuntimeException.class, () -> unavailable.preview(id, input(1, "7:8"), actor, trace)).code());
    }

    @Test void previewEnvelopeRejectsIdentityAndTemplateOverridesAndIsPrivateBeforeAuthorization() throws Exception {
        String json = "{\"revision\":0,\"businessRef\":{\"type\":\"lowcode.record\",\"id\":\"7:8\"},\"inputs\":{}}";
        var codec = new AiRuntimeBodyCodec();
        for (String addition : List.of("\"actor\":{},", "\"templateVersionId\":\"99\",", "\"runAs\":\"102\","))
            assertThrows(AiRuntimeException.class, () -> codec.read(("{" + addition + json.substring(1)).getBytes(StandardCharsets.UTF_8), AiApplicationPreview.Input.class));
        var request = new MockHttpServletRequest("POST", "/system/ai/applications/1/preview");
        request.setContentType("application/json"); request.setContent(json.getBytes(StandardCharsets.UTF_8));
        var response = new MockHttpServletResponse();
        new AiRuntimeBoundaryFilter(new ObjectMapper()).doFilter(request, response, (req, res) ->
                assertEquals(0, codec.read((byte[]) req.getAttribute(AiRuntimeBoundaryFilter.BODY), AiApplicationPreview.Input.class).revision()));
        assertEquals("no-store, private", response.getHeader("Cache-Control"));
        assertNotNull(UUID.fromString(response.getHeader("X-Request-ID")));
        var method = AiRuntimeController.class.getMethod("preview", long.class, jakarta.servlet.http.HttpServletRequest.class, jakarta.servlet.http.HttpServletResponse.class);
        assertEquals("@platformAccess.isPlatformIdentity() and hasAuthority('ai:application:test')", method.getAnnotation(PreAuthorize.class).value());
    }
}
