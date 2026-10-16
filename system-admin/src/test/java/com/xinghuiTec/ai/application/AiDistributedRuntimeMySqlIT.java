package com.xinghuiTec.ai.application;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.xinghuiTec.ai.contract.*;
import com.xinghuiTec.ai.contract.AiProviderRuntime.*;
import com.xinghuiTec.ai.contract.AiTypes.*;
import com.xinghuiTec.config.SecretCipher;
import io.lettuce.core.resource.DefaultClientResources;
import org.junit.jupiter.api.Test;
import org.springframework.aop.support.AopUtils;
import org.springframework.beans.factory.support.StaticListableBeanFactory;
import org.springframework.context.annotation.*;
import org.springframework.data.redis.connection.RedisPassword;
import org.springframework.data.redis.connection.RedisStandaloneConfiguration;
import org.springframework.data.redis.connection.lettuce.*;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.*;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.annotation.EnableTransactionManagement;
import org.springframework.transaction.support.TransactionTemplate;

import java.io.*;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.time.*;
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.*;

/** Explicit private QA only: two independent JVMs, real assigned MySQL/volatile Redis, controlled
 * A/C test adapters. No HTTP identity, real job credentials, vendor call, migration or reset. */
public class AiDistributedRuntimeMySqlIT {
    private static final ObjectMapper JSON = new ObjectMapper().findAndRegisterModules();
    private static final String SERVICE = "qa:ai-v04-b:background";
    private static final List<String> MODELS = List.of("91001", "91002", "91003");
    private static final String URL = "jdbc:mysql://127.0.0.1:13319/aiv04_b_20261016?useSSL=false&allowPublicKeyRetrieval=true&serverTimezone=Asia/Shanghai";

