package com.xinghuiTec.ai.config;

import com.fasterxml.jackson.databind.JsonNode;
import com.xinghuiTec.ai.application.*;
import com.xinghuiTec.ai.contract.*;
import com.xinghuiTec.ai.contract.AiProviderRuntime.*;
import com.xinghuiTec.ai.contract.AiTypes.*;
import com.xinghuiTec.ai.model.AiModelConfigRules;
import org.junit.jupiter.api.*;
import org.springframework.beans.factory.support.StaticListableBeanFactory;
import org.springframework.jdbc.datasource.DataSourceTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

import java.io.*;
import java.net.http.*;
import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.Flow;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

/**
 * Actual executor, configured router, encrypted settings, transport/parser and
 * ledger. Only HttpClient is controlled.
 */
class AiMultiChannelRuntimeTest {
    private AiConfigurationServiceTest f;
    private AiSettingsService settings;
    private AiConfiguredProviderRuntime provider;
    private AiRequestExecutor executor;
    private AiRequestStore requests;
    private AiRequestLifecycle lifecycle;
    private AiApplicationStore applications;
    private TransactionTemplate tx;
    private final TrustedActor actor = new TrustedActor("101", "000000", Set.of("ai:config:enable"), null);
    private final AiRequestStore.Owner owner = new AiRequestStore.Owner("101", "000000");
    private final Map<String, Integer> failures = new HashMap<>();
    private final List<String> calls = new CopyOnWriteArrayList<>();
    private final List<HttpRequest> outbound = new CopyOnWriteArrayList<>();
    private final List<AiRuntimeEvents.Event> events = new CopyOnWriteArrayList<>();
    private boolean partial;
    private boolean socketTimeout;
    private boolean hungPrimary;
    private long deadlineMillis = 30000;
    private final List<CompletableFuture<?>> pending = new CopyOnWriteArrayList<>();

