package com.xinghuiTec.ai.config;

import com.xinghuiTec.ai.contract.AiTypes.TrustedActor;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;
import org.springframework.transaction.support.TransactionTemplate;
import java.util.*;
import static com.xinghuiTec.ai.config.AiConfigurationService.fail;
import static com.xinghuiTec.ai.config.AiSettingsDtos.*;

/**
 * Human settings facade over existing encrypted configurations, tests and
 * guarded activation.
 */
@Service
public class AiSettingsService implements AiConfigurationTestObserver {
    public static final List<String> DEFAULT_MODELS = List.of("poolside/laguna-s-2.1:free",
            "nvidia/nemotron-3.5-lightning:free");
    private final AiConfigurationService configs;
    private final AiConfigurationTests tests;
    private final TransactionTemplate transactions;

    public AiSettingsService(AiConfigurationService configs, AiConfigurationTests tests,
            TransactionTemplate transactions) {
        this.configs = configs;
        this.tests = tests;
        this.transactions = transactions;
    }

    public List<Provider> providers() {
        return AiProviderPresets.all().stream().map(p -> new Provider(p.code(), p.name(), p.chatEndpoint(),
                p.suggestedModels().stream().map(code -> new Model(code, modelName(code))).toList())).toList();
    }

    public Page page(int page, int size) {
        return page(page, size, null);
    }
    public Page page(int page, int size, String search) {
        var rows = configs.page(page, size, search, null, null, null);
        return new Page(rows.records().stream().map(row -> get(Long.parseLong(row.id()))).toList(),
                Long.toString(rows.total()), page, size);
    }

    public View get(long configId) {
        var original = configs.get(configId);
        var snapshot = original.draft();
        var keys = snapshot == null ? List.<AiConfigurationDtos.KeySpec>of() : snapshot.keys();
        String masked = null;
        if (!keys.isEmpty()) {
            masked = "已配置";
            try {
                String secret = configs.resolveSecret(keys.get(0));
                if (secret.length() >= 8)
                    masked = "••••" + secret.substring(secret.length() - 4);
            } catch (RuntimeException unavailable) {
                // A configured but unavailable ENV source remains configured; no source name or
                // material is returned.
            }
        }
        boolean pending = original.activeVersion() != null && snapshot != null
                && !original.activeVersion().equals(snapshot.configVersion());
        Connection connection = new Connection(null, ConnectionStatus.NOT_TESTED,
                pending ? "修改待测试，当前仍使用原配置" : "尚未测试当前配置", false, null);
        if (original.latestTestId() != null) {
            var actual = tests.get(configId, Long.parseLong(original.latestTestId()));
            if (snapshot != null && snapshot.configVersion().equals(actual.configVersion())) {
                var run = testResult(configId, Long.parseLong(original.latestTestId()));
                var checked = configs.jdbc.queryForObject(
                        "SELECT update_time FROM sys_ai_configuration_test WHERE test_id=? AND config_id=?",
                        java.sql.Timestamp.class, run.testId(), configId);
                connection = new Connection(run.testId(), run.status(), run.message(), run.active(),
                        checked == null ? null : checked.toInstant());
            }
        }
        return new View(original.id(), original.revision(), original.name(), original.providerCode(),
                snapshot == null ? OpenRouterTransport.ENDPOINT : snapshot.endpointUrl(), !keys.isEmpty(), masked,
                snapshot == null
                        ? List.of()
                        : snapshot.models().stream().map(m -> new Model(m.modelCode(), modelName(m.modelCode())))
                                .toList(),
                "ACTIVE".equals(original.status()) && original.activeVersion() != null, connection, pending,
                keyViews(snapshot));
    }