    @Test void twoIndependentJvmsShareRealRoundRobinAndAuthorizedBackgroundRequests() throws Exception {
        assertEquals("1", System.getenv("AI_V04_B_DISTRIBUTED_RUN"), "Only run in the PM-approved QA window");
        var jdbc = new JdbcTemplate(source());
        var report = new LinkedHashMap<String, Object>();
        preflight(jdbc, report); // MUST precede every fixture write, including when the assigned DB is wrong.
        String run = UUID.randomUUID().toString().replace("-", "");
        String entry = "qa.background.rr." + run;
        String tenant = "qa-bg-" + run;
        String prefix = "qa:aiv04_b_20261016:rr" + run.substring(0, 12) + ":";
        Path directory = Path.of(Objects.requireNonNull(System.getenv("AI_V04_QA_REPORT_DIR"))).toAbsolutePath();
        Files.createDirectories(directory);
        assertEquals(0, jdbc.queryForObject("SELECT COUNT(*) FROM sys_ai_application WHERE entry_key=?", Integer.class, entry));
        long beforeApps = jdbc.queryForObject("SELECT COUNT(*) FROM sys_ai_application", Long.class);
        long beforeRequests = jdbc.queryForObject("SELECT COUNT(*) FROM sys_ai_request", Long.class);
        long app = 0;
        var ids = new ArrayList<Long>();
        try {
            String version;
            try (var setup = new Node(tenant, prefix)) {
                var bindings = MODELS.stream().map(model -> new ModelBinding(new ModelVersionRef(model, "92001"), KeyMode.AUTO, Set.of(), null, List.of())).toList();
                var policy = new AiApplicationPolicy(AiApplicationPolicy.ModelMode.ROUND_ROBIN, bindings, false,
                        new TemplateVersionRef("93001", "94001"), Set.of("user_request"), new Requirements(OutputFormat.TEXT, true, false),
                        AiApplicationPolicy.Scope.BACKGROUND, Set.of(SERVICE), Set.of(), null, 30, 1, 2, 128);
                app = Long.parseLong(setup.apps.create(new AiApplicationStore.Draft("QA background RR " + run, "Controlled internal service acceptance", entry, policy), 900001));
                var tests = new ArrayList<String>();
                for (int index = 0; index < 3; index++) {
                    var accepted = setup.runtime.test(app, 0, index, submission(UUID.randomUUID().toString(), "setup:" + index, "neutral"), setup.actor);
                    assertEquals(AiRequestStatus.SUCCEEDED, setup.await(Long.parseLong(accepted.requestId()), false).status());
                    tests.add(accepted.requestId());
                }
                version = setup.versions.publish(app, 0, tests, setup.actor).appVersionId();
            }
            report.put("appId", Long.toString(app)); report.put("appVersionId", version);
            report.put("principal", Map.of("servicePrincipalRef", SERVICE, "tenant", tenant, "source", "PM-authorized private server-owned QA binding; no LoginUser or run-as"));
            report.put("adapters", "A/C controlled test-only pure input adapters; zero vendor/network model calls");
            var trace = new ArrayList<JsonNode>();
            try (var first = new Worker(directory, "a", app, tenant, prefix); var second = new Worker(directory, "b", app, tenant, prefix)) {
                assertNotEquals(first.process.pid(), second.process.pid());
                report.put("workerPids", List.of(first.process.pid(), second.process.pid()));
                String originalNonce = UUID.randomUUID().toString();
                for (int index = 0; index < 4; index++) {
                    var node = index % 2 == 0 ? first : second;
                    var result = node.command(Map.of("op", "submit", "nonce", index == 0 ? originalNonce : UUID.randomUUID().toString(), "business", "rr:" + index, "text", "neutral"));
                    assertEquals(MODELS.get(index % 3), result.path("modelRef").textValue());
                    assertEquals("SUCCEEDED", result.path("status").textValue()); trace.add(result);
                }
                var replay = second.command(Map.of("op", "submit", "nonce", originalNonce, "business", "rr:0", "text", "neutral"));
                assertEquals(trace.get(0).path("requestId"), replay.path("requestId"));
                assertEquals(trace.get(3).path("providerCalls"), replay.path("providerCalls"));
                assertEquals(4, jdbc.queryForObject("SELECT COUNT(*) FROM sys_ai_request WHERE app_id=? AND request_kind='BUSINESS'", Integer.class, app));
                var shared = second.command(Map.of("op", "events", "requestId", trace.get(0).path("requestId").textValue()));
                assertTrue(shared.path("completed").booleanValue()); assertTrue(shared.path("ttl").longValue() > 0 && shared.path("ttl").longValue() <= 600);
                report.put("crossProcessReplay", replay); report.put("crossProcessRedis", shared);
                var held = first.command(Map.of("op", "hold", "nonce", UUID.randomUUID().toString(), "business", "held", "text", "qa-hold"));
                assertEquals("RUNNING", held.path("status").textValue());
                var cancelled = second.command(Map.of("op", "cancel", "requestId", held.path("requestId").textValue()));
                assertEquals("CANCELLED", cancelled.path("status").textValue());
                var settled = first.command(Map.of("op", "settled", "requestId", held.path("requestId").textValue()));
                assertEquals("CANCELLED", settled.path("status").textValue());
                report.put("crossProcessCancellation", settled);
                report.put("denials", second.command(Map.of("op", "denials", "requestId", trace.get(0).path("requestId").textValue())));
            }
            report.put("roundRobin", trace);
            report.put("keysPerModel", List.of(3, 1, 1));
            var owner = AiRuntimeService.owner(principal(tenant));
            assertEquals(8, jdbc.queryForObject("SELECT COUNT(*) FROM sys_ai_request WHERE app_id=? AND owner_scope=? AND tenant_ref=? AND subject_ref=?",
                    Integer.class, app, owner.scope(), tenant, "service:" + SERVICE));
            report.put("requestKinds", jdbc.queryForList("SELECT request_kind,COUNT(*) AS count FROM sys_ai_request WHERE app_id=? GROUP BY request_kind", app));
            report.put("attempts", jdbc.queryForList("SELECT r.request_id,r.request_kind,a.model_ref,a.key_ref,a.status,a.switch_reason,a.provider_code FROM sys_ai_request r JOIN sys_ai_request_attempt a ON a.request_id=r.request_id WHERE r.app_id=? ORDER BY r.request_id,a.attempt_number", app));
            report.put("result", "PASS");
        } finally {
            if (app > 0) {
                assertEquals(entry, jdbc.queryForObject("SELECT entry_key FROM sys_ai_application WHERE app_id=?", String.class, app));
                ids.addAll(jdbc.query("SELECT request_id FROM sys_ai_request WHERE app_id=?", (rs, index) -> rs.getLong(1), app));
                try (var cleanup = new Node(tenant, prefix)) {
                    for (long id : ids) cleanup.redis.delete(List.of(prefix + "{" + id + "}:events", prefix + "{" + id + "}:sequence", prefix + "{" + id + "}:bytes", prefix + "{" + id + "}:terminal"));
                }
                final long fixtureApp = app;
                new TransactionTemplate(new DataSourceTransactionManager(jdbc.getDataSource())).executeWithoutResult(ignored -> {
                    for (long id : ids) {
                        jdbc.update("DELETE FROM sys_ai_request_attempt WHERE request_id=?", id); jdbc.update("DELETE FROM sys_ai_request_stage WHERE request_id=?", id);
                    }
                    jdbc.update("DELETE FROM sys_ai_request WHERE app_id=?", fixtureApp);
                    var versions = jdbc.query("SELECT app_version_id FROM sys_ai_application_version WHERE app_id=?", (rs, index) -> rs.getLong(1), fixtureApp);
                    for (long version : versions) { jdbc.update("DELETE FROM sys_ai_application_reference WHERE app_version_id=?", version); jdbc.update("DELETE FROM sys_ai_application_cursor WHERE app_version_id=?", version); }
                    jdbc.update("UPDATE sys_ai_application SET published_version_id=NULL WHERE app_id=? AND entry_key=?", fixtureApp, entry);
                    jdbc.update("DELETE FROM sys_ai_application_version WHERE app_id=?", fixtureApp);
                    assertEquals(1, jdbc.update("DELETE FROM sys_ai_application WHERE app_id=? AND entry_key=?", fixtureApp, entry));
                });
                assertEquals(beforeApps, jdbc.queryForObject("SELECT COUNT(*) FROM sys_ai_application", Long.class));
                assertEquals(beforeRequests, jdbc.queryForObject("SELECT COUNT(*) FROM sys_ai_request", Long.class));
                report.put("cleanup", Map.of("exactAppId", Long.toString(app), "exactRequestIds", ids.stream().map(String::valueOf).toList(), "countsRestored", true, "redisPrefix", prefix));
            }
            Files.writeString(directory.resolve("report.json"), JSON.writerWithDefaultPrettyPrinter().writeValueAsString(report));
        }
    }

