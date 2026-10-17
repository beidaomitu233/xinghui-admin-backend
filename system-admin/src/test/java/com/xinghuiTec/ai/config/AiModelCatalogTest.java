package com.xinghuiTec.ai.config;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.sun.net.httpserver.HttpServer;
import org.junit.jupiter.api.*;
import java.net.*;
import java.net.http.HttpClient;
import java.nio.charset.StandardCharsets;
import java.time.*;
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicInteger;
import static com.xinghuiTec.ai.config.AiModelCatalogDtos.*;
import static org.junit.jupiter.api.Assertions.*;

/** Only loopback HTTP with synthetic secrets. No database or external provider calls. */
class AiModelCatalogTest {
    static final String KEY = "synthetic-never-echo-credential";
    static final String OPENROUTER = "{\"data\":[{\"id\":\"vendor/new-model\",\"name\":\"新模型\",\"context_length\":131072,\"top_provider\":{\"max_completion_tokens\":8192},\"architecture\":{\"input_modalities\":[\"text\",\"image\"],\"output_modalities\":[\"text\"]},\"supported_parameters\":[\"tools\",\"response_format\"]},{\"id\":\"vendor/other\",\"name\":\"Other\"}]}";
    HttpServer server;
    ExecutorService executor;
    AiModelCatalogTransport transport;
    Configs configs;
    AiModelCatalogService service;
    volatile String body;
    volatile int status;
    volatile long delay;
    volatile Runnable callback;
    final AtomicInteger calls = new AtomicInteger();
    volatile String path, authorization, method;

    @BeforeEach void start() throws Exception {
        body = OPENROUTER;
        status = 200;
        server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        executor = Executors.newCachedThreadPool(r -> { var thread = new Thread(r); thread.setDaemon(true); return thread; });
        server.setExecutor(executor);
        server.createContext("/", exchange -> {
            calls.incrementAndGet();
            path = exchange.getRequestURI().getPath();
            method = exchange.getRequestMethod();
            authorization = exchange.getRequestHeaders().getFirst("Authorization");
            if (callback != null) callback.run();
            try {
                if (delay > 0) Thread.sleep(delay);
                exchange.getResponseHeaders().set("Content-Type", "application/json");
                if (status == 302) exchange.getResponseHeaders().set("Location", "/must-never-follow");
                byte[] bytes = body.getBytes(StandardCharsets.UTF_8);
                exchange.sendResponseHeaders(status, bytes.length);
                exchange.getResponseBody().write(bytes);
            } catch (InterruptedException interrupted) { Thread.currentThread().interrupt(); }
            finally { exchange.close(); }
        });
        server.start();
        transport = local(Duration.ofSeconds(2));
        configs = new Configs();
        service = new AiModelCatalogService(configs, transport);
    }

    AiModelCatalogTransport local(Duration timeout) {
        return new AiModelCatalogTransport(HttpClient.newBuilder().followRedirects(HttpClient.Redirect.NEVER).build(),
                URI.create("http://127.0.0.1:" + server.getAddress().getPort()), timeout);
    }

    @AfterEach void stop() { if (server != null) server.stop(0); if (executor != null) executor.shutdownNow(); }

    Scan request() { return new Scan("123", "456", 3, "", 1, 20); }

    @Test void publicDiscoveryIsUnauthenticatedReadableSearchableAndNeverGenerationEvidence() {
        Page page = service.catalog("新模型", 1, 1);
        assertEquals("/api/v1/models", path);
        assertEquals("GET", method);
        assertNull(authorization);
        assertEquals(1, page.total());
        assertEquals("vendor/new-model", page.records().get(0).code());
        assertEquals(131072L, page.records().get(0).contextLength());
        assertEquals(8192L, page.records().get(0).maxCompletionTokens());
        assertEquals(List.of("image", "text"), page.records().get(0).inputModalities());
        assertTrue(page.records().get(0).supportedParameters().contains("tools"));
        assertEquals("PUBLIC", page.source());
        assertFalse(page.generationVerified());
        assertNotNull(page.refreshedAt());
        assertNull(page.keyId());
        assertEquals(0, configs.secretReads);
        assertTrue(service.catalog("", Integer.MAX_VALUE, 100).records().isEmpty());
    }

