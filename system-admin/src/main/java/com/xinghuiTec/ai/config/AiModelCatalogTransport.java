package com.xinghuiTec.ai.config;

import com.fasterxml.jackson.core.JsonFactory;
import com.fasterxml.jackson.core.StreamReadConstraints;
import com.fasterxml.jackson.core.StreamReadFeature;
import com.fasterxml.jackson.databind.DeserializationFeature;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.xinghuiTec.ai.model.AiModelConfigRules;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.net.*;
import java.net.http.*;
import java.nio.ByteBuffer;
import java.time.Duration;
import java.util.*;
import java.util.concurrent.*;

import static com.xinghuiTec.ai.config.AiModelCatalogDtos.Model;

/** Read-only official catalog transport. No generation, retries, redirects or arbitrary URLs. */
@Component
public class AiModelCatalogTransport {
    static final int MAX_BYTES = 4 * 1024 * 1024;
    private static final ObjectMapper JSON = new ObjectMapper(JsonFactory.builder()
            .streamReadConstraints(StreamReadConstraints.builder().maxNestingDepth(32)
                    .maxStringLength(262144).maxNumberLength(32).build())
            .enable(StreamReadFeature.STRICT_DUPLICATE_DETECTION).build())
            .enable(DeserializationFeature.FAIL_ON_TRAILING_TOKENS);
    private static final ExecutorService DNS = new ThreadPoolExecutor(2, 2, 0, TimeUnit.MILLISECONDS,
            new ArrayBlockingQueue<>(4), r -> {
        var thread = new Thread(r, "ai-catalog-dns");
        thread.setDaemon(true);
        return thread;
    }, new ThreadPoolExecutor.AbortPolicy());
    private final Semaphore concurrent = new Semaphore(4);
    private final HttpClient client;
    private final AiModelConfigRules addresses;
    private final URI localBase;
    private final boolean checkDns;
    private final Duration timeout;

    @Autowired
    public AiModelCatalogTransport(AiModelConfigRules addresses) {
        this.addresses = addresses;
        localBase = null;
        timeout = Duration.ofSeconds(15);
        var builder = HttpClient.newBuilder().followRedirects(HttpClient.Redirect.NEVER)
                .connectTimeout(Duration.ofSeconds(5));
        String proxy = System.getenv("OPENROUTER_HTTP_PROXY");
        checkDns = proxy == null || proxy.isBlank();
        if (!checkDns) {
            URI uri;
            try { uri = URI.create(proxy.trim()); }
            catch (RuntimeException invalid) { throw new IllegalStateException("OPENROUTER_HTTP_PROXY is invalid"); }
            if (!"http".equals(uri.getScheme()) || uri.getHost() == null || uri.getPort() < 1
                    || uri.getPort() > 65535 || uri.getRawUserInfo() != null || uri.getRawQuery() != null
                    || uri.getRawFragment() != null || !uri.getPath().isEmpty())
                throw new IllegalStateException("OPENROUTER_HTTP_PROXY is invalid");
            builder.proxy(ProxySelector.of(new InetSocketAddress(uri.getHost(), uri.getPort())));
        } else builder.proxy(new ProxySelector() {
            public List<Proxy> select(URI uri) { return List.of(Proxy.NO_PROXY); }
            public void connectFailed(URI uri, SocketAddress address, IOException error) {}
        });
        client = builder.build();
    }

    /** Local HTTP mock seam, unavailable through Spring or HTTP request input. */
    AiModelCatalogTransport(HttpClient client, URI local, Duration timeout) {
        if (!"http".equals(local.getScheme()) || !"127.0.0.1".equals(local.getHost())
                || local.getPort() < 1 || local.getRawUserInfo() != null || local.getRawQuery() != null
                || local.getRawFragment() != null || !local.getPath().isEmpty()
                || client.followRedirects() != HttpClient.Redirect.NEVER)
            throw new IllegalArgumentException("Local mock endpoint required");
        this.client = client;
        this.localBase = local;
        this.addresses = null;
        this.checkDns = false;
        this.timeout = timeout;
    }

    record Catalog(List<Model> models, int scannedCount, int filteredCount, Map<String, Integer> filterReasons) {}