    public View create(Change input, TrustedActor actor) {
        return mutation(null, input, actor, () -> create(input));
    }
    public View save(long id, Change input, TrustedActor actor) {
        return mutation(id, input, actor, () -> save(id, input));
    }
    private View mutation(Long id, Change input, TrustedActor actor, java.util.function.Supplier<View> operation) {
        if (input == null || input.clientMutationId() == null)
            return operation.get();
        try {
            if (!UUID.fromString(input.clientMutationId()).toString().equals(input.clientMutationId()))
                throw new IllegalArgumentException();
        } catch (IllegalArgumentException invalid) {
            throw fail(400, "请使用有效的操作标识");
        }
        if (actor == null)
            throw fail(403, "无权保存配置");
        String owner = configs.settingsFingerprint("ai:settings-owner:v1",
                List.of(actor.subjectId(), actor.tenantId()));
        var values = new LinkedHashMap<String, Object>();
        values.put("configId", id);
        values.put("revision", input.expectedRevision());
        values.put("name", input.name());
        values.put("provider", input.provider());
        values.put("endpoint", input.endpointUrl());
        values.put("apiKey", input.apiKey());
        values.put("models", input.modelCodes());
        if (input.keys() != null)
            values.put("keys", input.keys().stream().map(k -> {
                var v = new LinkedHashMap<String, Object>();
                v.put("id", k.keyId());
                v.put("name", k.name());
                v.put("apiKey", k.apiKey());
                v.put("models", k.modelCodes());
                return v;
            }).toList());
        String fingerprint = configs.settingsFingerprint("ai:settings-mutation:v1", values);
        return configs.settingsGuard(() -> transactions.execute(status -> {
            var prior = configs.jdbc.queryForList(
                    "SELECT config_id,fingerprint FROM sys_ai_settings_mutation WHERE owner_scope=? AND mutation_id=? FOR UPDATE",
                    owner, input.clientMutationId());
            if (!prior.isEmpty()) {
                if (!fingerprint.equals(prior.get(0).get("fingerprint")))
                    throw fail(409, "同一操作标识的内容已变化，请重新保存");
                return get(((Number) prior.get(0).get("config_id")).longValue());
            }
            var result = operation.get();
            configs.jdbc.update(
                    "INSERT INTO sys_ai_settings_mutation(owner_scope,mutation_id,config_id,fingerprint) VALUES(?,?,?,?)",
                    owner, input.clientMutationId(), result.configId(), fingerprint);
            return result;
        }));
    }

    public View create(Change input) {
        if (input == null || input.expectedRevision() != null)
            throw fail(400, "新增配置无需版本参数");
        if (input.keys() == null && (input.apiKey() == null || input.apiKey().isBlank()))
            throw fail(400, "请填写API Key");
        var view = configs.create(new AiConfigurationDtos.Create(change(input, null), null));
        return get(Long.parseLong(view.id()));
    }

    public View save(long configId, Change input) {
        if (input == null || input.expectedRevision() == null)
            throw fail(400, "请刷新后保存配置");
        var original = configs.get(configId);
        var previous = original.draft();
        var desired = change(input, previous);
        boolean sameConnection = sameConnection(desired, previous);
        if (sameConnection) {
            transactions.executeWithoutResult(status -> {
                var current = configs.head(configId, true);
                AiConfigurationService.checkRevision(current, input.expectedRevision());
                if (input.name() == null || input.name().isBlank() || input.name().length() > 100)
                    throw fail(400, "请填写配置名称");
                configs.jdbc.update(
                        "UPDATE sys_ai_configuration SET config_name=?,revision=revision+1,update_time=CURRENT_TIMESTAMP WHERE config_id=?",
                        input.name().trim(), configId);
            });
            return get(configId);
        }
        configs.saveSettings(configId, desired);
        return get(configId);
    }