    @BeforeEach
    void setup() throws Exception {
        f = new AiConfigurationServiceTest();
        f.setup();
        f.tests.close();
        f.jdbc.execute(
                "CREATE TABLE sys_ai_model_config(model_id BIGINT PRIMARY KEY,provider_code VARCHAR(64),model_code VARCHAR(128))");
        script(read("V20261016_200__ai_app_runtime_v04.sql"));
        script(read("V20261016_220__ai_attempt_trace.sql"));
        // Scene columns are used by the current request store even for legacy
        // application work.
        f.jdbc.execute("ALTER TABLE sys_ai_request ADD COLUMN scene_key VARCHAR(128)");
        f.jdbc.execute("ALTER TABLE sys_ai_request ADD COLUMN scene_version_id BIGINT");
        String conversation = read("V20261018_240__conversation_history.sql");
        script(conversation.substring(conversation.indexOf("CREATE TABLE sys_ai_settings_test_intent")));
        script(read("V20261018_243__settings_key_rotation_receipt.sql"));
        var manager = new DataSourceTransactionManager(f.jdbc.getDataSource());
        tx = new TransactionTemplate(manager);
        when(f.provider.getIfAvailable()).thenReturn(new JdbcAiApplicationReferences(f.jdbc, manager));
        var client = mock(HttpClient.class);
        when(client.sendAsync(any(HttpRequest.class), any(HttpResponse.BodyHandler.class))).thenAnswer(call -> {
            var request = (HttpRequest) call.getArgument(0);
            outbound.add(request);
            JsonNode body = f.json.readTree(body(request));
            String model = body.path("model").asText();
            String material = request.headers().firstValue("Authorization").orElseThrow().substring(7);
            boolean stream = body.path("stream").asBoolean();
            if (stream)
                calls.add(request.uri().getHost() + ":" + material);
            if (stream && socketTimeout && material.equals("controlled-primary"))
                return CompletableFuture.failedFuture(new HttpConnectTimeoutException("controlled timeout"));
            if (stream && hungPrimary && material.equals("controlled-primary")) {
                var forever = new CompletableFuture<HttpResponse<InputStream>>();
                pending.add(forever);
                return forever;
            }
            int code = stream ? failures.getOrDefault(material, 200) : 200;
            String payload;
            if (stream && partial && material.equals("controlled-primary"))
                payload = frame(model, "controlled partial", null) + "data: {\"error\":{\"code\":503}}\n\n";
            else if (code != 200)
                payload = "{}";
            else if (stream)
                payload = frame(model, "controlled answer", "stop") + "data: [DONE]\n\n";
            else
                payload = "{\"model\":\"" + model
                        + "\",\"choices\":[{\"message\":{\"content\":\"OK\"},\"finish_reason\":\"stop\"}]}";
            HttpResponse<InputStream> response = mock(HttpResponse.class);
            when(response.statusCode()).thenReturn(code);
            when(response.body()).thenReturn(new ByteArrayInputStream(payload.getBytes(StandardCharsets.UTF_8)));
            return CompletableFuture.completedFuture(response);
        });
        var transport = new OpenRouterTransport(f.json, new AiModelConfigRules(), client);
        f.tests = new AiConfigurationTests(f.service, transport, tx);
        settings = new AiSettingsService(f.service, f.tests, tx);
        provider = new AiConfiguredProviderRuntime(f.service, transport);
        var beans = new StaticListableBeanFactory();
        beans.addBean("observer", settings);
        beans.addBean("provider", provider);
        f.tests.completionObservers(beans.getBeanProvider(AiConfigurationTestObserver.class));
        AiRuntimeEvents journalEvents = new AiRuntimeEvents() {
            public Event append(Event event) {
                var next = event
                        .sequenced(events.stream().filter(e -> e.requestId().equals(event.requestId())).count() + 1);
                events.add(next);
                return next;
            }
            public List<Event> after(String id, long sequence) {
                return events.stream().filter(e -> e.requestId().equals(id) && e.sequence() > sequence).toList();
            }
        };
        applications = new AiApplicationStore(f.jdbc, f.json);
        requests = new AiRequestStore(f.jdbc);
        lifecycle = new AiRequestLifecycle(requests, journalEvents, manager);
        executor = new AiRequestExecutor(requests, applications, new AiRequestJournal(f.jdbc, requests, manager),
                lifecycle, new AiSharedModelCursor(f.jdbc), journalEvents,
                beans.getBeanProvider(AiProviderRuntime.class), beans.getBeanProvider(AiTemplateRuntime.class), f.jdbc,
                manager);
    }
    @AfterEach
    void close() {
        f.close();
    }
    private String read(String name) throws Exception {
        try (var in = getClass().getResourceAsStream("/db/migration/" + name)) {
            return new String(Objects.requireNonNull(in).readAllBytes(), StandardCharsets.UTF_8);
        }
    }
    private void script(String sql) {
        for (String s : sql.replace(" COLLATE utf8mb4_bin", "").replace(" ENGINE=InnoDB DEFAULT CHARSET=utf8mb4", "")
                .replaceAll("(?m)^--.*$", "").split(";"))
            if (!s.isBlank())
                f.jdbc.execute(s);
    }
    private String frame(String model, String text, String finish) {
        return "data: {\"model\":\"" + model + "\",\"choices\":[{\"delta\":{\"content\":\"" + text
                + "\"},\"finish_reason\":" + (finish == null ? "null" : "\"" + finish + "\"") + "}]}\n\n";
    }
    private String body(HttpRequest request) throws Exception {
        var result = new CompletableFuture<String>();
        var bytes = new ByteArrayOutputStream();
        request.bodyPublisher().orElseThrow().subscribe(new Flow.Subscriber<ByteBuffer>() {
            public void onSubscribe(Flow.Subscription s) {
                s.request(Long.MAX_VALUE);
            }
            public void onNext(ByteBuffer b) {
                byte[] block = new byte[b.remaining()];
                b.get(block);
                bytes.writeBytes(block);
            }
            public void onError(Throwable e) {
                result.completeExceptionally(e);
            }
            public void onComplete() {
                result.complete(bytes.toString(StandardCharsets.UTF_8));
            }
        });
        return result.get(2, TimeUnit.SECONDS);
    }
    private AiConfigurationDtos.View channel(String providerCode, String name, String... material) throws Exception {
        String model = providerCode.equals("deepseek") ? "deepseek-flash" : "unit/model";
        var keys = new ArrayList<AiSettingsDtos.KeyChange>();
        for (int i = 0; i < material.length; i++)
            keys.add(new AiSettingsDtos.KeyChange(null, name + " " + i, material[i], List.of(model)));
        var created = settings
                .create(new AiSettingsDtos.Change(null, name, providerCode, null, null, List.of(model), keys));
        long id = Long.parseLong(created.configId());
        var run = settings.startTest(id, new AiSettingsDtos.Test(true, true), actor);
        Instant limit = Instant.now().plusSeconds(4);
        while ("PENDING".equals(
                f.jdbc.queryForObject("SELECT activation_status FROM sys_ai_settings_test_intent WHERE test_id=?",
                        String.class, run.testId()))
                && Instant.now().isBefore(limit))
            Thread.sleep(5);
        assertTrue(settings.get(id).active());
        return f.service.get(id);
    }
    private ModelBinding binding(AiConfigurationDtos.View view) {
        return new ModelBinding(new ModelVersionRef(view.draft().models().get(0).modelRef(), view.activeVersion()),
                KeyMode.AUTO, Set.of(), null, List.of());
    }
    private AiRequestExecutor.Work work(List<ModelBinding> bindings, int attempts, boolean fallback) {
        var policy = new AiApplicationPolicy(AiApplicationPolicy.ModelMode.FIXED, bindings, fallback, null,
                Set.of("user_request"), new Requirements(OutputFormat.TEXT, true, false),
                AiApplicationPolicy.Scope.BOTH, Set.of("101"), Set.of(), null, 30, attempts, 2, 256);
        long app = Long.parseLong(applications
                .create(new AiApplicationStore.Draft("controlled", "controlled", "scene/controlled", policy), 101));
        long version = 1000 + app;
        f.jdbc.update(
                "INSERT INTO sys_ai_application_version(app_version_id,app_id,version_number,draft_revision,snapshot_json,tested_request_id,created_by) VALUES(?,?,1,1,'{}',1,101)",
                version, app);
        f.jdbc.update("UPDATE sys_ai_application SET enabled=TRUE,published_version_id=? WHERE app_id=?", version, app);
        f.jdbc.update("INSERT INTO sys_ai_application_cursor VALUES(?,0)", version);
        var now = Instant.now();
        var admitted = tx.execute(s -> requests.accept(
                new AiRequestStore.Admission(app, version, null, AiRequestStore.Kind.BUSINESS, owner, "ai.scene",
                        "controlled", UUID.randomUUID().toString(), "a".repeat(64), now.plusMillis(deadlineMillis)),
                now));
        lifecycle.accepted(Long.parseLong(admitted.request().requestId()), owner);
        return new AiRequestExecutor.Work(
                new Invocation(actor, Long.toString(app), Long.toString(version),
                        new BusinessRef("ai.scene", "controlled"), admitted.request().requestId()),
                policy, List.of(new AiMessage(MessageRole.USER, "controlled")), List.of(), Set.of("user_request"),
                false, () -> {
                });
    }
    private AiRequestStore.Request run(AiRequestExecutor.Work work) {
        executor.execute(work);
        return requests.owned(Long.parseLong(work.invocation().requestId()), owner);
    }
    @Test
    void rateLimitTriesSameModelOtherKeyBeforeIndependentDeepSeekChannel() throws Exception {
        var primary = channel("openrouter", "Primary", "controlled-primary", "controlled-second");
        var backup = channel("deepseek", "Backup", "controlled-backup");
        assertEquals(2, provider.availableModels(new Requirements(OutputFormat.TEXT, true, false)).size());
        failures.put("controlled-primary", 429);
        failures.put("controlled-second", 503);
        var work = work(List.of(binding(primary), binding(backup)), 4, true);
        assertEquals(AiRequestStatus.SUCCEEDED, run(work).status());
        assertEquals(List.of("openrouter.ai:controlled-primary", "openrouter.ai:controlled-second",
                "api.deepseek.com:controlled-backup"), calls);
        assertEquals(List.of("FIRST", "NEXT_KEY", "FALLBACK_MODEL"),
                f.jdbc.query(
                        "SELECT switch_reason FROM sys_ai_request_attempt WHERE request_id=? ORDER BY attempt_number",
                        (rs, n) -> rs.getString(1), work.invocation().requestId()));
        assertEquals(Arrays.asList("RATE_LIMITED", "PROVIDER_UNAVAILABLE", null),
                f.jdbc.query("SELECT error_code FROM sys_ai_request_attempt WHERE request_id=? ORDER BY attempt_number",
                        (rs, n) -> rs.getString(1), work.invocation().requestId()));
        var deep = outbound.stream().filter(r -> r.uri().getHost().equals("api.deepseek.com")).reduce((a, b) -> b)
                .orElseThrow();
        var payload = f.json.readTree(body(deep));
        assertEquals("disabled", payload.path("thinking").path("type").asText());
        assertFalse(payload.has("provider"));
        assertEquals("/chat/completions", deep.uri().getPath());
    }
    @Test
    void real408And504ResponsesSwitchToBackupBeforeGlobalDeadline() throws Exception {
        for (int status : List.of(408, 504)) {
            calls.clear();
            var primary = channel("openrouter", "Primary", "controlled-primary");
            var backup = channel("deepseek", "Backup", "controlled-backup");
            failures.put("controlled-primary", status);
            assertEquals(AiRequestStatus.SUCCEEDED,
                    run(work(List.of(binding(primary), binding(backup)), 3, true)).status());
            assertEquals(2, calls.size());
        }
    }
    @Test
    void connectionTimeoutSwitchesChannelWithActualTransport() throws Exception {
        var a = channel("openrouter", "Primary", "controlled-primary");
        var b = channel("deepseek", "Backup", "controlled-backup");
        socketTimeout = true;
        assertEquals(AiRequestStatus.SUCCEEDED, run(work(List.of(binding(a), binding(b)), 3, true)).status());
        assertEquals(2, calls.size());
    }
    @Test
    void authenticationFailureQuarantinesMaterialAndOtherKeyContinues() throws Exception {
        var a = channel("openrouter", "Primary", "controlled-primary", "controlled-second");
        failures.put("controlled-primary", 401);
        assertEquals(AiRequestStatus.SUCCEEDED, run(work(List.of(binding(a)), 3, false)).status());
        assertEquals(2, calls.size());
        calls.clear();
        assertEquals(AiRequestStatus.SUCCEEDED, run(work(List.of(binding(a)), 3, false)).status());
        assertEquals(List.of("openrouter.ai:controlled-second"), calls);
        assertEquals(AiSettingsDtos.ConnectionStatus.FAILED,
                settings.get(Long.parseLong(a.id())).keys().get(0).status());
    }
    @Test
    void allChannelsFailAndLedgerKeepsEveryActualAttempt() throws Exception {
        var a = channel("openrouter", "Primary", "controlled-primary");
        var b = channel("deepseek", "Backup", "controlled-backup");
        failures.put("controlled-primary", 503);
        failures.put("controlled-backup", 429);
        var work = work(List.of(binding(a), binding(b)), 3, true);
        var result = run(work);
        assertEquals(AiRequestStatus.FAILED, result.status());
        assertEquals(2, result.attemptCount());
        assertEquals("RATE_LIMITED", result.errorCode());
        assertEquals(2,
                f.jdbc.queryForObject(
                        "SELECT COUNT(*) FROM sys_ai_request_attempt WHERE request_id=? AND status='FAILED'",
                        Integer.class, work.invocation().requestId()));
    }
    @Test
    void emittedPartialOutputCannotBeSplicedWithAnotherKeyOrChannel() throws Exception {
        var a = channel("openrouter", "Primary", "controlled-primary", "controlled-second");
        var b = channel("deepseek", "Backup", "controlled-backup");
        partial = true;
        var work = work(List.of(binding(a), binding(b)), 4, true);
        assertEquals(AiRequestStatus.INCOMPLETE, run(work).status());
        assertEquals(1, calls.size());
        assertEquals("controlled partial", events.stream().filter(e -> e.name() == AiRuntimeEvents.Name.DELTA)
                .map(AiRuntimeEvents.Event::text).reduce("", String::concat));
    }
    @Test
    void attemptBudgetStopsBeforeUnboundedChannelRetries() throws Exception {
        var a = channel("openrouter", "Primary", "controlled-primary", "controlled-second");
        var b = channel("deepseek", "Backup", "controlled-backup");
        failures.put("controlled-primary", 429);
        failures.put("controlled-second", 503);
        var work = work(List.of(binding(a), binding(b)), 2, true);
        assertEquals(AiRequestStatus.FAILED, run(work).status());
        assertEquals(2, calls.size());
        assertTrue(calls.stream().noneMatch(c -> c.startsWith("api.deepseek.com")));
    }
    @Test
    void hungFirstKeyTimesOutInsideAttemptWindowAndBackupSucceeds() throws Exception {
        var a = channel("openrouter", "Primary", "controlled-primary", "controlled-second");
        hungPrimary = true;
        deadlineMillis = 1600;
        var work = work(List.of(binding(a)), 3, false);
        var started = Instant.now();
        assertEquals(AiRequestStatus.SUCCEEDED, run(work).status());
        assertEquals(List.of("openrouter.ai:controlled-primary", "openrouter.ai:controlled-second"), calls);
        assertTrue(java.time.Duration.between(started, Instant.now()).toMillis() < 1600);
        assertTrue(pending.get(0).isCancelled());
        assertEquals("DEADLINE_EXCEEDED",
                f.jdbc.queryForObject(
                        "SELECT error_code FROM sys_ai_request_attempt WHERE request_id=? AND attempt_number=1",
                        String.class, work.invocation().requestId()));
    }
    @Test
    void singleTargetKeepsWholeRemainingRequestWindow() throws Exception {
        var a = channel("openrouter", "Only", "controlled-primary");
        deadlineMillis = 900;
        hungPrimary = true;
        var started = Instant.now();
        var result = run(work(List.of(binding(a)), 8, false));
        long elapsed = java.time.Duration.between(started, Instant.now()).toMillis();
        assertEquals(AiRequestStatus.TIMED_OUT, result.status());
        assertEquals(1, calls.size());
        assertEquals(256,
                f.jdbc.queryForObject(
                        "SELECT output_token_limit FROM sys_ai_request_attempt ORDER BY attempt_id DESC LIMIT 1",
                        Integer.class));
        assertTrue(elapsed >= 650, "single target must not get an artificial one-eighth window");
    }

