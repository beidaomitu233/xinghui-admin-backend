package com.xinghuiTec.ai.config;

import com.xinghuiTec.ai.application.AiApplicationPolicy;
import com.xinghuiTec.ai.application.JdbcAiApplicationReferences;
import com.xinghuiTec.ai.contract.AiApplicationReferences;
import com.xinghuiTec.ai.contract.AiProviderRuntime;
import com.xinghuiTec.ai.contract.AiTypes.*;
import com.xinghuiTec.ai.scene.SceneDtos;
import com.xinghuiTec.ai.scene.SceneSettingsRotation;
import org.junit.jupiter.api.*;
import org.springframework.beans.factory.support.StaticListableBeanFactory;
import org.springframework.jdbc.datasource.DataSourceTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;
import org.springframework.test.util.ReflectionTestUtils;

import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.*;
import java.util.concurrent.*;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

/**
 * H2 + actual configuration/scene rotation services, controlled transport only.
 * No vendor calls.
 */
class AiSettingsKeyRotationTest {
    private AiConfigurationServiceTest fixture;
    private AiSettingsService settings;
    private TransactionTemplate tx;
    private final TrustedActor actor = new TrustedActor("101", "000000", Set.of("ai:config:test", "ai:config:enable"),
            null);
    private static final String OLD = "controlled-original-key";
    private static final String NEW = "controlled-replacement-key";
    private static final String COMPILED = "{  \"schemaVersion\": 2, \"legacyScopes\": [ {\"permission\":\"preserved\"} ] }";

    @BeforeEach
    void setup() throws Exception {
        fixture = new AiConfigurationServiceTest();
        fixture.setup();
        tx = new TransactionTemplate(new DataSourceTransactionManager(fixture.jdbc.getDataSource()));
        script(read("V20261016_200__ai_app_runtime_v04.sql").split("CREATE TABLE sys_ai_request")[0]);
        script(read("V20261016_225__direct_scene_runtime.sql").split("CREATE TABLE sys_ai_scene_mutation")[0]);
        String conversation = read("V20261018_240__conversation_history.sql");
        script(conversation.substring(conversation.indexOf("CREATE TABLE sys_ai_settings_test_intent")));
        script(read("V20261018_243__settings_key_rotation_receipt.sql"));
        var refs = new JdbcAiApplicationReferences(fixture.jdbc, tx.getTransactionManager());
        when(fixture.provider.getIfAvailable()).thenReturn(refs);
        var beans = new StaticListableBeanFactory();
        beans.addBean("rotation", new SceneSettingsRotation(fixture.jdbc, fixture.json));
        fixture.service.settingsRotations(beans.getBeanProvider(AiSettingsRotation.class));
        settings = new AiSettingsService(fixture.service, fixture.tests, tx);
        beans.addBean("observer", settings);
        fixture.tests.completionObservers(beans.getBeanProvider(AiConfigurationTestObserver.class));
    }
    @AfterEach
    void close() {
        fixture.close();
    }