    private static DriverManagerDataSource source() { return new DriverManagerDataSource(URL, "ai_v04_b", Objects.requireNonNull(System.getenv("AI_V04_B_DB_PASSWORD"))); }
    private static TrustedActor principal(String tenant) { return new TrustedActor("900001", tenant, Set.of(), SERVICE); }
    private static AiRuntimeService.Submission submission(String nonce, String business, String text) { return new AiRuntimeService.Submission(new BusinessRef("qa.job", business), Map.of("user_request", text), nonce); }
    private static void preflight(JdbcTemplate jdbc, Map<String, Object> report) {
        assertEquals("aiv04_b_20261016", jdbc.queryForObject("SELECT DATABASE()", String.class));
        assertEquals(13319, jdbc.queryForObject("SELECT @@port", Integer.class));
        assertTrue(jdbc.queryForObject("SELECT CURRENT_USER()", String.class).startsWith("ai_v04_b@"));
        var migrations = jdbc.queryForList("SELECT version FROM flyway_schema_history WHERE success=1 AND version LIKE '20261016.%' ORDER BY installed_rank", String.class);
        assertEquals(List.of("20261016.180", "20261016.190", "20261016.200", "20261016.210", "20261016.220"), migrations);
        assertEquals(0, jdbc.queryForObject("SELECT COUNT(*) FROM flyway_schema_history WHERE success=0", Integer.class));
        assertEquals(5, jdbc.queryForObject("SELECT COUNT(*) FROM information_schema.columns WHERE table_schema=DATABASE() AND table_name='sys_ai_request_attempt' AND column_name IN ('provider_code','model_code','key_version','provider_request_id','output_token_limit')", Integer.class));
        report.put("mysql", jdbc.queryForObject("SELECT @@version", String.class)); report.put("schema", "aiv04_b_20261016"); report.put("migrations", migrations);
    }

    @Configuration(proxyBeanMethods = false) @EnableTransactionManagement(proxyTargetClass = true)
    static class Transactions {}