    @Test
    void namedKeysRoundTripWithoutSecretsAndOldInputNeverEditsFirstKey() throws Exception {
        var channel = channel("openrouter", "Named", "controlled-primary", "controlled-second");
        long id = Long.parseLong(channel.id());
        var view = settings.get(id);
        assertEquals(2, view.keys().size());
        String json = f.json.writeValueAsString(view);
        assertFalse(json.contains("controlled-primary"));
        assertFalse(json.contains("controlled-second"));
        assertFalse(json.contains("credential_ciphertext"));
        var original = f.service.get(id).draft();
        int before = outbound.size();
        settings.save(id, new AiSettingsDtos.Change(view.revision(), "Renamed", view.provider(), null, "",
                List.of("unit/model")));
        assertEquals(original, f.service.get(id).draft());
        assertEquals(before, outbound.size());
        var latest = settings.get(id);
        assertEquals(400,
                assertThrows(AiConfigurationException.class,
                        () -> settings.save(id, new AiSettingsDtos.Change(latest.revision(), latest.name(),
                                latest.provider(), null, "ambiguous-material", List.of("unit/model"))))
                        .status());
        assertEquals(original, f.service.get(id).draft());
        assertEquals(400,
                assertThrows(AiConfigurationException.class,
                        () -> settings.save(id, new AiSettingsDtos.Change(latest.revision(), latest.name(),
                                latest.provider(), null, null, List.of("unit/model"), List.of())))
                        .status());
    }
    @Test
    void onlyExplicitKeyModelAssociationsAreTestedAndSelectable() throws Exception {
        var keys = List.of(
                new AiSettingsDtos.KeyChange(null, "Many", "controlled-primary", List.of("unit/a", "unit/b")),
                new AiSettingsDtos.KeyChange(null, "A backup", "controlled-second", List.of("unit/a")));
        var created = settings
                .create(new AiSettingsDtos.Change(null, "Matrix", "openrouter", null, null, List.of(), keys));
        long id = Long.parseLong(created.configId());
        var start = settings.startTest(id, new AiSettingsDtos.Test(true, true), actor);
        Instant until = Instant.now().plusSeconds(4);
        while ("PENDING".equals(
                f.jdbc.queryForObject("SELECT activation_status FROM sys_ai_settings_test_intent WHERE test_id=?",
                        String.class, start.testId()))
                && Instant.now().isBefore(until))
            Thread.sleep(5);
        var result = settings.testResult(id, Long.parseLong(start.testId()));
        assertTrue(result.active());
        assertEquals(2, result.keys().size());
        assertEquals(2, result.models().size());
        assertEquals(3, f.tests.get(id, Long.parseLong(start.testId())).total());
        assertEquals(3, outbound.size());
        var live = f.service.get(id);
        var snapshot = live.draft();
        var a = new ModelBinding(new ModelVersionRef(snapshot.models().get(0).modelRef(), live.activeVersion()),
                KeyMode.AUTO, Set.of(), null, List.of());
        var b = new ModelBinding(new ModelVersionRef(snapshot.models().get(1).modelRef(), live.activeVersion()),
                KeyMode.AUTO, Set.of(), null, List.of());
        assertEquals(2, provider.resolveTargets(a, Set.of(), f.control).size());
        assertEquals(1, provider.resolveTargets(b, Set.of(), f.control).size());
        var forbidden = new Target(b.model(),
                new KeyRef(snapshot.keys().get(1).keyRef(), snapshot.keys().get(1).keyVersion()), "openrouter",
                "unit/b", "A backup", new Capabilities(Set.of(OutputFormat.TEXT), true, false));
        var invocation = new Invocation(actor, "1", "1", new BusinessRef("ai.scene", "controlled"), "1");
        assertEquals(ErrorCode.NO_USABLE_KEY,
                assertThrows(AiRuntimeException.class,
                        () -> provider.execute(
                                new ExecutionRequest(invocation, "1", forbidden,
                                        List.of(new AiMessage(MessageRole.USER, "controlled")),
                                        new Requirements(OutputFormat.TEXT, true, false), 64),
                                mock(StreamObserver.class), f.control))
                        .code());
    }
    @Test
    void sparseCapacityUsesActualAssociationsRatherThanCartesianProduct() {
        var keys = new ArrayList<AiSettingsDtos.KeyChange>();
        for (int i = 0; i < 16; i++)
            keys.add(new AiSettingsDtos.KeyChange(null, "Key " + i, "controlled-capacity-" + i,
                    List.of("unit/model-" + i)));
        var view = settings
                .create(new AiSettingsDtos.Change(null, "Sparse", "openrouter", null, null, List.of(), keys));
        assertEquals(16, view.keys().size());
        assertEquals(16, view.models().size());
        assertTrue(outbound.isEmpty());
        var broad = keys.stream().map(k -> new AiSettingsDtos.KeyChange(null, k.name(), "controlled-capacity", null))
                .toList();
        assertEquals(400,
                assertThrows(AiConfigurationException.class,
                        () -> settings.create(new AiSettingsDtos.Change(null, "Too broad", "openrouter", null, null,
                                view.models().stream().map(AiSettingsDtos.Model::code).toList(), broad)))
                        .status());
    }
    @Test
    void keyIdentityCannotBeCopiedAcrossChannels() throws Exception {
        var a = channel("openrouter", "A", "controlled-primary");
        var b = channel("openrouter", "B", "controlled-second");
        var key = settings.get(Long.parseLong(a.id())).keys().get(0);
        assertEquals(400,
                assertThrows(AiConfigurationException.class,
                        () -> settings.save(Long.parseLong(b.id()),
                                new AiSettingsDtos.Change(b.revision(), b.name(), b.providerCode(), null, null,
                                        List.of("unit/model"),
                                        List.of(new AiSettingsDtos.KeyChange(key.keyId(), key.name(), "", null)))))
                        .status());
    }