    @Test void savedKeyUsesUserFilteredEndpointWithoutReturningOrStoringCredential() throws Exception {
        Page page = service.scan(request());
        assertEquals("/api/v1/models/user", path);
        assertEquals("Bearer " + KEY, authorization);
        assertEquals("USER_FILTERED", page.source());
        assertEquals("456", page.keyId());
        assertEquals(3, page.revision());
        assertFalse(page.generationVerified());
        assertEquals(1, configs.secretReads);
        assertFalse(new ObjectMapper().findAndRegisterModules().writeValueAsString(page).contains(KEY));
        assertEquals(1, calls.get());
    }

    @Test void deepseekUsesOfficialModelMetadataWithoutHardcodedNamesOrInferredCapabilities() {
        configs.provider = "deepseek";
        configs.endpoint = "https://api.deepseek.com/chat/completions";
        body = "{\"data\":[{\"id\":\"future-model\",\"name\":\"Future Model\",\"context_window\":200000,\"max_output_tokens\":32000,\"input_modalities\":[\"text\",\"image\"],\"output_modalities\":[\"text\"]},{\"id\":\"another-future-model\"}]}";
        Page page = service.scan(request());
        assertEquals("/models", path);
        assertEquals("deepseek", page.provider());
        assertEquals("PROVIDER_KEY", page.source());
        Model model = page.records().stream().filter(m -> m.code().equals("future-model")).findFirst().orElseThrow();
        assertEquals(200000L, model.contextLength());
        assertEquals(32000L, model.maxCompletionTokens());
        assertTrue(model.supportedParameters().isEmpty());
        assertEquals("another-future-model", page.records().get(0).name());
        assertEquals(400, assertThrows(AiModelCatalogException.class, () -> transport.fetch("deepseek", null)).status());
    }

    @Test void providerFailuresAreFixedSafeCodesAndRedirectsAreNeverFollowed() {
        body = "{\"error\":{\"message\":\"" + KEY + "\"}}";
        for (int code : List.of(401, 403, 429, 500, 302)) {
            status = code;
            int before = calls.get();
            var failure = assertThrows(AiModelCatalogException.class, () -> service.scan(request()));
            assertEquals(code == 429 ? 429 : 502, failure.status());
            assertFalse(failure.toString().contains(KEY));
            assertNull(failure.getCause());
            assertEquals(before + 1, calls.get());
        }
    }

    @Test void malformedDuplicateAndSecretEchoMetadataAreRejectedWithoutLeaking() {
        for (String bad : List.of("{", "{\"data\":[],\"data\":[]}", "{\"data\":[]} trailing",
                "{\"data\":[{\"id\":\"vendor/model\",\"name\":\"" + KEY + "\"}]}",
                "{\"data\":[{\"id\":\"http://127.0.0.1?query\",\"name\":\"unsafe\"}]}")) {
            body = bad;
            var failure = assertThrows(AiModelCatalogException.class, () -> service.scan(request()));
            assertEquals("AI_MODEL_CATALOG:UPSTREAM_INVALID", failure.getMessage());
            assertFalse(failure.toString().contains(KEY));
        }
    }

    @Test void responseLimitCancelsBeforeUnboundedBodyAllocation() {
        body = "x".repeat(AiModelCatalogTransport.MAX_BYTES + 1);
        var failure = assertThrows(AiModelCatalogException.class, () -> service.scan(request()));
        assertEquals("AI_MODEL_CATALOG:RESPONSE_LIMIT", failure.getMessage());
    }

    @Test void totalDeadlineBoundsSlowResponses() {
        delay = 600;
        var shortService = new AiModelCatalogService(configs, local(Duration.ofMillis(120)));
        long start = System.nanoTime();
        var failure = assertThrows(AiModelCatalogException.class, () -> shortService.scan(request()));
        assertEquals(504, failure.status());
        assertTrue(Duration.ofNanos(System.nanoTime() - start).toMillis() < 1500);
    }

    @Test void membershipRevisionAndEndpointValidationHappenBeforeCredentialsLeave() {
        assertEquals(409, assertThrows(AiModelCatalogException.class,
                () -> service.scan(new Scan("123", "456", 2, "", 1, 20))).status());
        assertEquals(404, assertThrows(AiModelCatalogException.class,
                () -> service.scan(new Scan("123", "789", 3, "", 1, 20))).status());
        configs.endpoint = "https://127.0.0.1/models";
        assertEquals(422, assertThrows(AiModelCatalogException.class, () -> service.scan(request())).status());
        configs.provider = "unknown";
        assertEquals(422, assertThrows(AiModelCatalogException.class, () -> service.scan(request())).status());
        assertEquals(0, calls.get());
        assertEquals(0, configs.secretReads);
    }