    private boolean sameConnection(AiConfigurationDtos.DraftChange desired, AiConfigurationDtos.Snapshot previous) {
        if (previous == null || !desired.providerCode().equals(previous.providerCode())
                || !desired.endpointUrl().equals(previous.endpointUrl())
                || desired.models().size() != previous.models().size()
                || desired.keys().size() != previous.keys().size())
            return false;
        for (int i = 0; i < desired.models().size(); i++) {
            var m = desired.models().get(i);
            var old = previous.models().get(i);
            if (!Objects.equals(m.modelRef(), old.modelRef()) || !m.modelCode().equals(old.modelCode())
                    || m.jsonObject() != old.jsonObject())
                return false;
        }
        for (int i = 0; i < desired.keys().size(); i++) {
            var k = desired.keys().get(i);
            var old = previous.keys().get(i);
            if (k.apiKey() != null && !k.apiKey().isBlank() || !Objects.equals(k.keyRef(), old.keyRef())
                    || !Objects.equals(k.label(), old.label()) || !Objects.equals(k.mode(), old.mode())
                    || !Objects.equals(k.envName(), old.envName()) || !Objects.equals(k.modelCodes(), old.modelCodes()))
                return false;
        }
        return true;
    }
    private AiConfigurationDtos.DraftChange change(Change input, AiConfigurationDtos.Snapshot previous) {
        if (input.keys() != null && input.apiKey() != null && !input.apiKey().isBlank())
            throw fail(400, "请使用Key列表填写凭据");
        String provider = input.provider() == null || input.provider().isBlank()
                ? previous == null ? "openrouter" : previous.providerCode() : input.provider();
        var codes = new ArrayList<>(input.modelCodes());
        if (codes.isEmpty() && input.keys() != null) {
            for (var key : input.keys())
                if (key.modelCodes() != null)
                    for (var code : key.modelCodes())
                        if (!codes.contains(code))
                            codes.add(code);
        }
        if (codes.isEmpty() && input.modelCodes().isEmpty())
            codes.addAll(previous == null
                    ? AiProviderPresets.require(provider)
                            .suggestedModels()
                    : previous.models().stream().map(AiConfigurationDtos.ModelSpec::modelCode).toList());
        if (codes.isEmpty())
            throw fail(400, "请至少选择一个模型");
        var models = codes.stream().map(code -> {
            var old = previous == null
                    ? null
                    : previous.models().stream().filter(m -> m.modelCode().equals(code)).findFirst().orElse(null);
            return new AiConfigurationDtos.ModelInput(old == null ? null : old.modelRef(), code,
                    old != null && old.jsonObject());
        }).toList();
        var keys = new ArrayList<AiConfigurationDtos.KeyInput>();
        var existing = previous == null ? List.<AiConfigurationDtos.KeySpec>of() : previous.keys();
        if (input.keys() != null) {
            if (input.keys().isEmpty() || input.keys().size() > 16)
                throw fail(400, "请配置1至16个Key");
            var seen = new HashSet<String>();
            for (var key : input.keys()) {
                if (key.name() == null || key.name().isBlank() || key.name().length() > 80
                        || key.name().chars().anyMatch(c -> c < 32 || c == 127))
                    throw fail(400, "请填写有效Key名称");
                var old = key.keyId() == null
                        ? null
                        : existing.stream().filter(k -> k.keyRef().equals(key.keyId())).findFirst()
                                .orElseThrow(() -> fail(400, "Key不属于当前配置"));
                if (key.keyId() != null && !seen.add(key.keyId()))
                    throw fail(400, "Key重复");
                boolean replace = key.apiKey() != null && !key.apiKey().isBlank();
                if (old == null && !replace)
                    throw fail(400, "新增Key不能为空");
                keys.add(new AiConfigurationDtos.KeyInput(old == null ? null : old.keyRef(), key.name(),
                        replace || old == null ? "DB" : old.mode(), replace || old == null ? null : old.envName(),
                        key.apiKey(), key.modelCodes()));
            }
        } else if (existing.isEmpty()) {
            keys.add(new AiConfigurationDtos.KeyInput(null, "API Key", "DB", null, input.apiKey()));
        } else {
            boolean replace = input.apiKey() != null && !input.apiKey().isBlank();
            if (replace && existing.size() != 1)
                throw fail(400, "请选择要修改的Key");
            for (var old : existing)
                keys.add(new AiConfigurationDtos.KeyInput(old.keyRef(), old.label(), replace ? "DB" : old.mode(),
                        replace ? null : old.envName(), replace ? input.apiKey() : null, old.modelCodes()));
        }
        String endpoint = input.endpointUrl() == null || input.endpointUrl().isBlank()
                ? previous == null ? AiProviderPresets.require(provider).chatEndpoint() : previous.endpointUrl()
                : input.endpointUrl();
        return new AiConfigurationDtos.DraftChange(input.expectedRevision() == null ? 0 : input.expectedRevision(),
                input.name(), "production", provider, endpoint, "OPENAI_CHAT",
                previous == null ? 120 : previous.timeoutSeconds(), models, keys);
    }