    private String read(String name) throws Exception {
        try (var in = getClass().getResourceAsStream("/db/migration/" + name)) {
            return new String(Objects.requireNonNull(in).readAllBytes(), StandardCharsets.UTF_8);
        }
    }
    private void script(String sql) {
        sql = sql.replace(" COLLATE utf8mb4_bin", "").replace(" ENGINE=InnoDB DEFAULT CHARSET=utf8mb4", "");
        for (String statement : sql.replaceAll("(?m)^--.*$", "").split(";"))
            if (!statement.isBlank())
                fixture.jdbc.execute(statement);
    }
    private long active() throws Exception {
        var created = settings.create(
                new AiSettingsDtos.Change(null, "Daily", "openrouter", null, OLD, AiSettingsService.DEFAULT_MODELS));
        long id = Long.parseLong(created.configId());
        assertTrue(finish(id, settings.startTest(id, new AiSettingsDtos.Test(true, true), actor)).active());
        return id;
    }
    private AiSettingsDtos.TestRun finish(long id, AiSettingsDtos.TestRun start) throws Exception {
        long test = Long.parseLong(start.testId());
        Instant limit = Instant.now().plusSeconds(6);
        while (Instant.now().isBefore(limit)) {
            boolean terminal=!List.of("QUEUED", "RUNNING").contains(fixture.tests.get(id, test).status());
            if (terminal && !"PENDING".equals(fixture.jdbc.queryForObject(
                    "SELECT activation_status FROM sys_ai_settings_test_intent WHERE test_id=?", String.class, test)))
                return settings.testResult(id, test);
            Thread.sleep(5);
        }
        throw new AssertionError("controlled rotation did not finish");
    }
    @Test
    void terminalEvidenceAndRunningMarkerArePublishedAtomicallyBeforeActivation() throws Exception {
        var terminalWritten=new CountDownLatch(1);var release=new CountDownLatch(1);
        var observed=spy(fixture.jdbc);fixture.jdbc=observed;
        ReflectionTestUtils.setField(fixture.service,"jdbc",observed);
        doAnswer(call->{
            Object result=call.callRealMethod();terminalWritten.countDown();
            assertTrue(release.await(6,TimeUnit.SECONDS),"test publication barrier was not released");return result;
        }).when(observed).update(eq("UPDATE sys_ai_configuration_test SET status=?,update_time=CURRENT_TIMESTAMP WHERE test_id=? AND status IN ('QUEUED','RUNNING')"),eq("COMPLETED"),anyLong());
        var created=settings.create(new AiSettingsDtos.Change(null,"Atomic publication","openrouter",null,OLD,AiSettingsService.DEFAULT_MODELS));long id=Long.parseLong(created.configId());
        var start=settings.startTest(id,new AiSettingsDtos.Test(true,true),actor);long test=Long.parseLong(start.testId());
        try {
            assertTrue(terminalWritten.await(6,TimeUnit.SECONDS),"worker did not reach terminal publication");
            // Another connection must not observe only half of the worker's terminal publication.
            settings.completed(id,test);
            assertEquals("PENDING",fixture.jdbc.queryForObject("SELECT activation_status FROM sys_ai_settings_test_intent WHERE test_id=?",String.class,test));
            assertEquals("RUNNING",fixture.tests.get(id,test).status());
            assertNull(fixture.service.head(id,false).active());
        } finally { release.countDown(); }
        var result=finish(id,start);assertTrue(result.active());
        assertEquals("APPLIED",fixture.jdbc.queryForObject("SELECT activation_status FROM sys_ai_settings_test_intent WHERE test_id=?",String.class,test));
        assertNull(fixture.service.head(id,false).runningTest());
    }
    private AiSettingsDtos.View replace(long id) {
        var current = settings.get(id);
        if(current.keys().size()>1) {
            var original=fixture.service.get(id).draft().keys();
            var changes=new ArrayList<AiSettingsDtos.KeyChange>();
            for(int i=0;i<original.size();i++) {
                var old=original.get(i);
                changes.add(new AiSettingsDtos.KeyChange(old.keyRef(),old.label(),i==0?NEW:"",old.modelCodes()));
            }
            return settings.save(id,new AiSettingsDtos.Change(current.revision(),current.name(),current.provider(),null,null,current.models().stream().map(AiSettingsDtos.Model::code).toList(),changes));
        }
        return settings.save(id, new AiSettingsDtos.Change(current.revision(), current.name(), current.provider(), null,
                NEW, current.models().stream().map(AiSettingsDtos.Model::code).toList()));
    }
    private void attach(long id, boolean legacy, boolean fixed) throws Exception {
        var view = fixture.service.get(id);
        var snapshot = view.draft();
        var key = snapshot.keys().get(0);
        var bindings = snapshot.models().stream()
                .map(m -> new AiProviderRuntime.ModelBinding(
                        new ModelVersionRef(m.modelRef(), snapshot.configVersion()),
                        fixed ? AiProviderRuntime.KeyMode.FIXED : AiProviderRuntime.KeyMode.AUTO, Set.of(),
                        fixed ? new KeyRef(key.keyRef(), key.keyVersion()) : null, List.of()))
                .toList();
        var policy = new AiApplicationPolicy(AiApplicationPolicy.ModelMode.FIXED, bindings, true, null,
                Set.of("user_request"), new Requirements(OutputFormat.TEXT, true, false),
                AiApplicationPolicy.Scope.BOTH, Set.of("101"), Set.of("ai:scene:invoke"), null, 90, 2, 2, 100);
        var defs = bindings.stream()
                .map(b -> new SceneDtos.ModelBinding(
                        new SceneDtos.ModelRef(b.model().modelRef(), b.model().configVersion()),
                        fixed ? SceneDtos.KeyMode.FIXED : SceneDtos.KeyMode.AUTO, List.of(),
                        fixed ? new SceneDtos.KeyRef(key.keyRef(), key.keyVersion()) : null, List.of()))
                .toList();
        var definition = new SceneDtos.Definition("Scene", "preserve purpose", "original {{user_request}}", "",
                List.of(), new SceneDtos.ModelPolicy(SceneDtos.ModelMode.FIXED, defs, true, SceneDtos.OutputFormat.TEXT,
                        90, 2, 2, 100));
        String json = fixture.json.writeValueAsString(policy);
        fixture.jdbc.update(
                "INSERT INTO sys_ai_application(app_id,name,purpose,owner_id,entry_key,draft_json,published_version_id,enabled) VALUES(1,'Scene','preserve purpose',101,'scene/test',?,1,TRUE)",
                json);
        fixture.jdbc.update(
                "INSERT INTO sys_ai_application_version(app_version_id,app_id,version_number,draft_revision,snapshot_json,tested_request_id,created_by,snapshot_source) VALUES(1,1,1,1,?,NULL,101,'SCENE_SAVE')",
                fixture.json.writeValueAsString(
                        new com.xinghuiTec.ai.application.AiApplicationService.Snapshot(policy, List.of(), List.of())));
        fixture.jdbc.update("INSERT INTO sys_ai_application_cursor VALUES(1,0)");
        for (var m : snapshot.models())
            fixture.jdbc.update("INSERT INTO sys_ai_application_reference VALUES(1,'MODEL',?,?)", m.modelRef(),
                    snapshot.configVersion());
        fixture.jdbc.update(
                "INSERT INTO sys_ai_scene(scene_key,name,purpose,scene_kind,source,app_id,current_version_id,created_by) VALUES('scene.test','Scene','preserve purpose','GENERAL',?,1,1,101)",
                legacy ? "LEGACY_V04" : "NATIVE");
        fixture.jdbc.update(
                "INSERT INTO sys_ai_scene_version(scene_version_id,scene_key,version_number,saved_revision,app_version_id,definition_json,compiled_json,source,created_by) VALUES(1,'scene.test',1,0,1,?,?,?,101)",
                fixture.json.writeValueAsString(definition), COMPILED, legacy ? "LEGACY_V04" : "NATIVE");
    }
    private long current() {
        return fixture.jdbc.queryForObject("SELECT current_version_id FROM sys_ai_scene WHERE scene_key='scene.test'",
                Long.class);
    }
    private void unchanged(long id, long old) {
        assertEquals(old, fixture.service.head(id, false).active());
        assertEquals(1, current());
        assertEquals(1, fixture.jdbc.queryForObject("SELECT COUNT(*) FROM sys_ai_scene_version", Integer.class));
    }

