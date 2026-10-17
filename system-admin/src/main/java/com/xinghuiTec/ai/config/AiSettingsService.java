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
        return List.of(new Provider("openrouter", "OpenRouter", OpenRouterTransport.ENDPOINT,
                DEFAULT_MODELS.stream().map(code -> new Model(code, modelName(code))).toList()));
    }

    public Page page(int page, int size) {
        var rows = configs.page(page, size, null, null, null, null);
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
                "ACTIVE".equals(original.status()) && original.activeVersion() != null, connection, pending);
    }

    public View create(Change input) {
        if (input == null || input.expectedRevision() != null)
            throw fail(400, "新增配置无需版本参数");
        if (input.apiKey() == null || input.apiKey().isBlank())
            throw fail(400, "请填写API Key");
        var view = configs.create(new AiConfigurationDtos.Create(change(input, null), null));
        return get(Long.parseLong(view.id()));
    }

    public View save(long configId, Change input) {
        if (input == null || input.expectedRevision() == null)
            throw fail(400, "请刷新后保存配置");
        var original = configs.get(configId);
        var previous = original.draft();
        boolean sameConnection = previous != null && (input.apiKey() == null || input.apiKey().isBlank())
                && input.modelCodes()
                        .equals(previous.models().stream().map(AiConfigurationDtos.ModelSpec::modelCode).toList())
                && (input.provider() == null || input.provider().isBlank()
                        || input.provider().equals(previous.providerCode()))
                && (input.endpointUrl() == null || input.endpointUrl().isBlank()
                        || input.endpointUrl().equals(previous.endpointUrl()));
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
        configs.save(configId, change(input, original.draft()));
        return get(configId);
    }

    private AiConfigurationDtos.DraftChange change(Change input, AiConfigurationDtos.Snapshot previous) {
        var codes = input.modelCodes().isEmpty() && previous == null ? DEFAULT_MODELS : input.modelCodes();
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
        if (previous == null || previous.keys().isEmpty()) {
            keys.add(new AiConfigurationDtos.KeyInput(null, "API Key", "DB", null, input.apiKey()));
        } else {
            for (int i = 0; i < previous.keys().size(); i++) {
                var old = previous.keys().get(i);
                boolean replace = i == 0 && input.apiKey() != null && !input.apiKey().isBlank();
                keys.add(new AiConfigurationDtos.KeyInput(old.keyRef(), old.label(), replace ? "DB" : old.mode(),
                        replace ? null : old.envName(), replace ? input.apiKey() : null));
            }
        }
        String provider = input.provider() == null || input.provider().isBlank() ? "openrouter" : input.provider();
        String endpoint = input.endpointUrl() == null || input.endpointUrl().isBlank()
                ? OpenRouterTransport.ENDPOINT
                : input.endpointUrl();
        return new AiConfigurationDtos.DraftChange(input.expectedRevision() == null ? 0 : input.expectedRevision(),
                input.name(), "production", provider, endpoint, "OPENAI_CHAT",
                previous == null ? 90 : previous.timeoutSeconds(), models, keys);
    }

    public TestRun startTest(long configId, Test input, TrustedActor actor) {
        if (input == null || !input.confirmedCosts())
            throw fail(400, "请确认测试连接会产生实际调用");
        if (input.activateOnSuccess() && !actor.authorities().contains("ai:config:enable"))
            throw fail(403, "无权启用配置");
        var head = configs.head(configId, false);
        var run = tests.start(configId,
                new AiConfigurationDtos.TestStart(head.revision(), AiConfigurationService.str(head.draft()), true));
        configs.jdbc.update(
                "INSERT INTO sys_ai_settings_test_intent(test_id,config_id,config_version,expected_revision,activate_on_success) VALUES(?,?,?,?,?)",
                run.id(), configId, run.configVersion(), head.revision(), input.activateOnSuccess());
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
                long succeeded = run.pairs().stream().filter(p -> "SUCCESS".equals(p.status())).count();
                String chosen = "NOT_REQUESTED";
                if (activate && "COMPLETED".equals(run.status()) && succeeded > 0) {
                    var current = configs.head(configId, false);
                    AiConfigurationService.checkRevision(current,
                            ((Number) intent.get("expected_revision")).intValue());
                    if (!"ACTIVE".equals(current.status())
                            || !Objects.equals(current.active(), Long.parseLong(run.configVersion())))
                        configs.activate(configId, new AiConfigurationDtos.Activate(
                                ((Number) intent.get("expected_revision")).intValue(), run.configVersion(), true));
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
        long success = run.pairs().stream().filter(p -> "SUCCESS".equals(p.status())).count();
        ConnectionStatus status = switch (run.status()) {
            case "QUEUED" -> ConnectionStatus.QUEUED;
            case "RUNNING" -> ConnectionStatus.RUNNING;
            case "CANCELLED" -> ConnectionStatus.CANCELLED;
            case "INTERRUPTED" -> ConnectionStatus.INTERRUPTED;
            default -> success == run.total() && success > 0
                    ? ConnectionStatus.SUCCEEDED
                    : success > 0 ? ConnectionStatus.PARTIAL : ConnectionStatus.FAILED;
        };
        String message = switch (status) {
            case QUEUED -> "连接测试等待中";
            case RUNNING -> "正在测试连接";
            case SUCCEEDED -> active ? "连接成功，配置已启用" : "连接成功，原可用配置保持不变";
            case PARTIAL -> active ? "已启用通过的模型，另一个未通过" : "部分模型通过，原可用配置保持不变";
            case FAILED -> "连接测试未通过，原可用配置保持不变";
            case CANCELLED -> "连接测试已取消";
            default -> "连接测试已中断，可重新测试";
        };
        return new TestRun(run.id(), Long.toString(configId), status, message, active, run.pairs().stream()
                .map(p -> new ModelResult(p.modelCode(),
                        "SUCCESS".equals(p.status())
                                ? ConnectionStatus.SUCCEEDED
                                : List.of("PENDING", "RUNNING").contains(p.status())
                                        ? ConnectionStatus.RUNNING
                                        : ConnectionStatus.FAILED,
                        reason(p.errorCode(), p.status())))
                .toList());
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