    @Test void changedKeyDuringScanAndUnavailableMaterialCannotBeLabeledAsCurrent() {
        callback = () -> configs.revision++;
        assertEquals("AI_MODEL_CATALOG:CONFIG_CHANGED",
                assertThrows(AiModelCatalogException.class, () -> service.scan(request())).getMessage());
        configs.revision = 3;
        configs.secret = null;
        int before = calls.get();
        assertEquals("AI_MODEL_CATALOG:CREDENTIAL_UNAVAILABLE",
                assertThrows(AiModelCatalogException.class, () -> service.scan(request())).getMessage());
        assertEquals(before, calls.get());
    }

    @Test void inputAndLocalSeamCannotAcceptArbitraryUrlsOrInjectedHeaders() {
        var client = HttpClient.newHttpClient();
        assertThrows(IllegalArgumentException.class, () -> new AiModelCatalogTransport(client,
                URI.create("https://evil.example"), Duration.ofSeconds(1)));
        assertThrows(IllegalArgumentException.class, () -> new AiModelCatalogTransport(client,
                URI.create("http://127.0.0.1:1234/path"), Duration.ofSeconds(1)));
        assertEquals(400, assertThrows(AiModelCatalogException.class, () -> service.catalog("x".repeat(129), 1, 20)).status());
        assertEquals(400, assertThrows(AiModelCatalogException.class, () -> service.catalog("", 1, 101)).status());
        configs.secret = "key\r\nInjected: secret";
        assertEquals(503, assertThrows(AiModelCatalogException.class, () -> service.scan(request())).status());
        assertEquals(0, calls.get());
    }

    @Test void unsavedKeyCanDiscoverBeforeModelSelectionWithoutReadingOrWritingConfiguration() throws Exception {
        var draft = new DraftScan("openrouter", KEY, "新模型", 1, 20);
        Page page = service.draftScan(draft);
        assertEquals(1, page.records().size());
        assertEquals("/api/v1/models/user", path);
        assertNull(page.configId());
        assertNull(page.keyId());
        assertNull(page.revision());
        assertFalse(draft.toString().contains(KEY));
        String encoded = new ObjectMapper().writeValueAsString(draft);
        assertFalse(encoded.contains(KEY));
        assertFalse(encoded.contains("apiKey"));
        assertEquals(0, configs.secretReads);
        assertEquals(0, configs.headReads);
    }

    @Test void draftHttpBodyIsBoundedStrictAndNeverEchoesSecrets() throws Exception {
        var controller = new com.xinghuiTec.controller.AiModelCatalogController(service);
        var valid = new org.springframework.mock.web.MockHttpServletRequest();
        valid.setContent(("{\"provider\":\"openrouter\",\"apiKey\":\"" + KEY + "\",\"pageNum\":1,\"pageSize\":20}").getBytes(StandardCharsets.UTF_8));
        assertNotNull(controller.draftScan(valid));
        for (String bad : List.of("{\"apiKey\":\"" + KEY + "\",\"url\":\"http://evil.example\"}",
                "{\"apiKey\":\"" + KEY + "\",\"apiKey\":\"duplicate\"}", "x".repeat(16385))) {
            var request = new org.springframework.mock.web.MockHttpServletRequest();
            request.setContent(bad.getBytes(StandardCharsets.UTF_8));
            var error = assertThrows(AiModelCatalogException.class, () -> controller.draftScan(request));
            assertFalse(error.toString().contains(KEY));
            assertEquals(bad.length() > 16384 ? 413 : 400, error.status());
        }
    }