    @Test
    void successfulRotationUsesNewMaterialAndPreservesOldSnapshots() throws Exception {
        long id = active();
        attach(id, false, false);
        long old = fixture.service.head(id, false).active();
        var oldSnapshot = fixture.service.snapshot(id, old);
        String before = fixture.jdbc.queryForObject(
                "SELECT snapshot_json FROM sys_ai_application_version WHERE app_version_id=1", String.class);
        replace(id);
        unchanged(id, old);
        clearInvocations(fixture.transport);
        var start = settings.startTest(id, new AiSettingsDtos.Test(true, true), actor);
        assertTrue(finish(id, start).active());
        verify(fixture.transport, times(2)).call(anyString(), anyInt(), eq(NEW), anyList(), any(), anyInt(), any(),
                any());
        verify(fixture.transport, never()).call(anyString(), anyInt(), eq(OLD), anyList(), any(), anyInt(), any(),
                any());
        assertNotEquals(old, fixture.service.head(id, false).active());
        assertNotEquals(1, current());
        assertEquals(COMPILED, fixture.jdbc.queryForObject(
                "SELECT compiled_json FROM sys_ai_scene_version WHERE scene_version_id=?", String.class, current()));
        assertEquals(before, fixture.jdbc.queryForObject(
                "SELECT snapshot_json FROM sys_ai_application_version WHERE app_version_id=1", String.class));
        assertEquals(oldSnapshot, fixture.service.snapshot(id, old));
        long app = fixture.jdbc.queryForObject("SELECT published_version_id FROM sys_ai_application WHERE app_id=1",
                Long.class);
        var published = fixture.json.readTree(fixture.jdbc.queryForObject(
                "SELECT snapshot_json FROM sys_ai_application_version WHERE app_version_id=?", String.class, app));
        String version = published.path("policy").path("models").get(0).path("model").path("configVersion").asText();
        assertEquals(Long.toString(fixture.service.head(id, false).active()), version);
        var binding = new AiProviderRuntime.ModelBinding(
                new ModelVersionRef(oldSnapshot.models().get(0).modelRef(), version), AiProviderRuntime.KeyMode.AUTO,
                Set.of(), null, List.of());
        var target = fixture.runtime.resolveTargets(binding, Set.of(), fixture.control).get(0);
        var oldBinding = new AiProviderRuntime.ModelBinding(
                new ModelVersionRef(oldSnapshot.models().get(0).modelRef(), Long.toString(old)),
                AiProviderRuntime.KeyMode.AUTO, Set.of(), null, List.of());
        var oldTarget = fixture.runtime.resolveTargets(oldBinding, Set.of(), fixture.control).get(0);
        clearInvocations(fixture.transport);
        var invocation = new Invocation(actor, "1", Long.toString(app), new BusinessRef("ai.scene", "scene.test"),
                "controlled-request");
        for (var chosen : List.of(target, oldTarget))
            fixture.runtime.execute(
                    new AiProviderRuntime.ExecutionRequest(invocation, "controlled-attempt", chosen,
                            List.of(new AiMessage(MessageRole.USER, "controlled")),
                            new Requirements(OutputFormat.TEXT, true, false), 100),
                    mock(StreamObserver.class), fixture.control);
        verify(fixture.transport).call(anyString(), anyInt(), eq(NEW), anyList(), any(), anyInt(), any(), any());
        verify(fixture.transport).call(anyString(), anyInt(), eq(OLD), anyList(), any(), anyInt(), any(), any());
        assertEquals(NEW,
                fixture.service.resolveSecret(fixture.service.snapshot(id, Long.parseLong(version)).keys().get(0)));
        assertNotEquals(oldSnapshot.keys().get(0).keyVersion(), target.key().keyVersion());
        assertEquals("101", fixture.jdbc.queryForObject(
                "SELECT initiated_by FROM sys_ai_settings_test_intent WHERE test_id=?", String.class, start.testId()));
        assertTrue(fixture.jdbc
                .queryForObject("SELECT rotation_receipt_json FROM sys_ai_settings_test_intent WHERE test_id=?",
                        String.class, start.testId())
                .contains("scene.test"));
        // Completion replay is idempotent: no additional scene/application versions.
        settings.completed(id, Long.parseLong(start.testId()));
        assertEquals(2, fixture.jdbc.queryForObject("SELECT COUNT(*) FROM sys_ai_scene_version", Integer.class));
    }
    @Test
    void failedReplacementKeepsWorkingConnection() throws Exception {
        long id = active();
        attach(id, false, false);
        long old = fixture.service.head(id, false).active();
        replace(id);
        when(fixture.transport.call(anyString(), anyInt(), eq(NEW), anyList(), any(), anyInt(), any(), any()))
                .thenReturn(new AiProviderRuntime.ExecutionOutcome(AiProviderRuntime.ExecutionStatus.FAILED,
                        ErrorCode.AUTH_FAILED, false, false, null, null, 1));
        assertFalse(finish(id, settings.startTest(id, new AiSettingsDtos.Test(true, true), actor)).active());
        unchanged(id, old);
    }
    @Test
    void missingUsedModelProofBlocksPartialActivation() throws Exception {
        long id = active();
        attach(id, false, false);
        long old = fixture.service.head(id, false).active();
        replace(id);
        when(fixture.transport.call(eq(AiSettingsService.DEFAULT_MODELS.get(1)), anyInt(), eq(NEW), anyList(), any(),
                anyInt(), any(), any()))
                .thenReturn(new AiProviderRuntime.ExecutionOutcome(AiProviderRuntime.ExecutionStatus.FAILED,
                        ErrorCode.RATE_LIMITED, false, false, null, null, 1));
        var result = finish(id, settings.startTest(id, new AiSettingsDtos.Test(true, true), actor));
        assertEquals(AiSettingsDtos.ConnectionStatus.PARTIAL, result.status());
        assertFalse(result.active());
        unchanged(id, old);
    }
    @Test
    void fixedKeyRejectsSaveWithoutLeavingDraftOrMaterial() throws Exception {
        long id = active();
        attach(id, false, true);
        long old = fixture.service.head(id, false).active();
        int materials = fixture.jdbc.queryForObject("SELECT COUNT(*) FROM sys_ai_configuration_key_material",
                Integer.class);
        assertEquals(409, assertThrows(AiConfigurationException.class, () -> replace(id)).status());
        unchanged(id, old);
        assertEquals(old, fixture.service.head(id, false).draft());
        assertEquals(materials,
                fixture.jdbc.queryForObject("SELECT COUNT(*) FROM sys_ai_configuration_key_material", Integer.class));
    }
    @Test
    void convertedLegacySceneKeepsCompiledLegacyScopes() throws Exception {
        long id = active();
        attach(id, true, false);
        replace(id);
        assertTrue(finish(id, settings.startTest(id, new AiSettingsDtos.Test(true, true), actor)).active());
        assertEquals(COMPILED, fixture.jdbc.queryForObject(
                "SELECT compiled_json FROM sys_ai_scene_version WHERE scene_version_id=?", String.class, current()));
        assertEquals("LEGACY_V04", fixture.jdbc.queryForObject(
                "SELECT source FROM sys_ai_scene_version WHERE scene_version_id=?", String.class, current()));
    }
    @Test
    void unconvertedLegacyRejectsWithoutChangingWorkingState() throws Exception {
        long id = active();
        attach(id, true, false);
        long old = fixture.service.head(id, false).active();
        fixture.jdbc.update("UPDATE sys_ai_scene_version SET compiled_json=NULL WHERE scene_version_id=1");
        assertThrows(AiConfigurationException.class, () -> replace(id));
        unchanged(id, old);
    }
    @Test
    void concurrentConfigEditMakesCompletionStale() throws Exception {
        long id = active();
        attach(id, false, false);
        long old = fixture.service.head(id, false).active();
        replace(id);
        var entered = new CountDownLatch(1);
        var release = new CountDownLatch(1);
        when(fixture.transport.call(anyString(), anyInt(), eq(NEW), anyList(), any(), anyInt(), any(), any()))
                .thenAnswer(call -> {
                    entered.countDown();
                    assertTrue(release.await(3, TimeUnit.SECONDS));
                    return new AiProviderRuntime.ExecutionOutcome(AiProviderRuntime.ExecutionStatus.SUCCEEDED, null,
                            false, false, null, null, 1);
                });
        var start = settings.startTest(id, new AiSettingsDtos.Test(true, true), actor);
        assertTrue(entered.await(2, TimeUnit.SECONDS));
        try {
            var current = settings.get(id);
            settings.save(id, new AiSettingsDtos.Change(current.revision(), "Renamed meanwhile", current.provider(),
                    null, "", AiSettingsService.DEFAULT_MODELS));
        } finally {
            release.countDown();
        }
        assertFalse(finish(id, start).active());
        unchanged(id, old);
        assertEquals("BLOCKED",
                fixture.jdbc.queryForObject("SELECT activation_status FROM sys_ai_settings_test_intent WHERE test_id=?",
                        String.class, start.testId()));
    }
    @Test
    void originalAdvancedSaveStillProtectsReferences() throws Exception {
        long id = active();
        attach(id, false, false);
        var view = fixture.service.get(id);
        var key = view.draft().keys().get(0);
        var change = new AiConfigurationDtos.DraftChange(view.revision(), view.name(), view.environment(),
                view.providerCode(), view.draft().endpointUrl(), view.draft().protocol(), view.draft().timeoutSeconds(),
                fixture.models(view),
                List.of(new AiConfigurationDtos.KeyInput(key.keyRef(), key.label(), "DB", null, NEW)));
        assertEquals(409,
                assertThrows(AiConfigurationException.class, () -> fixture.service.save(id, change)).status());
    }
    @Test
    void multiKeyRotationTestsOnlyChangedMaterial() throws Exception {
        var view = fixture.active();
        long id = Long.parseLong(view.id());
        attach(id, false, false);
        var pending = replace(id);
        clearInvocations(fixture.transport);
        var result = finish(id, settings.startTest(id, new AiSettingsDtos.Test(true, true), actor));
        assertTrue(result.active());
        verify(fixture.transport, times(2)).call(anyString(), anyInt(), eq(NEW), anyList(), any(), anyInt(), any(),
                any());
        verify(fixture.transport, never()).call(anyString(), anyInt(), eq("controlled-test-key-b"), anyList(), any(),
                anyInt(), any(), any());
        assertEquals(2, result.models().size());
    }
    @Test
    void unchangedKeySuccessCannotHideFailedReplacement() throws Exception {
        var view = fixture.active();
        long id = Long.parseLong(view.id());
        attach(id, false, false);
        long old = fixture.service.head(id, false).active();
        replace(id);
        when(fixture.transport.call(eq("unit/model-a"), anyInt(), eq(NEW), anyList(), any(), anyInt(), any(), any()))
                .thenReturn(new AiProviderRuntime.ExecutionOutcome(AiProviderRuntime.ExecutionStatus.FAILED,
                        ErrorCode.AUTH_FAILED, false, false, null, null, 1));
        assertFalse(finish(id, settings.startTest(id, new AiSettingsDtos.Test(true, true), actor)).active());
        unchanged(id, old);
    }
    @Test
    void disabledManagedSceneAlsoMovesWithoutBeingEnabled() throws Exception {
        long id = active();
        attach(id, false, false);
        fixture.jdbc.update("UPDATE sys_ai_scene SET enabled=FALSE");
        fixture.jdbc.update("UPDATE sys_ai_application SET enabled=FALSE");
        replace(id);
        assertTrue(finish(id, settings.startTest(id, new AiSettingsDtos.Test(true, true), actor)).active());
        assertNotEquals(1, current());
        assertFalse(
                fixture.jdbc.queryForObject("SELECT enabled FROM sys_ai_application WHERE app_id=1", Boolean.class));
        assertFalse(fixture.jdbc.queryForObject("SELECT enabled FROM sys_ai_scene WHERE scene_key='scene.test'",
                Boolean.class));
    }
    @Test
    void failedLaterSceneValidationLeavesAllScenesUntouched() throws Exception {
        long id = active();
        attach(id, false, false);
        long old = fixture.service.head(id, false).active();
        fixture.jdbc.update(
                "INSERT INTO sys_ai_application(app_id,name,purpose,owner_id,entry_key,draft_json,published_version_id,enabled) SELECT 2,'Unsafe','',101,'legacy/unsafe',draft_json,2,TRUE FROM sys_ai_application WHERE app_id=1");
        fixture.jdbc.update(
                "INSERT INTO sys_ai_application_version(app_version_id,app_id,version_number,draft_revision,snapshot_json,tested_request_id,created_by,snapshot_source) SELECT 2,2,1,1,snapshot_json,NULL,101,'LEGACY_TEST_PUBLISH' FROM sys_ai_application_version WHERE app_version_id=1");
        fixture.jdbc.update(
                "INSERT INTO sys_ai_application_reference SELECT 2,reference_kind,reference_id,reference_version FROM sys_ai_application_reference WHERE app_version_id=1");
        assertThrows(AiConfigurationException.class, () -> replace(id));
        unchanged(id, old);
        assertEquals(old, fixture.service.head(id, false).draft());
    }
    @Test
    void sceneChangedToRestrictedBindingDuringTestBlocksCompletion() throws Exception {
        long id = active();
        attach(id, false, false);
        long old = fixture.service.head(id, false).active();
        replace(id);
        var entered = new CountDownLatch(1);
        var release = new CountDownLatch(1);
        when(fixture.transport.call(anyString(), anyInt(), eq(NEW), anyList(), any(), anyInt(), any(), any()))
                .thenAnswer(call -> {
                    entered.countDown();
                    assertTrue(release.await(3, TimeUnit.SECONDS));
                    return new AiProviderRuntime.ExecutionOutcome(AiProviderRuntime.ExecutionStatus.SUCCEEDED, null,
                            false, false, null, null, 1);
                });
        var start = settings.startTest(id, new AiSettingsDtos.Test(true, true), actor);
        assertTrue(entered.await(2, TimeUnit.SECONDS));
        try {
            // A concurrent direct-save transaction publishes a new scene version with
            // restricted keys.
            var refs = fixture.provider.getIfAvailable();
            refs.withReferenceGuard(() -> {
                try {
                    var snapshot = fixture.json.readTree(fixture.jdbc.queryForObject(
                            "SELECT snapshot_json FROM sys_ai_application_version WHERE app_version_id=1",
                            String.class));
                    var definition = fixture.json.readTree(fixture.jdbc.queryForObject(
                            "SELECT definition_json FROM sys_ai_scene_version WHERE scene_version_id=1", String.class));
                    for (var list : List.of(snapshot.path("policy").path("models"),
                            definition.path("modelPolicy").path("models"))) {
                        var binding = (com.fasterxml.jackson.databind.node.ObjectNode) list.get(0);
                        binding.put("keyMode", "FIXED");
                        binding.set("fixedKey",
                                fixture.json.valueToTree(
                                        new KeyRef(fixture.service.snapshot(id, old).keys().get(0).keyRef(),
                                                fixture.service.snapshot(id, old).keys().get(0).keyVersion())));
                    }
                    String policy = fixture.json.writeValueAsString(snapshot.path("policy"));
                    fixture.jdbc.update(
                            "INSERT INTO sys_ai_application_version(app_version_id,app_id,version_number,draft_revision,snapshot_json,tested_request_id,created_by,snapshot_source) VALUES(2,1,2,2,?,NULL,101,'SCENE_SAVE')",
                            fixture.json.writeValueAsString(snapshot));
                    fixture.jdbc.update(
                            "INSERT INTO sys_ai_application_reference SELECT 2,reference_kind,reference_id,reference_version FROM sys_ai_application_reference WHERE app_version_id=1");
                    fixture.jdbc.update(
                            "INSERT INTO sys_ai_scene_version(scene_version_id,scene_key,version_number,saved_revision,app_version_id,definition_json,compiled_json,source,created_by) VALUES(2,'scene.test',2,1,2,?,?,'NATIVE',101)",
                            fixture.json.writeValueAsString(definition), COMPILED);
                    fixture.jdbc.update(
                            "UPDATE sys_ai_application SET published_version_id=2,draft_json=?,revision=revision+1 WHERE app_id=1",
                            policy);
                    fixture.jdbc.update(
                            "UPDATE sys_ai_scene SET current_version_id=2,revision=revision+1 WHERE scene_key='scene.test'");
                    return null;
                } catch (Exception failure) {
                    throw new RuntimeException(failure);
                }
            });
        } finally {
            release.countDown();
        }
        assertFalse(finish(id, start).active());
        assertEquals(old, fixture.service.head(id, false).active());
        assertEquals(2, current());
        assertEquals(2, fixture.jdbc.queryForObject("SELECT COUNT(*) FROM sys_ai_scene_version", Integer.class));
    }

