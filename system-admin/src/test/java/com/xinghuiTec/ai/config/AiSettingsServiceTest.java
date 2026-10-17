package com.xinghuiTec.ai.config;

import com.xinghuiTec.ai.application.AiInvocationIdentity;
import com.xinghuiTec.ai.contract.AiProviderRuntime.ExecutionOutcome;
import com.xinghuiTec.ai.contract.AiProviderRuntime.ExecutionStatus;
import com.xinghuiTec.ai.contract.AiTypes.*;
import com.xinghuiTec.domain.entity.*;
import com.xinghuiTec.security.PlatformAccessService;
import org.junit.jupiter.api.*;
import org.springframework.beans.factory.support.StaticListableBeanFactory;
import org.springframework.context.annotation.*;
import org.springframework.core.io.ByteArrayResource;
import org.springframework.jdbc.datasource.DataSourceTransactionManager;
import org.springframework.jdbc.datasource.init.ScriptUtils;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.config.annotation.method.configuration.EnableMethodSecurity;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import org.springframework.transaction.support.TransactionTemplate;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.*;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

/** Real encrypted configuration/service/test activation with explicitly controlled upstream outcomes. */
class AiSettingsServiceTest {
    private AiConfigurationServiceTest fixture;
    private AiSettingsService settings;
    private final TrustedActor admin = new TrustedActor("101", "000000", Set.of("ai:config:test", "ai:config:enable"), null);

    @BeforeEach void setup() throws Exception {
        fixture = new AiConfigurationServiceTest();
        fixture.setup();
        String migration;
        try (var in = getClass().getResourceAsStream("/db/migration/V20261018_240__conversation_history.sql")) {
            migration = new String(Objects.requireNonNull(in).readAllBytes(), StandardCharsets.UTF_8);
        }
        String table = migration.substring(migration.indexOf("CREATE TABLE sys_ai_settings_test_intent"))
                .replace(" COLLATE utf8mb4_bin", "")
                .replace(" ENGINE=InnoDB DEFAULT CHARSET=utf8mb4", "");
        try (var c = Objects.requireNonNull(fixture.jdbc.getDataSource()).getConnection()) {
            ScriptUtils.executeSqlScript(c, new ByteArrayResource(table.getBytes(StandardCharsets.UTF_8)));
        }
        settings = new AiSettingsService(fixture.service, fixture.tests,
                new TransactionTemplate(new DataSourceTransactionManager(fixture.jdbc.getDataSource())));
        var beans = new StaticListableBeanFactory();
        beans.addBean("settings", settings);
        fixture.tests.completionObservers(beans.getBeanProvider(AiConfigurationTestObserver.class));
    }

    @AfterEach void close() {
        SecurityContextHolder.clearContext();
        fixture.close();
    }

    private AiSettingsDtos.Change fresh() {
        return new AiSettingsDtos.Change(null, "Daily", "openrouter", null, "controlled-settings-key-1234", AiSettingsService.DEFAULT_MODELS);
    }

    @Test void directKeyInputIsEncryptedAndBlankUpdateKeepsMaterialWithoutEcho() throws Exception {
        var created = settings.create(fresh());
        long id = Long.parseLong(created.configId());
        assertTrue(created.keyConfigured());
        assertEquals("••••1234", created.maskedKey());
        assertEquals(OpenRouterTransport.ENDPOINT, created.endpointUrl());
        String ciphertext = fixture.jdbc.queryForObject("SELECT credential_ciphertext FROM sys_ai_configuration_key_material", String.class);
        assertNotNull(ciphertext);
        assertFalse(ciphertext.contains("controlled-settings-key-1234"));
        var originalKey = fixture.service.get(id).draft().keys().get(0);
        var saved = settings.save(id, new AiSettingsDtos.Change(created.revision(), "Renamed", "openrouter", null, "", AiSettingsService.DEFAULT_MODELS));
        assertEquals(originalKey.keyVersion(), fixture.service.get(id).draft().keys().get(0).keyVersion());
        String wire = fixture.json.writeValueAsString(saved);
        assertFalse(wire.contains("controlled-settings-key"));
        assertFalse(wire.contains(ciphertext));
        assertFalse(wire.contains("keyVersion"));
        assertFalse(wire.contains("modelRef"));
        var decoded = fixture.json.readValue("{\"name\":\"Daily\",\"apiKey\":\"controlled-settings-key-1234\",\"modelCodes\":[]}", AiSettingsDtos.Change.class);
        assertEquals("controlled-settings-key-1234", decoded.apiKey());
        assertFalse(fixture.json.writeValueAsString(decoded).contains("apiKey"));
        verifyNoInteractions(fixture.transport);
    }

