package com.xinghuiTec.ai.scene;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.xinghuiTec.ai.config.AiConfigurationDtos.Snapshot;
import com.xinghuiTec.ai.config.AiConfigurationException;
import com.xinghuiTec.ai.config.AiSettingsRotation;
import com.xinghuiTec.ai.contract.AiApplicationReferences.ApplicationReference;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.support.GeneratedKeyHolder;
import org.springframework.stereotype.Service;
import org.springframework.transaction.support.TransactionSynchronizationManager;

import java.util.*;

/**
 * Append compatible scene snapshots; never rewrite history or recompile
 * business data access.
 */
@Service
public class SceneSettingsRotation implements AiSettingsRotation {
    private final JdbcTemplate jdbc;
    private final ObjectMapper mapper;

    public SceneSettingsRotation(JdbcTemplate jdbc, ObjectMapper mapper) {
        this.jdbc = jdbc;
        this.mapper = mapper;
    }

    private record Plan(String scene, long sceneVersion, long app, long appVersion, int revision, int appRevision,
            int draftRevision, String definition, String snapshot, String policy) {
    }

    @Override
    public String rotate(Snapshot previous, Snapshot replacement, Set<String> provenModels,
            List<ApplicationReference> references, String actor, boolean apply) {
        if (!TransactionSynchronizationManager.isActualTransactionActive())
            throw new AiConfigurationException(503, "场景维护事务暂不可用");
        var models = previous.models().stream().map(m -> m.modelRef()).collect(java.util.stream.Collectors.toSet());
        // Include disabled but currently managed scenes so re-enabling cannot resurrect
        // an old credential.
        var currentReferences = new ArrayList<ApplicationReference>(references);
        var current = jdbc.query("""
                SELECT a.app_id,a.published_version_id,a.name,r.reference_id
                FROM sys_ai_scene s JOIN sys_ai_application a ON a.app_id=s.app_id
                JOIN sys_ai_application_reference r ON r.app_version_id=a.published_version_id
                WHERE r.reference_kind='MODEL' AND r.reference_version=? FOR UPDATE
                """, (rs, n) -> new String[]{rs.getString(1), rs.getString(2), rs.getString(3), rs.getString(4)},
                previous.configVersion());
        for (var row : current)
            if (models.contains(row[3]))
                currentReferences.add(new ApplicationReference(row[0], row[1], row[2]));
        var plans = new ArrayList<Plan>();
        for (var reference : currentReferences.stream().distinct()
                .sorted(Comparator.comparing(ApplicationReference::appId)).toList()) {
            var rows = jdbc.queryForList(
                    """
                            SELECT s.scene_key,s.current_version_id,s.revision,a.app_id,a.published_version_id,
                                   a.revision AS app_revision,a.draft_revision,a.draft_json,v.definition_json,v.compiled_json,av.snapshot_json
                            FROM sys_ai_scene s JOIN sys_ai_application a ON a.app_id=s.app_id
                            JOIN sys_ai_scene_version v ON v.scene_version_id=s.current_version_id
                            JOIN sys_ai_application_version av ON av.app_version_id=v.app_version_id
                            WHERE a.app_id=? AND a.published_version_id=v.app_version_id
                              AND a.published_version_id=?
                            FOR UPDATE
                            """,
                    reference.appId(), reference.appVersionId());
            if (rows.size() != 1)
                throw blocked();
            var row = rows.get(0);
            if (row.get("compiled_json") == null || row.get("definition_json") == null)
                throw blocked();
            // Reading proves that a compiled object exists; its exact stored bytes are
            // copied below.
            if (!read(text(row, "compiled_json")).isObject())
                throw blocked();
            var definition = read(text(row, "definition_json"));
            var snapshot = read(text(row, "snapshot_json"));
            var policy = snapshot.path("policy");
            if (!read(text(row, "draft_json")).equals(policy))
                throw blocked();
            var sceneBindings = definition.path("modelPolicy").path("models");
            var policyBindings = policy.path("models");
            if (!sceneBindings.equals(policyBindings))
                throw blocked();
            rewrite(sceneBindings, previous.configVersion(), replacement.configVersion(), models, provenModels);
            rewrite(policyBindings, previous.configVersion(), replacement.configVersion(), models, provenModels);
            plans.add(new Plan(text(row, "scene_key"), number(row, "current_version_id"), number(row, "app_id"),
                    number(row, "published_version_id"), (int) number(row, "revision"),
                    (int) number(row, "app_revision"), (int) number(row, "draft_revision"), write(definition),
                    write(snapshot), write(policy)));
        }
        // Validate the entire set before appending anything. Any failure also rolls
        // back the outer activation.
        if (!apply)
            return "[]";
        if (actor == null || !actor.matches("[1-9][0-9]{0,18}"))
            throw blocked();
        var receipts = new ArrayList<Map<String, String>>();
        for (var plan : plans) {
            int appNumber = jdbc.queryForObject(
                    "SELECT COALESCE(MAX(version_number),0)+1 FROM sys_ai_application_version WHERE app_id=?",
                    Integer.class, plan.app());
            int sceneNumber = jdbc.queryForObject(
                    "SELECT COALESCE(MAX(version_number),0)+1 FROM sys_ai_scene_version WHERE scene_key=?",
                    Integer.class, plan.scene());
            long appVersion = insert("app_version_id", """
                    INSERT INTO sys_ai_application_version(app_id,version_number,draft_revision,snapshot_json,
                        tested_request_id,created_by,snapshot_source)
                    SELECT app_id,?,
                           ?,?,NULL,?,'SCENE_SAVE' FROM sys_ai_application_version WHERE app_version_id=?
                    """, appNumber, plan.draftRevision() + 1, plan.snapshot(), actor, plan.appVersion());
            jdbc.update("INSERT INTO sys_ai_application_cursor(app_version_id,next_index) VALUES(?,0)", appVersion);
            var refs = jdbc.queryForList(
                    "SELECT reference_kind,reference_id,reference_version FROM sys_ai_application_reference WHERE app_version_id=?",
                    plan.appVersion());
            for (var ref : refs) {
                String version = text(ref, "reference_version");
                if ("MODEL".equals(text(ref, "reference_kind")) && previous.configVersion().equals(version)
                        && models.contains(text(ref, "reference_id")))
                    version = replacement.configVersion();
                jdbc.update(
                        "INSERT INTO sys_ai_application_reference(app_version_id,reference_kind,reference_id,reference_version) VALUES(?,?,?,?)",
                        appVersion, ref.get("reference_kind"), ref.get("reference_id"), version);
            }
            long sceneVersion = insert("scene_version_id", """
                    INSERT INTO sys_ai_scene_version(scene_key,version_number,saved_revision,app_version_id,
                        definition_json,compiled_json,source,legacy_template_id,legacy_template_version_id,created_by)
                    SELECT scene_key,?,
                           ?,?,?,compiled_json,source,legacy_template_id,legacy_template_version_id,?
                    FROM sys_ai_scene_version WHERE scene_version_id=?
                    """, sceneNumber, plan.revision() + 1, appVersion, plan.definition(), actor, plan.sceneVersion());
            if (jdbc.update(
                    "UPDATE sys_ai_application SET published_version_id=?,draft_json=?,draft_revision=draft_revision+1,revision=revision+1,update_time=CURRENT_TIMESTAMP WHERE app_id=? AND revision=? AND published_version_id=?",
                    appVersion, plan.policy(), plan.app(), plan.appRevision(), plan.appVersion()) != 1)
                throw blocked();
            if (jdbc.update(
                    "UPDATE sys_ai_scene SET current_version_id=?,revision=revision+1,update_time=CURRENT_TIMESTAMP WHERE scene_key=? AND revision=? AND current_version_id=?",
                    sceneVersion, plan.scene(), plan.revision(), plan.sceneVersion()) != 1)
                throw blocked();
            receipts.add(Map.of("sceneKey", plan.scene(), "previousSceneVersion", Long.toString(plan.sceneVersion()),
                    "sceneVersion", Long.toString(sceneVersion), "applicationVersion", Long.toString(appVersion)));
        }
        return write(mapper.valueToTree(receipts));
    }