    private String mask(AiConfigurationDtos.KeySpec key) {
        try {
            String secret = configs.resolveSecret(key);
            return secret.length() < 8 ? "已配置" : "••••" + secret.substring(secret.length() - 4);
        } catch (RuntimeException unavailable) {
            return "已配置";
        }
    }
    private List<KeyView> keyViews(AiConfigurationDtos.Snapshot snapshot) {
        return keyViews(snapshot, null);
    }
    private List<KeyView> keyViews(AiConfigurationDtos.Snapshot snapshot, AiConfigurationDtos.TestRun run) {
        if (snapshot == null)
            return List.of();
        return snapshot.keys().stream().map(key -> {
            var results = snapshot.models().stream().filter(m -> AiConfigurationService.associated(m, key)).map(m -> {
                if (run != null) {
                    var recorded = run
                            .pairs().stream().filter(p -> p.keyRef().equals(key.keyRef())
                                    && p.keyVersion().equals(key.keyVersion()) && p.modelRef().equals(m.modelRef()))
                            .findFirst();
                    if (recorded.isPresent()) {
                        var pair = recorded.get();
                        var resultStatus = "SUCCESS".equals(pair.status())
                                ? ConnectionStatus.SUCCEEDED
                                : "FAILED".equals(pair.status())
                                        ? ConnectionStatus.FAILED
                                        : "CANCELLED".equals(run.status())
                                                ? ConnectionStatus.CANCELLED
                                                : "INTERRUPTED".equals(run.status())
                                                        ? ConnectionStatus.INTERRUPTED
                                                        : ConnectionStatus.RUNNING;
                        return new ModelResult(m.modelCode(), resultStatus, reason(pair.errorCode(), pair.status()));
                    }
                }
                boolean valid = configs.keyUsable(key) && configs.validProof(snapshot, m, key);
                var latest = configs.jdbc.query(
                        "SELECT p.status,p.error_code,t.status FROM sys_ai_configuration_test_pair p JOIN sys_ai_configuration_test t ON t.test_id=p.test_id WHERE p.model_ref=? AND p.key_ref=? AND p.key_version=? ORDER BY p.pair_id DESC LIMIT 1",
                        (rs, n) -> new String[]{rs.getString(1), rs.getString(2), rs.getString(3)}, m.modelRef(),
                        key.keyRef(), key.keyVersion());
                ConnectionStatus status = valid
                        ? ConnectionStatus.SUCCEEDED
                        : latest.isEmpty()
                                ? ConnectionStatus.NOT_TESTED
                                : List.of("PENDING", "RUNNING").contains(latest.get(0)[0])
                                        ? ConnectionStatus.RUNNING
                                        : ConnectionStatus.FAILED;
                if (!configs.keyUsable(key))
                    return new ModelResult(m.modelCode(), ConnectionStatus.FAILED, "Key认证失败，请检查并重新测试");
                if (status == ConnectionStatus.RUNNING
                        && List.of("CANCELLED", "INTERRUPTED").contains(latest.get(0)[2]))
                    status = ConnectionStatus.valueOf(latest.get(0)[2]);
                if (!valid && !latest.isEmpty() && "SUCCESS".equals(latest.get(0)[0]))
                    return new ModelResult(m.modelCode(), ConnectionStatus.NOT_TESTED, "当前配置尚未测试或验证已过期");
                return new ModelResult(m.modelCode(), status,
                        valid ? "连接成功" : latest.isEmpty() ? "尚未测试" : reason(latest.get(0)[1], latest.get(0)[0]));
            }).toList();
            ConnectionStatus status = aggregate(results);
            return new KeyView(key.keyRef(), key.label(), true, mask(key),
                    results.stream().map(ModelResult::code).toList(), status, summary(status), results);
        }).toList();
    }
    private ConnectionStatus aggregate(List<ModelResult> models) {
        if (models.isEmpty())
            return ConnectionStatus.NOT_TESTED;
        if (models.stream()
                .anyMatch(m -> m.status() == ConnectionStatus.RUNNING || m.status() == ConnectionStatus.QUEUED))
            return ConnectionStatus.RUNNING;
        long passed = models.stream().filter(m -> m.status() == ConnectionStatus.SUCCEEDED).count();
        if (passed == models.size())
            return ConnectionStatus.SUCCEEDED;
        if (passed > 0)
            return ConnectionStatus.PARTIAL;
        if (models.stream().allMatch(m -> m.status() == ConnectionStatus.NOT_TESTED))
            return ConnectionStatus.NOT_TESTED;
        return ConnectionStatus.FAILED;
    }
    private String summary(ConnectionStatus status) {
        return switch (status) {
            case SUCCEEDED -> "关联模型均可用";
            case PARTIAL -> "部分关联模型可用，请查看各模型结果";
            case RUNNING, QUEUED -> "正在测试连接";
            case NOT_TESTED -> "尚未测试";
            default -> "连接不可用，请检查Key或重新测试";
        };
    }