    @Test
    void exceptionAfterAppendingSnapshotsRollsBackWholeRotation() throws Exception {
        long id = active();
        attach(id, false, false);
        long old = fixture.service.head(id, false).active();
        replace(id);
        var actual = new SceneSettingsRotation(fixture.jdbc, fixture.json);
        AiSettingsRotation failing = (before, after, proofs, refs, who, apply) -> {
            String receipt = actual.rotate(before, after, proofs, refs, who, apply);
            if (apply)
                throw new AiConfigurationException(409, "controlled rollback after version append");
            return receipt;
        };
        var beans = new StaticListableBeanFactory();
        beans.addBean("rotation", failing);
        fixture.service.settingsRotations(beans.getBeanProvider(AiSettingsRotation.class));
        assertFalse(finish(id, settings.startTest(id, new AiSettingsDtos.Test(true, true), actor)).active());
        unchanged(id, old);
        assertEquals(1, fixture.jdbc.queryForObject("SELECT COUNT(*) FROM sys_ai_application_version", Integer.class));
        assertEquals(1, fixture.jdbc
                .queryForObject("SELECT published_version_id FROM sys_ai_application WHERE app_id=1", Long.class));
        assertNull(fixture.jdbc.queryForObject(
                "SELECT rotation_receipt_json FROM sys_ai_settings_test_intent ORDER BY test_id DESC LIMIT 1",
                String.class));
    }
    @Test
    void cancelledRotationNeverMovesActiveOrScene() throws Exception {
        long id = active();
        attach(id, false, false);
        long old = fixture.service.head(id, false).active();
        replace(id);
        var entered = new CountDownLatch(1);
        var release = new CountDownLatch(1);
        when(fixture.transport.call(anyString(), anyInt(), eq(NEW), anyList(), any(), anyInt(), any(), any()))
                .thenAnswer(call -> {
                    entered.countDown();
                    assertTrue(release.await(3, TimeUnit.SECONDS));
                    return new AiProviderRuntime.ExecutionOutcome(AiProviderRuntime.ExecutionStatus.SUCCEEDED, null,
                            false, false, null, null, 1);
                });
        var start = settings.startTest(id, new AiSettingsDtos.Test(true, true), actor);
        assertTrue(entered.await(2, TimeUnit.SECONDS));
        try {
            fixture.tests.cancel(id, Long.parseLong(start.testId()));
        } finally {
            release.countDown();
        }
        assertEquals(AiSettingsDtos.ConnectionStatus.CANCELLED, finish(id, start).status());
        unchanged(id, old);
    }
    @Test
    void explicitAllowedKeyRestrictionAlsoRejectsRotation() throws Exception {
        long id = active();
        attach(id, false, false);
        long old = fixture.service.head(id, false).active();
        var key = fixture.service.snapshot(id, old).keys().get(0);
        var snapshot = fixture.json.readTree(fixture.jdbc.queryForObject(
                "SELECT snapshot_json FROM sys_ai_application_version WHERE app_version_id=1", String.class));
        var definition = fixture.json.readTree(fixture.jdbc.queryForObject(
                "SELECT definition_json FROM sys_ai_scene_version WHERE scene_version_id=1", String.class));
        for (var list : List.of(snapshot.path("policy").path("models"),
                definition.path("modelPolicy").path("models"))) {
            var binding = (com.fasterxml.jackson.databind.node.ObjectNode) list.get(0);
            binding.set("allowedKeys", fixture.json.valueToTree(List.of(new KeyRef(key.keyRef(), key.keyVersion()))));
        }
        fixture.jdbc.update("UPDATE sys_ai_application_version SET snapshot_json=? WHERE app_version_id=1",
                fixture.json.writeValueAsString(snapshot));
        fixture.jdbc.update("UPDATE sys_ai_application SET draft_json=? WHERE app_id=1",
                fixture.json.writeValueAsString(snapshot.path("policy")));
        fixture.jdbc.update("UPDATE sys_ai_scene_version SET definition_json=? WHERE scene_version_id=1",
                fixture.json.writeValueAsString(definition));
        assertThrows(AiConfigurationException.class, () -> replace(id));
        unchanged(id, old);
    }