    private static final class Node implements AutoCloseable {
        final JdbcTemplate jdbc = new JdbcTemplate(source());
        final DataSourceTransactionManager manager = new DataSourceTransactionManager(jdbc.getDataSource());
        final AnnotationConfigApplicationContext transactions = new AnnotationConfigApplicationContext();
        final DefaultClientResources resources = DefaultClientResources.builder().ioThreadPoolSize(2).computationThreadPoolSize(2).build();
        final LettuceConnectionFactory connection;
        final StringRedisTemplate redis;
        final RedisAiRuntimeEvents events;
        final AiApplicationStore apps = new AiApplicationStore(jdbc, JSON);
        final AiRequestStore requests = new AiRequestStore(jdbc);
        final AiApplicationService versions;
        final AiRuntimeService runtime;
        final ControlledProvider provider;
        final TrustedActor actor;
        Node(String tenant, String prefix) {
            actor = principal(tenant);
            transactions.register(Transactions.class); transactions.registerBean(JdbcTemplate.class, () -> jdbc);
            transactions.registerBean(PlatformTransactionManager.class, () -> manager); transactions.registerBean(AiSharedModelCursor.class); transactions.refresh();
            var cursor = transactions.getBean(AiSharedModelCursor.class); assertTrue(AopUtils.isAopProxy(cursor));
            var config = new RedisStandaloneConfiguration("127.0.0.1", 16381); config.setPassword(RedisPassword.of(Objects.requireNonNull(System.getenv("AI_V04_REDIS_PASSWORD"))));
            connection = new LettuceConnectionFactory(config, LettuceClientConfiguration.builder().clientResources(resources).commandTimeout(Duration.ofSeconds(3)).shutdownTimeout(Duration.ZERO).build());
            connection.afterPropertiesSet(); connection.start(); redis = new StringRedisTemplate(connection); redis.afterPropertiesSet();
            events = new RedisAiRuntimeEvents(redis, JSON, prefix);
            provider = new ControlledProvider(tenant); var beans = new StaticListableBeanFactory(); beans.addBean("provider", provider); beans.addBean("template", new ControlledTemplate(tenant));
            var providers = beans.getBeanProvider(AiProviderRuntime.class); var templates = beans.getBeanProvider(AiTemplateRuntime.class);
            versions = new AiApplicationService(jdbc, JSON, apps, new JdbcAiApplicationReferences(jdbc, manager), providers, templates);
            var journal = new AiRequestJournal(jdbc, requests, manager); var lifecycle = new AiRequestLifecycle(requests, events, manager);
            var worker = new AiRequestExecutor(requests, apps, journal, lifecycle, cursor, events, providers, templates, jdbc, manager);
            runtime = new AiRuntimeService(apps, versions, requests, journal, lifecycle, worker, templates, jdbc, JSON, manager, new SecretCipher(() -> System.getenv("AI_V04_QA_MASTER")));
        }
        AiRequestStore.Request await(long id, boolean running) throws Exception {
            long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(12);
            do {
                var state = runtime.status(id, actor);
                if (running ? state.status() == AiRequestStatus.RUNNING && jdbc.queryForObject("SELECT COUNT(*) FROM sys_ai_request_attempt WHERE request_id=?", Integer.class, id) > 0 : state.status().terminal()) return state;
                Thread.sleep(10);
            } while (System.nanoTime() < deadline);
            throw new IllegalStateException("Controlled QA request did not reach expected state");
        }
        @Override public void close() { runtime.stop(); connection.destroy(); resources.shutdown(); transactions.close(); }
    }