    Catalog fetch(String provider, String secret) {
        final AiProviderPresets.Preset preset;
        try { preset = AiProviderPresets.require(provider); }
        catch (AiConfigurationException invalid) { throw failure(422, "PROVIDER_UNSUPPORTED"); }
        String destination = secret == null ? preset.publicModelsEndpoint() : preset.modelsEndpoint();
        if (destination == null) throw failure(400, "KEY_REQUIRED");
        URI official = URI.create(destination);
        URI endpoint = localBase == null ? official : localBase.resolve(official.getRawPath()+(official.getRawQuery()==null?"":"?"+official.getRawQuery()));
        if (secret != null && (secret.isBlank() || secret.length() > 8192
                || secret.chars().anyMatch(c -> c < 32 || c == 127)))
            throw failure(503, "CREDENTIAL_UNAVAILABLE");
        if (!concurrent.tryAcquire()) throw failure(503, "BUSY");
        CompletableFuture<?> pending = null;
        long deadline = System.nanoTime() + timeout.toNanos();
        try {
            if (checkDns) {
                var resolved = DNS.submit(() -> InetAddress.getAllByName(official.getHost()));
                try {
                    for (var address : resolved.get(Math.min(3000, timeout.toMillis()), TimeUnit.MILLISECONDS)) {
                        String host = address.getHostAddress();
                        addresses.endpointUrl("https://" + (host.contains(":") ? "[" + host + "]" : host));
                    }
                } finally { resolved.cancel(true); }
            }
            var request = HttpRequest.newBuilder(endpoint)
                    .timeout(timeout).header("Accept", "application/json").GET();
            if (secret != null) request.header("Authorization", "Bearer " + secret);
            var responseFuture = client.sendAsync(request.build(), info -> new LimitedBody());
            pending = responseFuture;
            long remaining = deadline - System.nanoTime();
            if (remaining <= 0) throw failure(504, "TIMEOUT");
            var response = responseFuture.get(remaining, TimeUnit.NANOSECONDS);
            int status = response.statusCode();
            if (status == 401) throw failure(502, "UPSTREAM_UNAUTHORIZED");
            if (status == 403) throw failure(502, "UPSTREAM_FORBIDDEN");
            if (status == 429) throw failure(429, "RATE_LIMITED");
            if (status >= 300 && status < 400) throw failure(502, "UPSTREAM_REDIRECT");
            if (status != 200) throw failure(502, "UPSTREAM_FAILED");
            String type = response.headers().firstValue("Content-Type").orElse("");
            if (!"application/json".equals(type.split(";", 2)[0].strip().toLowerCase(Locale.ROOT)))
                throw failure(502, "UPSTREAM_INVALID");
            return parse(response.body(), secret, provider);
        } catch (AiModelCatalogException safe) { throw safe; }
        catch (TimeoutException timeoutError) { throw failure(504, "TIMEOUT"); }
        catch (InterruptedException interrupted) {
            Thread.currentThread().interrupt();
            throw failure(503, "NETWORK_UNAVAILABLE");
        } catch (ExecutionException upstream) {
            if (upstream.getCause() instanceof AiModelCatalogException safe) throw safe;
            if (upstream.getCause() instanceof HttpTimeoutException) throw failure(504, "TIMEOUT");
            throw failure(503, "NETWORK_UNAVAILABLE");
        } catch (Exception invalid) { throw failure(503, "NETWORK_UNAVAILABLE"); }
        finally {
            if (pending != null) pending.cancel(true);
            concurrent.release();
        }
    }

