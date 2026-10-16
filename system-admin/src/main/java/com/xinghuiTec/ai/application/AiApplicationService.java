package com.xinghuiTec.ai.application;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.xinghuiTec.ai.contract.*;
import com.xinghuiTec.ai.contract.AiTypes.*;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.support.GeneratedKeyHolder;
import org.springframework.stereotype.Service;

import java.time.Instant;
import java.util.*;

/** Publication and dependency references are atomic under the same guard used by A and C. */
@Service
public class AiApplicationService {
    private final JdbcTemplate jdbc;
    private final ObjectMapper mapper;
    private final AiApplicationStore store;
    private final AiApplicationReferences references;
    private final ObjectProvider<AiProviderRuntime> providers;
    private final ObjectProvider<AiTemplateRuntime> templates;

    public AiApplicationService(JdbcTemplate jdbc, ObjectMapper mapper, AiApplicationStore store,
                                AiApplicationReferences references, ObjectProvider<AiProviderRuntime> providers,
                                ObjectProvider<AiTemplateRuntime> templates) {
        this.jdbc = jdbc;
        this.mapper = mapper;
        this.store = store;
        this.references = references;
        this.providers = providers;
        this.templates = templates;
    }

    public record TestEvidence(String requestId, ModelVersionRef model) {}
    public record EvidenceView(String requestId, int draftRevision, ModelVersionRef model, String status, Instant finishedAt) {}
    public record Snapshot(AiApplicationPolicy policy, List<ResourceVersionRef> resources,
                           List<TestEvidence> tests) {
        public Snapshot { resources = List.copyOf(resources); tests = List.copyOf(tests); }
    }
    public record Version(String appVersionId, String appId, int versionNumber,
                          int draftRevision, Snapshot snapshot) {}

    public Version publish(long appId, int revision, List<String> successfulTests, TrustedActor actor) {
        return references.withReferenceGuard(() -> {
            var app = store.current(appId);
            expected(app, revision);
            app.draft().requireComplete();
            List<TestEvidence> tests = evidence(app, successfulTests);
            var invocation = testInvocation(app, tests.get(0).requestId(), actor);
            List<ResourceVersionRef> resources = validate(app.draft(), invocation);
            ensureEntryAvailable(app);
            List<Integer> versions = jdbc.query("SELECT version_number FROM sys_ai_application_version"
                    + " WHERE app_id=? ORDER BY version_number DESC LIMIT 1 FOR UPDATE",
                    (rs, index) -> rs.getInt(1), appId);
            int number = versions.isEmpty() ? 1 : Math.addExact(versions.get(0), 1);
            Snapshot snapshot = new Snapshot(app.draft(), resources, tests);
            var keys = new GeneratedKeyHolder();
            jdbc.update(connection -> {
                var statement = connection.prepareStatement("INSERT INTO sys_ai_application_version"
                                + "(app_id,version_number,draft_revision,snapshot_json,tested_request_id,created_by) VALUES(?,?,?,?,?,?)",
                        new String[] {"app_version_id"});
                statement.setLong(1, appId);
                statement.setInt(2, number);
                statement.setInt(3, app.draftRevision());
                statement.setString(4, store.json(snapshot));
                statement.setLong(5, positiveId(tests.get(0).requestId()));
                statement.setLong(6, positiveId(actor.subjectId()));
                return statement;
            }, keys);
            if (keys.getKey() == null) throw new AiRuntimeException(ErrorCode.INTERNAL_FAILURE);
            long versionId = keys.getKey().longValue();
            registerReferences(versionId, snapshot);
            jdbc.update("INSERT INTO sys_ai_application_cursor(app_version_id,next_index) VALUES(?,0)", versionId);
            activate(app, versionId);
            return new Version(Long.toString(versionId), app.appId(), number, app.draftRevision(), snapshot);
        });
    }

    public AiApplicationStore.View setEnabled(long appId, int revision, boolean enabled, TrustedActor actor) {
        return references.withReferenceGuard(() -> {
            var app = store.current(appId);
            expected(app, revision);
            if (enabled) {
                if (app.publishedVersionId() == null) throw new AiRuntimeException(ErrorCode.VERSION_CONFLICT);
                var version = version(appId, positiveId(app.publishedVersionId()), true);
                validateVersion(app, version, actor);
                ensureEntryAvailable(app);
            }
            jdbc.update("UPDATE sys_ai_application SET enabled=?,revision=revision+1,update_time=CURRENT_TIMESTAMP(3)"
                    + " WHERE app_id=?", enabled, appId);
            return store.current(appId);
        });
    }

    public AiApplicationStore.View rollback(long appId, int revision, long versionId, TrustedActor actor) {
        return references.withReferenceGuard(() -> {
            var app = store.current(appId);
            expected(app, revision);
            Version version = version(appId, versionId, true);
            validateVersion(app, version, actor);
            ensureEntryAvailable(app);
            activate(app, versionId);
            return store.current(appId);
        });
    }