    private void rewrite(JsonNode bindings, String previous, String next, Set<String> models, Set<String> proven) {
        if (!bindings.isArray() || bindings.isEmpty())
            throw blocked();
        boolean found = false;
        for (var binding : bindings) {
            var model = binding.path("model");
            if (!previous.equals(model.path("configVersion").asText())
                    || !models.contains(model.path("modelRef").asText()))
                continue;
            found = true;
            if (!"AUTO".equals(binding.path("keyMode").asText()) || !binding.path("allowedKeys").isArray()
                    || !binding.path("allowedKeys").isEmpty()
                    || !binding.path("fixedKey").isNull() && !binding.path("fixedKey").isMissingNode()
                    || !binding.path("fallbackKeys").isArray() || !binding.path("fallbackKeys").isEmpty())
                throw blocked();
            if (!proven.contains(model.path("modelRef").asText()))
                throw new AiConfigurationException(409, "新Key未通过正在使用的全部模型验证，原连接保持不变");
            ((ObjectNode) model).put("configVersion", next);
        }
        if (!found)
            throw blocked();
    }

    private static AiConfigurationException blocked() {
        return new AiConfigurationException(409, "现有提示词配置无法安全自动更换Key，原连接保持不变，请联系管理员检查");
    }
    private JsonNode read(String value) {
        try {
            return mapper.readTree(value);
        } catch (Exception invalid) {
            throw blocked();
        }
    }
    private String write(JsonNode value) {
        try {
            return mapper.writeValueAsString(value);
        } catch (Exception invalid) {
            throw blocked();
        }
    }
    private static String text(Map<String, Object> row, String key) {
        return Objects.toString(row.get(key), null);
    }
    private static long number(Map<String, Object> row, String key) {
        return ((Number) row.get(key)).longValue();
    }
    private long insert(String column, String sql, Object... args) {
        var keys = new GeneratedKeyHolder();
        jdbc.update(c -> {
            var statement = c.prepareStatement(sql, new String[]{column});
            for (int i = 0; i < args.length; i++)
                statement.setObject(i + 1, args[i]);
            return statement;
        }, keys);
        if (keys.getKey() == null)
            throw blocked();
        return keys.getKey().longValue();
    }
}