    @Test void partialRealProofActivatesOnlyPassingModelsAndFailedRetestPreservesPriorActive() throws Exception {
        when(fixture.transport.call(anyString(), anyInt(), anyString(), anyList(), any(), anyInt(), any(), any())).thenAnswer(call -> {
            boolean success = AiSettingsService.DEFAULT_MODELS.get(0).equals(call.getArgument(0));
            return new ExecutionOutcome(success ? ExecutionStatus.SUCCEEDED : ExecutionStatus.FAILED,
                    success ? null : ErrorCode.RATE_LIMITED, false, false, new Usage(null, null, null), null, 1);
        });
        var created = settings.create(fresh());
        long id = Long.parseLong(created.configId());
        var started = settings.startTest(id, new AiSettingsDtos.Test(true, true), admin);
        var partial = finished(id, Long.parseLong(started.testId()));
        assertEquals(AiSettingsDtos.ConnectionStatus.PARTIAL, partial.status());
        assertTrue(partial.active());
        assertEquals(1, fixture.service.get(id).activePairs().size());
        assertTrue(partial.models().stream().anyMatch(m -> m.message().contains("限流")));
        Long active = fixture.service.head(id, false).active();
        var current = settings.get(id);
        settings.save(id, new AiSettingsDtos.Change(current.revision(), current.name(), current.provider(), null, "", AiSettingsService.DEFAULT_MODELS));
        when(fixture.transport.call(anyString(), anyInt(), anyString(), anyList(), any(), anyInt(), any(), any()))
                .thenReturn(new ExecutionOutcome(ExecutionStatus.FAILED, ErrorCode.AUTH_FAILED, false, false, new Usage(null, null, null), null, 1));
        var retry = settings.startTest(id, new AiSettingsDtos.Test(true, true), admin);
        assertEquals(AiSettingsDtos.ConnectionStatus.FAILED, finished(id, Long.parseLong(retry.testId())).status());
        assertEquals(active, fixture.service.head(id, false).active());
        assertTrue(settings.get(id).active());
    }

    private AiSettingsDtos.TestRun finished(long config, long test) throws Exception {
        Instant limit = Instant.now().plusSeconds(5);
        while (Instant.now().isBefore(limit)) {
            var result = settings.testResult(config, test);
            String intent = fixture.jdbc.queryForObject("SELECT activation_status FROM sys_ai_settings_test_intent WHERE test_id=?", String.class, test);
            if (!"PENDING".equals(intent)) return result;
            Thread.sleep(5);
        }
        throw new AssertionError("controlled settings test did not finish");
    }

    @Test void changedKeyShowsUntestedDraftWhileOriginalConnectionRemainsActiveAndRenameNeedsNoProbe() throws Exception {
        var created = settings.create(fresh());
        long id = Long.parseLong(created.configId());
        var first = settings.startTest(id, new AiSettingsDtos.Test(true, true), admin);
        assertTrue(finished(id, Long.parseLong(first.testId())).active());
        var live = settings.get(id);
        var active = fixture.service.head(id, false).active();
        var renamed = settings.save(id, new AiSettingsDtos.Change(live.revision(), "Readable name", live.provider(), null, "", AiSettingsService.DEFAULT_MODELS));
        assertFalse(renamed.pendingChanges());
        assertEquals(AiSettingsDtos.ConnectionStatus.SUCCEEDED, renamed.connection().status());
        var changed = settings.save(id, new AiSettingsDtos.Change(renamed.revision(), renamed.name(), renamed.provider(), null,
                "controlled-replacement-key-5678", AiSettingsService.DEFAULT_MODELS));
        assertTrue(changed.active());
        assertTrue(changed.pendingChanges());
        assertEquals(AiSettingsDtos.ConnectionStatus.NOT_TESTED, changed.connection().status());
        assertEquals("修改待测试，当前仍使用原配置", changed.connection().message());
        assertEquals(active, fixture.service.head(id, false).active());
        verify(fixture.transport, times(2)).call(anyString(), anyInt(), anyString(), anyList(), any(), anyInt(), any(), any());
    }

    @Configuration
    @EnableMethodSecurity
    static class MethodSecurityConfig {}

    @Test void actualMvcMethodSecurityKeepsCreateReadTestAndEnableActionsSeparate() throws Exception {
        try (var context = new AnnotationConfigApplicationContext()) {
            context.register(MethodSecurityConfig.class);
            context.registerBean("platformAccess", PlatformAccessService.class, () -> new PlatformAccessService(false));
            context.registerBean(AiSettingsController.class, () -> new AiSettingsController(settings, new AiInvocationIdentity()));
            context.refresh();
            var mvc = MockMvcBuilders.standaloneSetup(context.getBean(AiSettingsController.class))
                    .setControllerAdvice(new AiSettingsHttpErrors()).build();
            login(Set.of("ai:config:add"));
            var response = mvc.perform(post("/system/ai/settings").contentType("application/json")
                            .content("{\"name\":\"Daily\",\"apiKey\":\"controlled-settings-key-1234\",\"modelCodes\":[]}"))
                    .andExpect(status().isOk()).andExpect(jsonPath("$.data.keyConfigured").value(true))
                    .andExpect(jsonPath("$.data.apiKey").doesNotExist()).andReturn();
            String id = fixture.json.readTree(response.getResponse().getContentAsString()).path("data").path("configId").asText();
            assertFalse(response.getResponse().getContentAsString().contains("controlled-settings-key"));
            mvc.perform(get("/system/ai/settings")).andExpect(status().isForbidden());
            login(Set.of("ai:config:test"));
            mvc.perform(post("/system/ai/settings/" + id + "/connection-tests").contentType("application/json")
                    .content("{\"confirmedCosts\":true,\"activateOnSuccess\":true}"))
                    .andExpect(status().isForbidden());
            verifyNoInteractions(fixture.transport);
        }
    }

    private void login(Set<String> authorities) {
        var user = new SysUser();
        user.setUserId(101L);
        user.setTenantId("000000");
        user.setStatus(1);
        var principal = new LoginUser(user, new ArrayList<>(authorities));
        principal.setTenantId("000000");
        SecurityContextHolder.getContext().setAuthentication(new UsernamePasswordAuthenticationToken(principal, null, principal.getAuthorities()));
    }
}
