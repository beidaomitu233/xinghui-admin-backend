package com.xinghuiTec.ai.application;

import ch.qos.logback.classic.Logger;
import ch.qos.logback.classic.spi.ILoggingEvent;
import ch.qos.logback.core.read.ListAppender;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.xinghuiTec.ai.contract.*;
import com.xinghuiTec.ai.contract.AiTypes.*;
import com.xinghuiTec.config.SecretCipher;
import com.xinghuiTec.config.SecurityConfig;
import com.xinghuiTec.config.JacksonConfig;
import com.xinghuiTec.constants.RedisConstants;
import com.xinghuiTec.controller.AiRuntimeController;
import com.xinghuiTec.domain.entity.LoginUser;
import com.xinghuiTec.domain.entity.SysUser;
import com.xinghuiTec.filter.AuthenticationEntryPointImpl;
import com.xinghuiTec.filter.LoginFilter;
import com.xinghuiTec.utils.JwtUtil;
import com.xinghuiTec.utils.RedisCacheUtils;
import jakarta.servlet.DispatcherType;
import jakarta.servlet.Filter;
import org.junit.jupiter.api.*;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.config.BeanFactoryPostProcessor;
import org.springframework.beans.factory.support.StaticListableBeanFactory;
import org.springframework.boot.builder.SpringApplicationBuilder;
import org.springframework.boot.web.embedded.tomcat.TomcatServletWebServerFactory;
import org.springframework.boot.web.servlet.DelegatingFilterProxyRegistrationBean;
import org.springframework.boot.web.servlet.FilterRegistrationBean;
import org.springframework.boot.web.servlet.ServletRegistrationBean;
import org.springframework.boot.web.servlet.context.ServletWebServerApplicationContext;
import org.springframework.context.annotation.*;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DataSourceTransactionManager;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.transaction.support.TransactionTemplate;
import org.springframework.web.servlet.DispatcherServlet;
import org.springframework.web.servlet.config.annotation.EnableWebMvc;
import org.springframework.web.servlet.config.annotation.WebMvcConfigurer;
import org.springframework.boot.autoconfigure.jackson.Jackson2ObjectMapperBuilderCustomizer;
import org.springframework.http.converter.HttpMessageConverter;
import org.springframework.http.converter.json.Jackson2ObjectMapperBuilder;
import org.springframework.http.converter.json.MappingJackson2HttpMessageConverter;

import java.net.URI;
import java.net.http.*;
import java.time.Duration;
import java.time.Instant;
import java.util.*;
import java.util.concurrent.*;

import static org.junit.jupiter.api.Assertions.*;

/** Real loopback Tomcat, production SecurityConfig/LoginFilter/controller/streams and real signed JWTs.
 * Only session storage, H2 persistence and transient event data are test fixtures; no provider is called. */