    private Catalog parse(byte[] body, String secret, String provider) {
        try {
            JsonNode root = JSON.readTree(body);
            var data = root == null ? null : root.get("data");
            if (data == null || !data.isArray() || data.size() > 5000) throw failure(502, "UPSTREAM_INVALID");
            var result = new TreeMap<String, Model>();
            var reasons = new TreeMap<String, Integer>();
            for (var item : data) {
                try {
                    // Match the configuration/runtime code grammar; routing aliases are not executable here.
                    String code = text(item.get("id"), 262144, secret);
                    if (!code.matches("[A-Za-z0-9][A-Za-z0-9._:/-]{0,127}")) {
                        reasons.merge("UNSUPPORTED_MODEL_CODE", 1, Integer::sum);
                        continue;
                    }
                    boolean deepseek = "deepseek".equals(provider);
                    var architecture = deepseek ? item : item.path("architecture");
                    var input = tokens(architecture.get("input_modalities"), secret);
                    var output = tokens(architecture.get("output_modalities"), secret);
                    if (architecture.hasNonNull("output_modalities") && !output.contains("text")) {
                        reasons.merge("NON_TEXT_OUTPUT", 1, Integer::sum);
                        continue;
                    }
                    String name = item.hasNonNull("name") ? text(item.get("name"), 256, secret) : code;
                    var model = new Model(code, name, positive(item.get(deepseek ? "context_window" : "context_length")),
                            positive(deepseek ? item.get("max_output_tokens") : item.path("top_provider").get("max_completion_tokens")),
                            input, output, tokens(item.get("supported_parameters"), secret));
                    if (result.putIfAbsent(code, model) != null)
                        reasons.merge("DUPLICATE_MODEL_CODE", 1, Integer::sum);
                } catch (AiModelCatalogException invalidRow) {
                    // A new unsupported metadata shape must not discard the remaining catalog.
                    reasons.merge("INVALID_METADATA", 1, Integer::sum);
                }
            }
            return new Catalog(List.copyOf(result.values()), data.size(),
                    reasons.values().stream().mapToInt(Integer::intValue).sum(), Map.copyOf(reasons));
        } catch (AiModelCatalogException safe) { throw safe; }
        catch (Exception invalid) { throw failure(502, "UPSTREAM_INVALID"); }
    }

    private String text(JsonNode node, int maximum, String secret) {
        if (node == null || !node.isTextual()) throw failure(502, "UPSTREAM_INVALID");
        String value = node.textValue();
        if (value.chars().anyMatch(Character::isISOControl) || (secret != null && value.contains(secret)))
            throw new UnsafeMetadata();
        if (value.isBlank() || value.length() > maximum) throw failure(502, "UPSTREAM_INVALID");
        return value;
    }

    /** Security violations remain fatal, unlike unsupported individual metadata shapes. */
    private static final class UnsafeMetadata extends RuntimeException {}

    private List<String> tokens(JsonNode node, String secret) {
        if (node == null || node.isNull()) return List.of();
        if (!node.isArray() || node.size() > 64) throw failure(502, "UPSTREAM_INVALID");
        var values = new TreeSet<String>();
        for (var token : node) {
            String value = text(token, 64, secret);
            if (!value.matches("[A-Za-z0-9_/-]+")) throw failure(502, "UPSTREAM_INVALID");
            values.add(value);
        }
        return List.copyOf(values);
    }

    private Long positive(JsonNode node) {
        return node != null && node.isIntegralNumber() && node.canConvertToLong() && node.longValue() > 0
                ? node.longValue() : null;
    }

    static AiModelCatalogException failure(int status, String code) { return new AiModelCatalogException(status, code); }

    private static final class LimitedBody implements HttpResponse.BodySubscriber<byte[]> {
        private final CompletableFuture<byte[]> result = new CompletableFuture<>();
        private final ByteArrayOutputStream bytes = new ByteArrayOutputStream();
        private Flow.Subscription subscription;
        public CompletionStage<byte[]> getBody() { return result; }
        public void onSubscribe(Flow.Subscription subscription) {
            this.subscription = subscription;
            subscription.request(1);
        }
        public void onNext(List<ByteBuffer> buffers) {
            for (var buffer : buffers) {
                if (buffer.remaining() > MAX_BYTES - bytes.size()) {
                    subscription.cancel();
                    result.completeExceptionally(failure(502, "RESPONSE_LIMIT"));
                    return;
                }
                byte[] chunk = new byte[buffer.remaining()];
                buffer.get(chunk);
                bytes.writeBytes(chunk);
            }
            subscription.request(1);
        }
        public void onError(Throwable error) { result.completeExceptionally(error); }
        public void onComplete() { result.complete(bytes.toByteArray()); }
    }
}