    public List<Version> versions(long appId) {
        store.get(appId);
        return jdbc.query("SELECT * FROM sys_ai_application_version WHERE app_id=? ORDER BY version_number DESC",
                (rs, index) -> versionRow(rs), appId);
    }

    public record Health(String appId, int revision, String appVersionId, String state, ErrorCode reason, Instant checkedAt) {}

    /** Configuration dependency check for the current published entry; no render or provider execution. */
    public Health health(long appId, TrustedActor actor) {
        var app = store.get(appId);
        String state = "UNPUBLISHED";
        ErrorCode reason = null;
        if (app.publishedVersionId() != null) {
            if (!app.enabled()) state = "DISABLED";
            else {
                try {
                    validateVersion(app, version(appId, positiveId(app.publishedVersionId()), false), actor);
                    state = "AVAILABLE";
                } catch (AiRuntimeException unavailable) {
                    state = "UNAVAILABLE"; reason = unavailable.code();
                } catch (IllegalArgumentException invalid) {
                    state = "UNAVAILABLE"; reason = ErrorCode.INVALID_INPUT;
                }
            }
        }
        if (store.get(appId).revision() != app.revision()) throw new AiRuntimeException(ErrorCode.VERSION_CONFLICT);
        return new Health(app.appId(), app.revision(), app.publishedVersionId(), state, reason, Instant.now());
    }

    /** Refreshing the test panel only reads current-draft metadata; it never re-renders or calls A. */
    public List<EvidenceView> testEvidence(long appId) {
        var app = store.get(appId);
        var result = new ArrayList<EvidenceView>();
        // At most 16 candidates. One exact latest row per candidate cannot be crowded out by
        // hundreds of repeated tests of another model and never reads sample/identity fields.
        for (var binding : app.draft().models()) {
            result.addAll(jdbc.query("""
                    SELECT r.request_id,r.draft_revision,a.model_ref,a.config_version,r.status,r.finish_time
                      FROM sys_ai_request r JOIN sys_ai_request_attempt a ON a.request_id=r.request_id
                     WHERE r.app_id=? AND r.draft_revision=? AND r.request_kind='TEST'
                       AND r.status='SUCCEEDED' AND a.status='SUCCEEDED' AND a.model_ref=? AND a.config_version=?
                     ORDER BY r.request_id DESC LIMIT 1
                    """, (rs, index) -> new EvidenceView(Long.toString(rs.getLong(1)), rs.getInt(2),
                    new ModelVersionRef(rs.getString(3), rs.getString(4)), rs.getString(5), rs.getTimestamp(6).toInstant()),
                    appId, app.draftRevision(), binding.model().modelRef(), binding.model().configVersion()));
        }
        return List.copyOf(result);
    }

    public Version version(long appId, long versionId, boolean currentRead) {
        List<Version> rows = jdbc.query("SELECT * FROM sys_ai_application_version WHERE app_id=? AND app_version_id=?"
                        + (currentRead ? " FOR UPDATE" : ""), (rs, index) -> versionRow(rs), appId, versionId);
        if (rows.size() != 1) throw new AiRuntimeException(ErrorCode.NOT_FOUND);
        return rows.get(0);
    }

    private Version versionRow(java.sql.ResultSet rs) throws java.sql.SQLException {
        try {
            return new Version(Long.toString(rs.getLong("app_version_id")), Long.toString(rs.getLong("app_id")),
                    rs.getInt("version_number"), rs.getInt("draft_revision"),
                    mapper.readValue(rs.getString("snapshot_json"), Snapshot.class));
        } catch (JsonProcessingException invalidStoredData) {
            throw new AiRuntimeException(ErrorCode.INTERNAL_FAILURE);
        }
    }

    private List<TestEvidence> evidence(AiApplicationStore.View app, List<String> testIds) {
        if (testIds == null || testIds.isEmpty() || testIds.size() > 32 || new HashSet<>(testIds).size() != testIds.size()) {
            throw new AiRuntimeException(ErrorCode.INVALID_INPUT);
        }
        var tests = new ArrayList<TestEvidence>();
        for (String testId : testIds) {
            long id = positiveId(testId);
            List<TestEvidence> rows = jdbc.query("""
                    SELECT r.request_id,a.model_ref,a.config_version
                      FROM sys_ai_request r JOIN sys_ai_request_attempt a ON a.request_id=r.request_id
                     WHERE r.request_id=? AND r.app_id=? AND r.draft_revision=? AND r.request_kind='TEST'
                       AND r.status='SUCCEEDED' AND a.status='SUCCEEDED' FOR UPDATE
                    """, (rs, index) -> new TestEvidence(Long.toString(rs.getLong(1)),
                    new ModelVersionRef(rs.getString(2), rs.getString(3))), id, positiveId(app.appId()), app.draftRevision());
            if (rows.isEmpty()) throw new AiRuntimeException(ErrorCode.VERSION_CONFLICT);
            tests.addAll(rows);
        }
        Set<ModelVersionRef> covered = new HashSet<>();
        tests.forEach(test -> covered.add(test.model()));
        if (!covered.containsAll(app.draft().models().stream().map(AiProviderRuntime.ModelBinding::model).toList())) {
            throw new AiRuntimeException(ErrorCode.MODEL_UNAVAILABLE);
        }
        return List.copyOf(tests);
    }