class AiSseSecurityServerTest {
    private static final Sessions SESSIONS = new Sessions();
    private static final Queue<Dispatch> DISPATCHES = new ConcurrentLinkedQueue<>();
    private static final ListAppender<ILoggingEvent> LOGS = new ListAppender<>();
    private static ServletWebServerApplicationContext server;
    private static Fixture fixture;
    private static String base;
    private static String ownerToken;
    private static final HttpClient CLIENT = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(3)).build();
    private record Dispatch(String path, DispatcherType type, Long subject) {}

    @BeforeAll static void start() {
        LOGS.start(); ((Logger) LoggerFactory.getLogger(Logger.ROOT_LOGGER_NAME)).addAppender(LOGS);
        server = (ServletWebServerApplicationContext) new SpringApplicationBuilder(Server.class)
                .properties("server.port=0", "spring.main.banner-mode=off", "spring.main.register-shutdown-hook=false")
                .run();
        base = "http://127.0.0.1:" + server.getWebServer().getPort();
        fixture = server.getBean(Fixture.class);
        ownerToken = session(101, "platform", List.of());
    }

    @AfterAll static void stop() {
        if (server != null) server.close();
        ((Logger) LoggerFactory.getLogger(Logger.ROOT_LOGGER_NAME)).detachAppender(LOGS); LOGS.stop();
        SESSIONS.rows.clear(); DISPATCHES.clear();
    }

    @Test void completedAndFailedTerminalReplayHaveAuthenticatedAsyncDispatchAndCleanHttpEof() throws Exception {
        var unsafeSequence = new AiRuntimeEvents.Event(AiRuntimeEvents.Name.USAGE, "1", 9_007_199_254_740_992L, AiRequestStatus.RUNNING, null, null, null, null);
        assertEquals(ErrorCode.INVALID_RESPONSE, assertThrows(AiRuntimeException.class, () -> AiRuntimeWire.event(unsafeSequence)).code());
        var safeMaximum = AiRuntimeWire.event(new AiRuntimeEvents.Event(AiRuntimeEvents.Name.USAGE, "1", 1, AiRequestStatus.RUNNING, null, 9_007_199_254_740_991L, 0L, null));
        assertTrue(safeMaximum.path("inputTokens").isIntegralNumber()); assertEquals(9_007_199_254_740_991L, safeMaximum.path("inputTokens").longValue());
        for (AiRequestStatus terminal : List.of(AiRequestStatus.SUCCEEDED, AiRequestStatus.FAILED)) {
            var measured = terminal == AiRequestStatus.SUCCEEDED ? new Usage(0L, 17L, 17L) : new Usage(9_007_199_254_740_992L, null, null);
            long id = fixture.terminal(terminal, measured);
            String path = "/ai/requests/" + id + "/events";
            var state = get("/ai/requests/" + id, ownerToken);
            assertEquals(200, state.statusCode());
            assertEquals(terminal.name(), new ObjectMapper().readTree(state.body()).path("data").path("status").asText());
            assertTrue(new ObjectMapper().readTree(state.body()).path("data").path("inputTokens").isTextual(), "Production Long serializer must actually be installed and remain unchanged");
            int reads = SESSIONS.reads.get();
            var response = get(path, ownerToken); // real chunked HTTP body must terminate normally
            assertEquals(200, response.statusCode());
            assertTrue(response.headers().firstValue("content-type").orElse("").startsWith("text/event-stream"));
            String body = response.body().replace("\r\n", "\n");
            assertTrue(body.contains("event:accepted\n"), body);
            assertTrue(body.contains(terminal == AiRequestStatus.SUCCEEDED ? "event:completed\n" : "event:failed\n"), body);
            assertTrue(body.endsWith("\n\n"));
            assertEquals(1, body.split(terminal == AiRequestStatus.SUCCEEDED ? "event:completed" : "event:failed", -1).length - 1);
            long sequence = 0;
            boolean usageSeen = false;
            for (String line : body.split("\n")) {
                if (!line.startsWith("data:")) continue;
                var node = new ObjectMapper().readTree(line.substring(5));
                assertTrue(node.path("requestId").isTextual());
                assertEquals(Long.toString(id), node.path("requestId").textValue());
                assertTrue(node.path("sequence").isIntegralNumber(), "SSE sequence must be a JSON integer, never a numeric string");
                assertEquals(++sequence, node.path("sequence").longValue());
                if (node.path("name").textValue().equals("USAGE")) {
                    usageSeen = true;
                    if (terminal == AiRequestStatus.SUCCEEDED) {
                        assertTrue(node.path("inputTokens").isIntegralNumber()); assertEquals(0L, node.path("inputTokens").longValue());
                        assertTrue(node.path("outputTokens").isIntegralNumber()); assertEquals(17L, node.path("outputTokens").longValue());
                    } else {
                        assertTrue(node.path("inputTokens").isNull(), "Above JS safe integer must remain unknown on wire, never rounded");
                        assertTrue(node.path("outputTokens").isNull(), "Unknown usage must remain null");
                    }
                } else {
                    assertTrue(node.path("inputTokens").isNull()); assertTrue(node.path("outputTokens").isNull());
                }
            }
            assertTrue(usageSeen);
            assertTrue(DISPATCHES.stream().anyMatch(item -> item.path().equals(path) && item.type() == DispatcherType.ASYNC && Objects.equals(item.subject(), 101L)), "ASYNC must re-enter the real chain with the authenticated owner");
            assertTrue(SESSIONS.reads.get() >= reads + 2, "JWT/session must be checked for REQUEST and ASYNC");
            assertEquals(terminal, fixture.requests.owned(id, fixture.owner).status());
            assertEquals(401, get("/ai/requests/" + id, null).statusCode(), "completed dispatch cannot leak its identity to later requests");
        }
        assertTrue(LOGS.list.stream().noneMatch(event -> event.getLevel().isGreaterOrEqual(ch.qos.logback.classic.Level.ERROR)
                && (event.getFormattedMessage().contains("committed") || event.getFormattedMessage().contains("AccessDenied"))), "No committed-response security failure");
    }

    @Test void missingInvalidAndSupersededTokensCannotOpenTheSseEndpoint() throws Exception {
        long id = fixture.terminal(AiRequestStatus.SUCCEEDED);
        String path = "/ai/requests/" + id + "/events";
        assertEquals(401, get(path, null).statusCode());
        assertEquals(401, get(path, "invalid.signature.fixture").statusCode());
        String stale = session(102, "platform", List.of()); session(102, "platform", List.of());
        assertEquals(401, get(path, stale).statusCode());
        assertFalse(DISPATCHES.stream().anyMatch(item -> item.path().equals(path) && item.type() == DispatcherType.ASYNC));
    }

    @Test void anotherOwnerTenantOrAuditPermissionCannotReplayOrCancelTheOriginalRequest() throws Exception {
        long id = fixture.terminal(AiRequestStatus.FAILED);
        try {
            for (String token : List.of(session(103, "platform", List.of()), session(101, "other-tenant", List.of()),
                    session(105, "platform", List.of("ai:request:audit", "ai:request:query")))) {
                var denied = get("/ai/requests/" + id + "/events", token);
                assertEquals(404, denied.statusCode());
                assertFalse(denied.body().contains("event:"));
                var cancel = CLIENT.send(HttpRequest.newBuilder(URI.create(base + "/ai/requests/" + id + "/cancel"))
                        .header("Authorization", token).timeout(Duration.ofSeconds(5)).POST(HttpRequest.BodyPublishers.noBody()).build(), HttpResponse.BodyHandlers.ofString());
                assertEquals(404, cancel.statusCode());
            }
        } finally {
            ownerToken = session(101, "platform", List.of());
        }
        assertEquals(AiRequestStatus.FAILED, fixture.requests.owned(id, fixture.owner).status());
    }

    private static HttpResponse<String> get(String path, String token) throws Exception {
        var request = HttpRequest.newBuilder(URI.create(base + path)).timeout(Duration.ofSeconds(10)).GET();
        if (token != null) request.header("Authorization", token);
        return CLIENT.send(request.build(), HttpResponse.BodyHandlers.ofString());
    }

    private static String session(long id, String tenant, List<String> permissions) {
        var user = new SysUser(); user.setUserId(id); user.setTenantId(tenant); user.setStatus(1);
        var login = new LoginUser(user, permissions); login.setTenantId(tenant);
        String token = JwtUtil.createJWT(Long.toString(id), tenant); login.setToken(token);
        SESSIONS.rows.put(RedisConstants.ADMIN_LOGIN_PREFIX + id, login); return token;
    }

    private static final class Sessions extends RedisCacheUtils {
        final Map<String, LoginUser> rows = new ConcurrentHashMap<>();
        final java.util.concurrent.atomic.AtomicInteger reads = new java.util.concurrent.atomic.AtomicInteger();
        @Override @SuppressWarnings("unchecked") public <T> T getCacheObject(String key) { reads.incrementAndGet(); return (T) rows.get(key); }
    }

    @Configuration(proxyBeanMethods = false) @EnableWebMvc
    @Import({SecurityConfig.class, LoginFilter.class, AuthenticationEntryPointImpl.class, JacksonConfig.class})
    static class Server {
        @Bean static BeanFactoryPostProcessor sessionFixture() { return factory -> factory.registerSingleton("redisCacheUtils", SESSIONS); }
        @Bean TomcatServletWebServerFactory webServer() { var factory = new TomcatServletWebServerFactory(0); factory.setAddress(java.net.InetAddress.getLoopbackAddress()); return factory; }
        @Bean DispatcherServlet dispatcherServlet() { return new DispatcherServlet(); }
        // This minimal server does not enable Boot auto-configuration. Apply the actual production
        // customizer to every real MVC Jackson converter as Boot's Jackson auto-configuration does.
        @Bean WebMvcConfigurer productionJson(Jackson2ObjectMapperBuilderCustomizer customizer) {
            return new WebMvcConfigurer() {
                @Override public void extendMessageConverters(List<HttpMessageConverter<?>> converters) {
                    for (var converter : converters) if (converter instanceof MappingJackson2HttpMessageConverter jackson) {
                        var builder = Jackson2ObjectMapperBuilder.json(); customizer.customize(builder); jackson.setObjectMapper(builder.build());
                    }
                }
            };
        }
        @Bean ServletRegistrationBean<DispatcherServlet> dispatcher(DispatcherServlet servlet) { var bean = new ServletRegistrationBean<>(servlet, "/"); bean.setAsyncSupported(true); return bean; }
        @Bean DelegatingFilterProxyRegistrationBean securityRegistration() {
            var bean = new DelegatingFilterProxyRegistrationBean("springSecurityFilterChain");
            bean.setDispatcherTypes(DispatcherType.REQUEST, DispatcherType.ASYNC, DispatcherType.ERROR); bean.setAsyncSupported(true); bean.setOrder(-100); return bean;
        }
        @Bean FilterRegistrationBean<Filter> dispatchEvidence() {
            Filter observer = (request, response, chain) -> {
                var http = (jakarta.servlet.http.HttpServletRequest) request;
                var authentication = SecurityContextHolder.getContext().getAuthentication();
                Long subject = authentication != null && authentication.getPrincipal() instanceof LoginUser login ? login.getUser().getUserId() : null;
                DISPATCHES.add(new Dispatch(http.getRequestURI(), http.getDispatcherType(), subject)); chain.doFilter(request, response);
            };
            var bean = new FilterRegistrationBean<>(observer); bean.setOrder(-99); bean.setAsyncSupported(true);
            bean.setDispatcherTypes(DispatcherType.REQUEST, DispatcherType.ASYNC); return bean;
        }
        @Bean Fixture fixture() throws Exception { return new Fixture(); }
        @Bean AiRuntimeService runtime(Fixture fixture) { return fixture.runtime; }
        @Bean AiRequestStreams streams(Fixture fixture) { return new AiRequestStreams(fixture.runtime, fixture.events, fixture.lifecycle); }
        @Bean AiRuntimeController controller(AiRuntimeService runtime, AiRequestStreams streams) { return new AiRuntimeController(runtime, new AiInvocationIdentity(), new AiRuntimeBodyCodec(), streams, null); }
    }

    private static final class Fixture {
        final AiRequestStore.Owner owner = new AiRequestStore.Owner("101", "platform");
        final AiRequestStore requests;
        final AiRuntimeService runtime;
        final AiRequestLifecycle lifecycle;
        final Buffer events = new Buffer();
        final TransactionTemplate transaction;
        final long app;
        Fixture() throws Exception {
            var jdbc = new JdbcTemplate(AiApplicationTestDatabase.create());
            var manager = new DataSourceTransactionManager(jdbc.getDataSource()); transaction = new TransactionTemplate(manager);
            var mapper = new ObjectMapper().findAndRegisterModules(); var apps = new AiApplicationStore(jdbc, mapper);
            requests = new AiRequestStore(jdbc); lifecycle = new AiRequestLifecycle(requests, events, manager);
            var beans = new StaticListableBeanFactory(); beans.addBean("template", new Template());
            var providers = beans.getBeanProvider(AiProviderRuntime.class); var templates = beans.getBeanProvider(AiTemplateRuntime.class);
            var versions = new AiApplicationService(jdbc, mapper, apps, new JdbcAiApplicationReferences(jdbc, manager), providers, templates);
            // No provider bean, no worker and no credential: any accidental model execution fails closed.
            runtime = new AiRuntimeService(apps, versions, requests, new AiRequestJournal(jdbc, requests, manager), lifecycle, null,
                    templates, jdbc, mapper, manager, new SecretCipher(() -> null));
            var policy = new AiApplicationPolicy(AiApplicationPolicy.ModelMode.FIXED, List.of(), false, new TemplateVersionRef("11", "12"),
                    Set.of(), new Requirements(OutputFormat.TEXT, true, false), AiApplicationPolicy.Scope.FRONTEND, Set.of("101"), Set.of(), null, 30, 2, 2, 128);
            app = Long.parseLong(apps.create(new AiApplicationStore.Draft("SSE fixture", "HTTP replay only", "test.sse", policy), 101));
        }
        long terminal(AiRequestStatus status) {
            return terminal(status, null);
        }
        long terminal(AiRequestStatus status, Usage usage) {
            Instant now = Instant.now();
            var accepted = transaction.execute(ignored -> requests.accept(new AiRequestStore.Admission(app, null, 1, AiRequestStore.Kind.TEST,
                    owner, "test.sse", "fixture", UUID.randomUUID().toString(), "a".repeat(64), now.plusSeconds(30)), now));
            long id = Long.parseLong(accepted.request().requestId()); lifecycle.accepted(id, owner); requests.start(id, now);
            if (status == AiRequestStatus.SUCCEEDED) lifecycle.delta(id, owner, "neutral replay fixture");
            lifecycle.finish(id, owner, status, status == AiRequestStatus.SUCCEEDED ? null : ErrorCode.OUTPUT_LIMIT, usage); return id;
        }
    }
    private static final class Template implements AiTemplateRuntime {
        @Override public Validation validateBinding(TemplateVersionRef version, Set<String> names, Invocation invocation, InvocationControl control) { control.check(); return new Validation(List.of()); }
        @Override public PublishedTemplate publishedSnapshot(TemplateVersionRef version, Invocation invocation, InvocationControl control) { throw new AssertionError("Replay cannot publish or render"); }
        @Override public RenderedTemplate renderPublished(RenderRequest request, InvocationControl control) { throw new AssertionError("Replay cannot render or call a provider"); }
    }
    private static final class Buffer implements AiRuntimeEvents {
        final Map<String, List<Event>> rows = new HashMap<>();
        @Override public synchronized Event append(Event event) { var list = rows.computeIfAbsent(event.requestId(), ignored -> new ArrayList<>()); var value = event.sequenced(list.size() + 1L); list.add(value); return value; }
        @Override public synchronized List<Event> after(String id, long sequence) { return rows.getOrDefault(id, List.of()).stream().filter(event -> event.sequence() > sequence).toList(); }
    }
}