    public TestRun startTest(long configId, Test input, TrustedActor actor) {
        if (input == null || !input.confirmedCosts())
            throw fail(400, "请确认测试连接会产生实际调用");
        if (input.activateOnSuccess() && !actor.authorities().contains("ai:config:enable"))
            throw fail(403, "无权启用配置");
        var head = configs.head(configId, false);
        var run = tests.startSettings(configId,
                new AiConfigurationDtos.TestStart(head.revision(), AiConfigurationService.str(head.draft()), true));
        configs.jdbc.update(
                "INSERT INTO sys_ai_settings_test_intent(test_id,config_id,config_version,expected_revision,activate_on_success,initiated_by) VALUES(?,?,?,?,?,?)",
                run.id(), configId, run.configVersion(), head.revision(), input.activateOnSuccess(), actor.subjectId());
        // An extremely short test may have completed before its intent was inserted.
        completed(configId, Long.parseLong(run.id()));
        return testResult(configId, Long.parseLong(run.id()));
    }

    @Override
    public void completed(long configId, long testId) {
        try {
            transactions.executeWithoutResult(status -> {
                var intents = configs.jdbc.queryForList(
                        "SELECT * FROM sys_ai_settings_test_intent WHERE test_id=? AND config_id=? FOR UPDATE", testId,
                        configId);
                if (intents.isEmpty() || !"PENDING".equals(intents.get(0).get("activation_status")))
                    return;
                var intent = intents.get(0);
                var run = tests.get(configId, testId);
                if (List.of("QUEUED", "RUNNING").contains(run.status()))
                    return;
                Object flag = intent.get("activate_on_success");
                boolean activate = Boolean.TRUE.equals(flag) || flag instanceof Number number && number.intValue() == 1;
                var testedSnapshot = configs.snapshot(configId, Long.parseLong(run.configVersion()));
                long succeeded = testedSnapshot.models().stream()
                        .flatMap(m -> testedSnapshot.keys().stream().filter(k -> AiConfigurationService.associated(m, k)
                                && configs.keyUsable(k) && configs.validProof(testedSnapshot, m, k)))
                        .count();
                String chosen = "NOT_REQUESTED";
                if (activate && "COMPLETED".equals(run.status()) && succeeded > 0) {
                    var current = configs.head(configId, false);
                    AiConfigurationService.checkRevision(current,
                            ((Number) intent.get("expected_revision")).intValue());
                    if (!"ACTIVE".equals(current.status())
                            || !Objects.equals(current.active(), Long.parseLong(run.configVersion())))
                        configs.activateSettings(configId,
                                new AiConfigurationDtos.Activate(((Number) intent.get("expected_revision")).intValue(),
                                        run.configVersion(), true),
                                (String) intent.get("initiated_by"), testId);
                    chosen = "APPLIED";
                } else if (activate) {
                    chosen = "NO_SUCCESS";
                }
                configs.jdbc.update("UPDATE sys_ai_settings_test_intent SET activation_status=? WHERE test_id=?",
                        chosen, testId);
            });
        } catch (AiConfigurationException conflict) {
            // Roll back the guarded attempt first, then durably explain why the original
            // active configuration was kept.
            configs.jdbc.update(
                    "UPDATE sys_ai_settings_test_intent SET activation_status='BLOCKED' WHERE test_id=? AND config_id=? AND activation_status='PENDING'",
                    testId, configId);
        }
    }