    @Test void addingAndDeletingNamedKeyMaintainsManagedSceneWithoutApplicationSteps() throws Exception {
        long id=active();attach(id,true,false);var original=settings.get(id);var first=original.keys().get(0);
        var codes=original.models().stream().map(AiSettingsDtos.Model::code).toList();
        settings.save(id,new AiSettingsDtos.Change(original.revision(),original.name(),original.provider(),null,null,codes,List.of(new AiSettingsDtos.KeyChange(first.keyId(),first.name(),"",null),new AiSettingsDtos.KeyChange(null,"Backup",NEW,codes))));
        assertEquals(1,current());var added=finish(id,settings.startTest(id,new AiSettingsDtos.Test(true,true),actor));assertTrue(added.active());assertEquals(2,current());
        var two=settings.get(id);var backup=two.keys().get(1);
        settings.save(id,new AiSettingsDtos.Change(two.revision(),two.name(),two.provider(),null,null,codes,List.of(new AiSettingsDtos.KeyChange(backup.keyId(),backup.name(),"",codes))));
        assertTrue(finish(id,settings.startTest(id,new AiSettingsDtos.Test(true,true),actor)).active());assertEquals(3,current());
        assertEquals(1,settings.get(id).keys().size());assertEquals(COMPILED,fixture.jdbc.queryForObject("SELECT compiled_json FROM sys_ai_scene_version WHERE scene_version_id=?",String.class,current()));
    }

}