    @Test
    void settingsMutationReplaysAndConflictsWithoutDuplicatingSecrets() throws Exception {
        String mutation = UUID.randomUUID().toString();
        var input = new AiSettingsDtos.Change(null, "Idempotent", "openrouter", null, "controlled-primary",
                List.of("unit/model"), null, mutation);
        var created = settings.create(input, actor);
        assertEquals(created.configId(), settings.create(input, actor).configId());
        assertEquals(1, f.jdbc.queryForObject("SELECT COUNT(*) FROM sys_ai_configuration_key_material", Integer.class));
        assertEquals(409,
                assertThrows(AiConfigurationException.class,
                        () -> settings.create(new AiSettingsDtos.Change(null, "Changed", "openrouter", null,
                                "controlled-primary", List.of("unit/model"), null, mutation), actor))
                        .status());
        String receipt = f.jdbc.queryForObject("SELECT fingerprint FROM sys_ai_settings_mutation", String.class);
        assertTrue(receipt.matches("[0-9a-f]{64}"));
        assertFalse(receipt.contains("controlled"));
        long id = Long.parseLong(created.configId());
        var save = new AiSettingsDtos.Change(created.revision(), created.name(), created.provider(), null,
                "controlled-replacement", List.of("unit/model"), null, UUID.randomUUID().toString());
        var changed = settings.save(id, save, actor);
        assertEquals(changed.revision(), settings.save(id, save, actor).revision());
        assertEquals(2, f.jdbc.queryForObject("SELECT COUNT(*) FROM sys_ai_configuration_key_material", Integer.class));
    }
    @Test
    void searchFiltersBeforeTotalAndPagination() {
        for (int i = 0; i < 23; i++)
            settings.create(new AiSettingsDtos.Change(null, i == 22 ? "Find me" : "Other " + i, "openrouter", null,
                    "controlled-primary", List.of("unit/model")));
        var result = settings.page(1, 5, "Find me");
        assertEquals("1", result.total());
        assertEquals(1, result.records().size());
        assertEquals("Find me", result.records().get(0).name());
        assertEquals("23", settings.page(1, 5).total());
        assertTrue(settings.page(2, 5, "Find me").records().isEmpty());
    }
    @Test
    void successfulRetestRestoresPreviouslyRejectedMaterial() throws Exception {
        var a = channel("openrouter", "Primary", "controlled-primary", "controlled-second");
        failures.put("controlled-primary", 401);
        run(work(List.of(binding(a)), 3, false));
        assertEquals(AiSettingsDtos.ConnectionStatus.FAILED,
                settings.get(Long.parseLong(a.id())).keys().get(0).status());
        failures.clear();
        var test = settings.startTest(Long.parseLong(a.id()), new AiSettingsDtos.Test(true, true), actor);
        Instant until = Instant.now().plusSeconds(4);
        while ("PENDING".equals(
                f.jdbc.queryForObject("SELECT activation_status FROM sys_ai_settings_test_intent WHERE test_id=?",
                        String.class, test.testId()))
                && Instant.now().isBefore(until))
            Thread.sleep(5);
        assertEquals(AiSettingsDtos.ConnectionStatus.SUCCEEDED,
                settings.get(Long.parseLong(a.id())).keys().get(0).status());
        calls.clear();
        assertEquals(AiRequestStatus.SUCCEEDED, run(work(List.of(binding(a)), 3, false)).status());
        assertEquals(List.of("openrouter.ai:controlled-primary"), calls);
    }
    @Test
    void cancellingHungAttemptStopsImmediatelyWithoutFallback() throws Exception {
        var a = channel("openrouter", "Primary", "controlled-primary", "controlled-second");
        hungPrimary = true;
        var work = work(List.of(binding(a)), 3, false);
        var running = CompletableFuture.runAsync(() -> executor.execute(work));
        Instant until = Instant.now().plusSeconds(2);
        while (pending.isEmpty() && Instant.now().isBefore(until))
            Thread.sleep(5);
        assertFalse(pending.isEmpty());
        lifecycle.finish(Long.parseLong(work.invocation().requestId()), owner, AiRequestStatus.CANCELLED,
                ErrorCode.CANCELLED, null);
        running.get(2, TimeUnit.SECONDS);
        assertEquals(AiRequestStatus.CANCELLED,
                requests.owned(Long.parseLong(work.invocation().requestId()), owner).status());
        assertEquals(1, calls.size());
        assertTrue(pending.get(0).isCancelled());
    }