    private Invocation testInvocation(AiApplicationStore.View app, String testId, TrustedActor actor) {
        return testInvocation(app, testId, actor, "draft:" + app.draftRevision());
    }

    private Invocation testInvocation(AiApplicationStore.View app, String testId, TrustedActor actor, String version) {
        List<BusinessRef> rows = jdbc.query("SELECT business_type,business_id FROM sys_ai_request"
                        + " WHERE request_id=? AND app_id=? AND request_kind='TEST' FOR UPDATE",
                (rs, index) -> new BusinessRef(rs.getString(1), rs.getString(2)), positiveId(testId), positiveId(app.appId()));
        if (rows.size() != 1) throw new AiRuntimeException(ErrorCode.VERSION_CONFLICT);
        return new Invocation(actor, app.appId(), version, rows.get(0), testId);
    }

    private void validateVersion(AiApplicationStore.View app, Version version, TrustedActor actor) {
        List<ResourceVersionRef> current = validate(version.snapshot().policy(), testInvocation(app,
                version.snapshot().tests().get(0).requestId(), actor, version.appVersionId()));
        if (!new HashSet<>(current).equals(new HashSet<>(version.snapshot().resources()))) {
            throw new AiRuntimeException(ErrorCode.VERSION_CONFLICT);
        }
    }

    private List<ResourceVersionRef> validate(AiApplicationPolicy policy, Invocation invocation) {
        policy.requireComplete();
        AiProviderRuntime provider = providers.getIfUnique();
        AiTemplateRuntime template = templates.getIfUnique();
        if (provider == null || template == null) throw new AiRuntimeException(ErrorCode.DEPENDENCY_UNAVAILABLE);
        InvocationControl control = new InvocationControl() {
            private final Instant deadline = Instant.now().plusSeconds(10);
            @Override public Instant deadline() { return deadline; }
            @Override public boolean cancelled() { return false; }
        };
        control.check();
        var modelValidation = provider.validateBindings(policy.models(), policy.requirements());
        if (!modelValidation.valid()) throw new AiRuntimeException(modelValidation.violations().get(0).code());
        var templateValidation = template.validateBinding(policy.template(), policy.inputNames(), invocation, control);
        if (!templateValidation.valid()) throw new AiRuntimeException(templateValidation.violations().get(0).code());
        var snapshot = template.publishedSnapshot(policy.template(), invocation, control);
        control.check();
        if (!snapshot.version().equals(policy.template())) throw new AiRuntimeException(ErrorCode.VERSION_CONFLICT);
        return snapshot.resources();
    }

    private void registerReferences(long versionId, Snapshot snapshot) {
        for (var binding : snapshot.policy().models()) {
            reference(versionId, "MODEL", binding.model().modelRef(), binding.model().configVersion());
        }
        reference(versionId, "TEMPLATE", snapshot.policy().template().templateId(),
                snapshot.policy().template().templateVersionId());
        for (ResourceVersionRef resource : new HashSet<>(snapshot.resources())) {
            reference(versionId, "RESOURCE", resource.resourceId(), resource.resourceVersionId());
        }
    }

    private void reference(long versionId, String kind, String id, String version) {
        jdbc.update("INSERT INTO sys_ai_application_reference"
                + "(app_version_id,reference_kind,reference_id,reference_version) VALUES(?,?,?,?)", versionId, kind, id, version);
    }

    private void ensureEntryAvailable(AiApplicationStore.View app) {
        if (!app.entryKey().startsWith("lowcode.record.summary/")) return;
        List<Long> existing = jdbc.query("SELECT app_id FROM sys_ai_application"
                        + " WHERE entry_key=? AND enabled=TRUE AND app_id<>? FOR UPDATE",
                (rs, index) -> rs.getLong(1), app.entryKey(), positiveId(app.appId()));
        if (!existing.isEmpty()) throw new AiRuntimeException(ErrorCode.VERSION_CONFLICT);
    }

    private void activate(AiApplicationStore.View app, long versionId) {
        jdbc.update("UPDATE sys_ai_application SET published_version_id=?,enabled=TRUE,revision=revision+1,"
                + "update_time=CURRENT_TIMESTAMP(3) WHERE app_id=?", versionId, positiveId(app.appId()));
    }

    private void expected(AiApplicationStore.View app, int revision) {
        if (app.revision() != revision) throw new AiRuntimeException(ErrorCode.VERSION_CONFLICT);
    }

    static long positiveId(String value) {
        try {
            if (value == null || !value.matches("[1-9][0-9]{0,18}")) throw new NumberFormatException();
            return Long.parseLong(value);
        } catch (NumberFormatException invalid) {
            throw new AiRuntimeException(ErrorCode.INVALID_INPUT);
        }
    }
}