    @Scheduled(fixedDelay = 10000)
    public void reconcile() {
        var rows = configs.jdbc.queryForList(
                "SELECT i.config_id,i.test_id FROM sys_ai_settings_test_intent i JOIN sys_ai_configuration_test t ON t.test_id=i.test_id WHERE i.activation_status='PENDING' AND t.status NOT IN ('QUEUED','RUNNING') LIMIT 100");
        for (var row : rows)
            completed(((Number) row.get("config_id")).longValue(), ((Number) row.get("test_id")).longValue());
    }

    public TestRun testResult(long configId, long testId) {
        var run = tests.get(configId, testId);
        var head = configs.head(configId, false);
        boolean active = "ACTIVE".equals(head.status())
                && Objects.equals(head.active(), Long.parseLong(run.configVersion()));
        var snapshot = configs.snapshot(configId, Long.parseLong(run.configVersion()));
        var keyViews = keyViews(snapshot, run);
        var allResults = keyViews.stream().flatMap(k -> k.models().stream()).toList();
        long success = allResults.stream().filter(m -> m.status() == ConnectionStatus.SUCCEEDED).count();
        ConnectionStatus status = switch (run.status()) {
            case "QUEUED" -> ConnectionStatus.QUEUED;
            case "RUNNING" -> ConnectionStatus.RUNNING;
            case "CANCELLED" -> ConnectionStatus.CANCELLED;
            case "INTERRUPTED" -> ConnectionStatus.INTERRUPTED;
            default -> success == allResults.size() && success > 0
                    ? ConnectionStatus.SUCCEEDED
                    : success > 0 ? ConnectionStatus.PARTIAL : ConnectionStatus.FAILED;
        };
        String message = switch (status) {
            case QUEUED -> "连接测试等待中";
            case RUNNING -> "正在测试连接";
            case SUCCEEDED -> active ? "连接成功，配置已启用" : "连接成功，原可用配置保持不变";
            case PARTIAL -> active ? "已启用可用的Key与模型关联，部分关联未通过" : "部分关联通过，原可用配置保持不变";
            case FAILED -> "连接测试未通过，原可用配置保持不变";
            case CANCELLED -> "连接测试已取消";
            default -> "连接测试已中断，可重新测试";
        };
        var activation = configs.jdbc.query(
                "SELECT activation_status FROM sys_ai_settings_test_intent WHERE test_id=? AND config_id=?",
                (rs, n) -> rs.getString(1), testId, configId);
        if (!active && activation.contains("BLOCKED"))
            message = "新Key尚未应用，正在使用的模型验证未全通过或提示词配置已变化，原连接保持不变";
        var aggregated = snapshot.models().stream().map(model -> {
            var results = allResults.stream().filter(m -> m.code().equals(model.modelCode())).toList();
            boolean usable = results.stream().anyMatch(m -> m.status() == ConnectionStatus.SUCCEEDED);
            var modelStatus = usable ? ConnectionStatus.SUCCEEDED : aggregate(results);
            return new ModelResult(model.modelCode(), modelStatus,
                    usable ? "至少一个关联Key可用" : results.isEmpty() ? "未关联Key" : results.get(0).message());
        }).toList();
        return new TestRun(run.id(), Long.toString(configId), status, message, active, aggregated, keyViews.stream()
                .map(k -> new KeyResult(k.keyId(), k.name(), k.status(), k.message(), k.models())).toList());
    }

    private static String reason(String code, String status) {
        if ("SUCCESS".equals(status))
            return "连接成功";
        if (code == null)
            return "RUNNING".equals(status) || "PENDING".equals(status) ? "测试中" : "连接未完成";
        return switch (code) {
            case "AUTH_FAILED", "CREDENTIAL_UNAVAILABLE" -> "请检查API Key";
            case "RATE_LIMITED" -> "服务商暂时限流，请稍后重试";
            case "DEADLINE_EXCEEDED", "NETWORK_ERROR" -> "网络或服务商响应超时";
            case "OUTPUT_LIMIT" -> "模型未在输出预算内完成回答";
            default -> "服务商未完成本次请求";
        };
    }

    private static String modelName(String code) {
        return switch (code) {
            case "poolside/laguna-s-2.1:free" -> "Poolside Laguna S 2.1";
            case "nvidia/nemotron-3.5-lightning:free" -> "NVIDIA Nemotron 3.5 Lightning";
            default -> code;
        };
    }
}