    @Test void methodSecurityRejectsTenantAndMissingActionsWhileAllowingDraftAddWithoutRead() {
        var platform = new PlatformFlag();
        var expressions = new org.springframework.security.access.expression.method.DefaultMethodSecurityExpressionHandler();
        var context = new org.springframework.context.support.GenericApplicationContext();
        context.getBeanFactory().registerSingleton("platformAccess", platform);
        context.refresh();
        expressions.setApplicationContext(context);
        var manager = new org.springframework.security.authorization.method.PreAuthorizeAuthorizationManager();
        manager.setExpressionHandler(expressions);
        var factory = new org.springframework.aop.framework.ProxyFactory(new com.xinghuiTec.controller.AiModelCatalogController(service));
        factory.addAdvisor(org.springframework.security.authorization.method.AuthorizationManagerBeforeMethodInterceptor.preAuthorize(manager));
        var secured = (com.xinghuiTec.controller.AiModelCatalogController) factory.getProxy();
        try {
            authenticate("ai:config:add");
            assertNotNull(secured.draftScan(draftHttpRequest()));
            assertThrows(org.springframework.security.access.AccessDeniedException.class, () -> secured.catalog("", 1, 20));
            assertThrows(org.springframework.security.access.AccessDeniedException.class, () -> secured.scan(request()));
            authenticate("ai:config:query");
            assertNotNull(secured.catalog("", 1, 20));
            assertThrows(org.springframework.security.access.AccessDeniedException.class, () -> secured.scan(request()));
            authenticate("ai:config:test");
            assertNotNull(secured.scan(request()));
            platform.allowed = false;
            assertThrows(org.springframework.security.access.AccessDeniedException.class, () -> secured.scan(request()));
            assertThrows(org.springframework.security.access.AccessDeniedException.class, () -> secured.draftScan(draftHttpRequest()));
        } finally {
            org.springframework.security.core.context.SecurityContextHolder.clearContext();
            context.close();
        }
    }

    @Test void httpCatalogUsesNoStoreAndSafeErrors() throws Exception {
        var mvc = org.springframework.test.web.servlet.setup.MockMvcBuilders.standaloneSetup(
                new com.xinghuiTec.controller.AiModelCatalogController(service)).build();
        var success = mvc.perform(org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get("/system/ai/model-catalog"))
                .andReturn().getResponse();
        assertEquals(200, success.getStatus());
        assertEquals("no-store", success.getHeader("Cache-Control"));
        status = 401;
        body = KEY;
        var failure = mvc.perform(org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post("/system/ai/model-catalog/draft-scan")
                .contentType("application/json").content(draftHttpRequest().getContentAsByteArray())).andReturn().getResponse();
        assertEquals(502, failure.getStatus());
        assertTrue(failure.getContentAsString().contains("UPSTREAM_UNAUTHORIZED"));
        assertFalse(failure.getContentAsString().contains(KEY));
        assertEquals("no-store", failure.getHeader("Cache-Control"));
    }

    private org.springframework.mock.web.MockHttpServletRequest draftHttpRequest() {
        var request = new org.springframework.mock.web.MockHttpServletRequest();
        request.setContent(("{\"provider\":\"openrouter\",\"apiKey\":\"" + KEY + "\",\"pageNum\":1,\"pageSize\":20}")
                .getBytes(StandardCharsets.UTF_8));
        return request;
    }

    private void authenticate(String authority) {
        org.springframework.security.core.context.SecurityContextHolder.getContext().setAuthentication(
                new org.springframework.security.authentication.UsernamePasswordAuthenticationToken("operator", "",
                        org.springframework.security.core.authority.AuthorityUtils.createAuthorityList(authority)));
    }

    public static class PlatformFlag {
        boolean allowed = true;
        public boolean isPlatformIdentity() { return allowed; }
    }

    static class Configs extends AiConfigurationService {
        volatile int revision = 3;
        String provider = "openrouter", endpoint = "https://openrouter.ai/api/v1/chat/completions", secret = KEY;
        int secretReads, headReads;
        Configs() { super(null, null, null, null, null, null, null, null); }
        @Override Head head(long id, boolean lock) {
            assertEquals(123, id);
            headReads++;
            return new Head(id, "Config", "test", provider, "DRAFT", revision, 12L, null, null, null, null, LocalDateTime.now());
        }
        @Override public AiConfigurationDtos.Snapshot snapshot(long configId, long version) {
            assertEquals(12, version);
            return new AiConfigurationDtos.Snapshot("12", provider, endpoint, "OPENAI", 15, List.of(),
                    List.of(new AiConfigurationDtos.KeySpec("456", "999", "Key", "DB", null)));
        }
        @Override String resolveSecret(AiConfigurationDtos.KeySpec key) {
            assertEquals("456", key.keyRef());
            assertEquals("999", key.keyVersion());
            secretReads++;
            return secret;
        }
    }
}