    private static final class ControlledTemplate implements AiTemplateRuntime {
        final String tenant;
        ControlledTemplate(String tenant) { this.tenant = tenant; }
        @Override public PublishedTemplate publishedSnapshot(TemplateVersionRef version, Invocation invocation, InvocationControl control) { assertEquals(principal(tenant), invocation.actor()); control.check(); return new PublishedTemplate(version, List.of(new Variable("user_request", VariableType.STRING, true, false)), List.of()); }
        @Override public Validation validateBinding(TemplateVersionRef version, Set<String> inputs, Invocation invocation, InvocationControl control) { publishedSnapshot(version, invocation, control); return new Validation(List.of()); }
        @Override public RenderedTemplate renderPublished(RenderRequest request, InvocationControl control) { publishedSnapshot(request.version(), request.invocation(), control); return new RenderedTemplate(request.version(), List.of(new AiMessage(MessageRole.USER, request.inputs().values().get("user_request").toString())), List.of(), List.of()); }
    }
    private static final class ControlledProvider implements AiProviderRuntime {
        final String tenant; final AtomicInteger calls = new AtomicInteger();
        ControlledProvider(String tenant) { this.tenant = tenant; }
        @Override public List<AvailableModel> availableModels(Requirements requirements) { throw new AssertionError("Not a catalog acceptance"); }
        @Override public Validation validateBindings(List<ModelBinding> models, Requirements requirements) { return new Validation(List.of()); }
        @Override public List<Target> resolveTargets(ModelBinding binding, Set<KeyRef> attempted, InvocationControl control) {
            control.check(); int size = binding.model().modelRef().equals(MODELS.get(0)) ? 3 : 1; var targets = new ArrayList<Target>();
            for (int key = 1; key <= size; key++) { var ref = new KeyRef(binding.model().modelRef() + key, "95001"); if (!attempted.contains(ref)) targets.add(new Target(binding.model(), ref, "QA_CONTROLLED", "controlled-" + binding.model().modelRef(), "masked-qa-" + key, new Capabilities(Set.of(OutputFormat.TEXT), true, false))); }
            return targets;
        }
        @Override public ExecutionOutcome execute(ExecutionRequest request, StreamObserver observer, InvocationControl control) {
            assertEquals(principal(tenant), request.invocation().actor()); calls.incrementAndGet();
            try { while (request.messages().get(0).content().equals("qa-hold")) { control.check(); Thread.sleep(10); } }
            catch (InterruptedException interrupted) { Thread.currentThread().interrupt(); throw new AiRuntimeException(ErrorCode.CANCELLED); }
            control.check(); observer.onDelta("controlled neutral output");
            return new ExecutionOutcome(ExecutionStatus.SUCCEEDED, null, false, true, new Usage(1L, 1L, 2L), "qa-" + UUID.randomUUID(), 1);
        }
    }

    public static void main(String[] args) throws Exception {
        long app = Long.parseLong(args[0]); String tenant = args[1], prefix = args[2];
        try (var node = new Node(tenant, prefix); var reader = new BufferedReader(new InputStreamReader(System.in, StandardCharsets.UTF_8))) {
            for (String line; (line = reader.readLine()) != null;) {
                var command = JSON.readTree(line); String operation = command.path("op").asText(); if (operation.equals("stop")) return;
                var result = new LinkedHashMap<String, Object>();
                try {
                    long id;
                    if (operation.equals("submit") || operation.equals("hold")) {
                        var accepted = node.runtime.submit(app, submission(command.path("nonce").asText(), command.path("business").asText(), command.path("text").asText()), node.actor);
                        id = Long.parseLong(accepted.requestId()); var state = node.await(id, operation.equals("hold"));
                        result.put("requestId", accepted.requestId()); result.put("status", state.status().name());
                        result.put("modelRef", node.jdbc.queryForObject("SELECT model_ref FROM sys_ai_request_attempt WHERE request_id=? AND attempt_number=1", String.class, id));
                    } else {
                        id = Long.parseLong(command.path("requestId").asText());
                        if (operation.equals("events")) {
                            node.runtime.authorizeEvents(node.runtime.status(id, node.actor), node.actor);
                            var events = node.events.after(Long.toString(id), 0); assertFalse(events.isEmpty());
                            result.put("completed", events.stream().anyMatch(event -> event.name() == AiRuntimeEvents.Name.COMPLETED));
                            result.put("eventCount", events.size()); result.put("ttl", node.redis.getExpire(prefix + "{" + id + "}:events"));
                        } else if (operation.equals("cancel")) result.put("status", node.runtime.cancel(id, node.actor).status().name());
                        else if (operation.equals("settled")) {
                            long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(8);
                            while (node.jdbc.queryForObject("SELECT COUNT(*) FROM sys_ai_request_attempt WHERE request_id=? AND status='RUNNING'", Integer.class, id) > 0 && System.nanoTime() < deadline) Thread.sleep(10);
                            assertEquals(0, node.jdbc.queryForObject("SELECT COUNT(*) FROM sys_ai_request_attempt WHERE request_id=? AND status='RUNNING'", Integer.class, id));
                            var events = node.events.after(Long.toString(id), 0); assertTrue(events.stream().anyMatch(event -> event.name() == AiRuntimeEvents.Name.CANCELLED));
                            assertFalse(events.stream().anyMatch(event -> event.name() == AiRuntimeEvents.Name.COMPLETED)); result.put("status", node.runtime.status(id, node.actor).status().name());
                        } else if (operation.equals("denials")) {
                            int calls = node.provider.calls.get();
                            for (var actor : List.of(new TrustedActor("900001", tenant, Set.of(), null), new TrustedActor("900002", tenant, Set.of(), "qa:other")))
                                assertEquals(ErrorCode.FORBIDDEN, assertThrows(AiRuntimeException.class, () -> node.runtime.submit(app, submission(UUID.randomUUID().toString(), "denied", "neutral"), actor)).code());
                            assertEquals(ErrorCode.NOT_FOUND, assertThrows(AiRuntimeException.class, () -> node.runtime.status(id, principal("qa-other-tenant"))).code());
                            assertEquals(calls, node.provider.calls.get()); result.put("humanAndUnapprovedServiceRejected", true); result.put("crossTenantReadRejected", true);
                        } else throw new IllegalArgumentException("Unknown private QA operation");
                    }
                    result.put("pid", ProcessHandle.current().pid()); result.put("providerCalls", node.provider.calls.get());
                } catch (Throwable failure) { result.put("error", failure.getClass().getSimpleName()); }
                System.out.println("QA_RESULT:" + JSON.writeValueAsString(result)); System.out.flush();
            }
        }
    }

