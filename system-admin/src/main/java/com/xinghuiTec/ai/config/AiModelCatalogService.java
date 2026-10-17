package com.xinghuiTec.ai.config;

import org.springframework.stereotype.Service;
import java.time.Instant;
import java.util.*;
import static com.xinghuiTec.ai.config.AiModelCatalogDtos.*;
import static com.xinghuiTec.ai.config.AiModelCatalogTransport.failure;

/** Uses the saved draft's exact key membership; no writes, persistence or credential store. */
@Service
public class AiModelCatalogService {
    private final AiConfigurationService configs;
    private final AiModelCatalogTransport transport;

    public AiModelCatalogService(AiConfigurationService configs, AiModelCatalogTransport transport) {
        this.configs = configs;
        this.transport = transport;
    }

    public Page catalog(String search, int pageNum, int pageSize) {
        String query = query(search, pageNum, pageSize);
        return page(transport.fetch("openrouter", null), query, pageNum, pageSize, "PUBLIC", "openrouter", null, null, null);
    }

    public Page draftScan(DraftScan request) {
        if (request == null || request.apiKey() == null || request.apiKey().isBlank())
            throw failure(400, "INVALID_INPUT");
        String query = query(request.search(), request.pageNum(), request.pageSize());
        var models = transport.fetch(request.provider(), request.apiKey());
        return page(models, query, request.pageNum(), request.pageSize(),
                "openrouter".equals(request.provider()) ? "USER_FILTERED" : "PROVIDER_KEY",
                request.provider(), null, null, null);
    }

    public Page scan(Scan request) {
        if (request == null || request.expectedRevision() < 0) throw failure(400, "INVALID_INPUT");
        String query = query(request.search(), request.pageNum(), request.pageSize());
        long configId = decimalId(request.configId());
        decimalId(request.keyId());
        var head = head(configId);
        verify(head, request.expectedRevision());
        final AiConfigurationDtos.Snapshot snapshot;
        try { snapshot = configs.snapshot(configId, head.draft()); }
        catch (RuntimeException unavailable) { throw failure(409, "CONFIG_UNAVAILABLE"); }
        final AiProviderPresets.Preset preset;
        try { preset = AiProviderPresets.require(snapshot.providerCode()); }
        catch (AiConfigurationException invalid) { throw failure(422, "PROVIDER_UNSUPPORTED"); }
        if (!preset.chatEndpoint().equals(snapshot.endpointUrl()))
            throw failure(422, "PROVIDER_UNSUPPORTED");
        var keys = snapshot.keys() == null ? List.<AiConfigurationDtos.KeySpec>of() : snapshot.keys();
        var selected = keys.stream().filter(k -> request.keyId().equals(k.keyRef())).findFirst()
                .orElseThrow(() -> failure(404, "KEY_NOT_FOUND"));
        final String secret;
        try { secret = configs.resolveSecret(selected); }
        catch (RuntimeException unavailable) { throw failure(503, "CREDENTIAL_UNAVAILABLE"); }
        if (secret == null || secret.isBlank()) throw failure(503, "CREDENTIAL_UNAVAILABLE");
        // Recheck before sending credentials and after receiving the remote result.
        same(head, head(configId));
        var models = transport.fetch(preset.code(), secret);
        same(head, head(configId));
        return page(models, query, request.pageNum(), request.pageSize(),
                "openrouter".equals(preset.code()) ? "USER_FILTERED" : "PROVIDER_KEY", preset.code(),
                request.configId(), request.keyId(), head.revision());
    }

    private AiConfigurationService.Head head(long configId) {
        try { return configs.head(configId, false); }
        catch (RuntimeException unavailable) { throw failure(409, "CONFIG_UNAVAILABLE"); }
    }

    private void verify(AiConfigurationService.Head head, int revision) {
        if (head.revision() != revision) throw failure(409, "CONFIG_CHANGED");
        if (head.draft() == null || "ARCHIVED".equals(head.status())) throw failure(409, "CONFIG_UNAVAILABLE");
    }

    private void same(AiConfigurationService.Head original, AiConfigurationService.Head current) {
        verify(current, original.revision());
        if (!Objects.equals(original.draft(), current.draft())) throw failure(409, "CONFIG_CHANGED");
    }

    private long decimalId(String value) {
        if (value == null || !value.matches("[1-9][0-9]{0,18}")) throw failure(400, "INVALID_INPUT");
        try { return Long.parseLong(value); }
        catch (NumberFormatException invalid) { throw failure(400, "INVALID_INPUT"); }
    }

    private String query(String search, int pageNum, int pageSize) {
        if (pageNum < 1 || pageSize < 1 || pageSize > 100 || search != null
                && (search.length() > 128 || search.chars().anyMatch(Character::isISOControl)))
            throw failure(400, "INVALID_INPUT");
        return search == null ? "" : search.strip().toLowerCase(Locale.ROOT);
    }

    private Page page(List<Model> models, String query, int pageNum, int pageSize,
                      String source, String provider, String configId, String keyId, Integer revision) {
        var filtered = models.stream().filter(model -> model.code().toLowerCase(Locale.ROOT).contains(query)
                || model.name().toLowerCase(Locale.ROOT).contains(query)).toList();
        long offset = (long) (pageNum - 1) * pageSize;
        var records = offset >= filtered.size() ? List.<Model>of()
                : filtered.subList((int) offset, Math.min(filtered.size(), (int) offset + pageSize));
        return new Page(List.copyOf(records), filtered.size(), pageNum, pageSize, Instant.now(),
                source, provider, configId, keyId, revision, false);
    }
}