    @Test
    void concurrentIdenticalCreatesProduceOneChannelAndOneMaterial() throws Exception {
        var input = new AiSettingsDtos.Change(null, "Concurrent", "openrouter", null, "controlled-primary",
                List.of("unit/model"), null, UUID.randomUUID().toString());
        var pool = Executors.newFixedThreadPool(2);
        try {
            var first = pool.submit(() -> settings.create(input, actor));
            var second = pool.submit(() -> settings.create(input, actor));
            assertEquals(first.get(3, TimeUnit.SECONDS).configId(), second.get(3, TimeUnit.SECONDS).configId());
            assertEquals(1, f.jdbc.queryForObject("SELECT COUNT(*) FROM sys_ai_configuration", Integer.class));
            assertEquals(1,
                    f.jdbc.queryForObject("SELECT COUNT(*) FROM sys_ai_configuration_key_material", Integer.class));
        } finally {
            pool.shutdownNow();
        }
    }
    @Test
    void providerPresetCannotBeRedirectedToArbitraryOrOtherProviderEndpoint() {
        assertEquals(400, assertThrows(AiConfigurationException.class,
                () -> settings.create(new AiSettingsDtos.Change(null, "Unsafe", "deepseek",
                        "https://example.com/chat/completions", "controlled-primary", List.of("deepseek-flash"))))
                .status());
        assertEquals(400,
                assertThrows(AiConfigurationException.class,
                        () -> settings.create(new AiSettingsDtos.Change(null, "Wrong", "deepseek",
                                OpenRouterTransport.ENDPOINT, "controlled-primary", List.of("deepseek-flash"))))
                        .status());
        assertTrue(outbound.isEmpty());
    }

}