    private static final class Worker implements AutoCloseable {
        final Process process; final BufferedWriter commands; final BlockingQueue<JsonNode> results = new LinkedBlockingQueue<>();
        Worker(Path directory, String label, long app, String tenant, String prefix) throws Exception {
            Path arguments = directory.resolve("worker-" + label + ".args");
            String classpath = System.getProperty("surefire.test.class.path", System.getProperty("java.class.path")).replace('\\', '/');
            Files.writeString(arguments, "-Xmx256m\n-XX:ActiveProcessorCount=2\n-cp\n\"" + classpath + "\"\n" + AiDistributedRuntimeMySqlIT.class.getName() + "\n" + app + "\n" + tenant + "\n" + prefix + "\n");
            process = new ProcessBuilder(Path.of(System.getProperty("java.home"), "bin", "java.exe").toString(), "@" + arguments).redirectErrorStream(true).start();
            commands = new BufferedWriter(new OutputStreamWriter(process.getOutputStream(), StandardCharsets.UTF_8));
            Thread pump = new Thread(() -> {
                try (var output = new BufferedReader(new InputStreamReader(process.getInputStream(), StandardCharsets.UTF_8)); var log = Files.newBufferedWriter(directory.resolve("worker-" + label + ".log"))) {
                    for (String line; (line = output.readLine()) != null;) { log.write(line); log.newLine(); log.flush(); if (line.startsWith("QA_RESULT:")) results.add(JSON.readTree(line.substring(10))); }
                } catch (IOException failure) { /* Parent timeout plus private log identifies worker failure. */ }
            }, "qa-worker-output-" + label); pump.setDaemon(true); pump.start();
        }
        JsonNode command(Map<String, Object> command) throws Exception {
            commands.write(JSON.writeValueAsString(command)); commands.newLine(); commands.flush();
            var result = results.poll(20, TimeUnit.SECONDS); assertNotNull(result, "QA worker response timeout; inspect private worker log"); assertFalse(result.has("error"), result.toString()); return result;
        }
        @Override public void close() throws Exception {
            try { commands.write("{\"op\":\"stop\"}"); commands.newLine(); commands.flush(); } catch (IOException ignored) { }
            if (!process.waitFor(10, TimeUnit.SECONDS)) { process.destroyForcibly(); process.waitFor(5, TimeUnit.SECONDS); }
            commands.close();
        }
    }
}
