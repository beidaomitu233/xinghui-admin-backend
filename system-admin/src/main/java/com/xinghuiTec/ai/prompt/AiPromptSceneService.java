package com.xinghuiTec.ai.prompt;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.xinghuiTec.ai.prompt.AiPromptSceneRules.VariableDefinition;
import com.xinghuiTec.exception.ServiceException;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.RowMapper;
import org.springframework.jdbc.support.GeneratedKeyHolder;
import org.springframework.jdbc.support.KeyHolder;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.sql.PreparedStatement;
import java.sql.Timestamp;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/** Platform configuration for cross-application prompt scenes; it never reads business data or calls a model. */
@Service
public class AiPromptSceneService {
    private static final String TABLE = "sys_ai_prompt_scene";
    private static final String DETAIL_SELECT = """
            SELECT s.scene_id,s.app_key,s.scene_key,s.draft_model_id,
                   dm.provider_code AS draft_provider_code,dm.model_code AS draft_model_code,
                   dm.enabled AS draft_model_enabled,s.draft_prompt,s.draft_variables_json,s.draft_revision,
                   s.published_model_id,pm.provider_code AS published_provider_code,
                   pm.model_code AS published_model_code,pm.enabled AS published_model_enabled,
                   s.published_prompt,s.published_variables_json,s.published_revision,s.enabled,s.revision,
                   s.create_time,s.update_time
              FROM sys_ai_prompt_scene s
              LEFT JOIN sys_ai_model_config dm ON dm.model_id=s.draft_model_id
              LEFT JOIN sys_ai_model_config pm ON pm.model_id=s.published_model_id
            """;

    private final JdbcTemplate jdbc;
    private final ObjectMapper objectMapper;
    private final AiPromptSceneRules rules;

    public AiPromptSceneService(JdbcTemplate jdbc, ObjectMapper objectMapper, AiPromptSceneRules rules) {
        this.jdbc = jdbc;
        this.objectMapper = objectMapper;
        this.rules = rules;
    }

    public record CreateRequest(String appKey, String sceneKey, Long modelId,
                                String prompt, List<VariableDefinition> variables) {}

    public record DraftRequest(Long modelId, String prompt,
                               List<VariableDefinition> variables, Integer revision) {}

    public record RevisionRequest(Integer revision) {}

    public record StatusRequest(Boolean enabled, Integer revision) {}

    public record PreviewRequest(Integer revision, Map<String, String> values) {}

    public record ModelOption(long id, String providerCode, String modelCode) {}

    public record Summary(long id, String appKey, String sceneKey,
                          long draftModelId, String draftProviderCode, String draftModelCode,
                          boolean draftModelEnabled, Long publishedModelId,
                          String publishedProviderCode, String publishedModelCode,
                          boolean publishedModelEnabled, boolean published,
                          int draftRevision, Integer publishedRevision, boolean enabled,
                          int revision, java.time.LocalDateTime updateTime) {}

    public record Page(List<Summary> records, long total, int pageNum, int pageSize) {}

    public record View(long id, String appKey, String sceneKey,
                       long draftModelId, String draftProviderCode, String draftModelCode,
                       boolean draftModelEnabled, String draftPrompt, List<VariableDefinition> draftVariables,
                       int draftRevision, Long publishedModelId, String publishedProviderCode,
                       String publishedModelCode, boolean publishedModelEnabled,
                       String publishedPrompt, List<VariableDefinition> publishedVariables,
                       Integer publishedRevision, boolean published, boolean enabled, int revision,
                       java.time.LocalDateTime createTime, java.time.LocalDateTime updateTime) {}

    public record PreviewView(String prompt, long modelId, String providerCode,
                              String modelCode, int draftRevision) {}

    /** Service-only surface for future internal consumers; no tenant HTTP read route is exposed in AI-02. */
    public record PublishedScene(String appKey, String sceneKey, long modelId,
                                 String promptTemplate, List<VariableDefinition> variables,
                                 int publishedRevision) {}

    public Page page(Integer requestedPage, Integer requestedSize, String requestedAppKey,
                     String requestedSceneKey, Boolean enabled, Boolean published) {
        return page(requestedPage,requestedSize,requestedAppKey,requestedSceneKey,enabled,published,null,null);
    }
    public Page page(Integer requestedPage, Integer requestedSize, String requestedAppKey,
                     String requestedSceneKey, Boolean enabled, Boolean published,String orderBy,String order) {
        String ordering=com.xinghuiTec.ai.AiListOrder.clause(orderBy,order,Map.of(
                "appKey","s.app_key","sceneKey","s.scene_key","enabled","s.enabled",
                "draftRevision","s.draft_revision","publishedRevision","s.published_revision","updateTime","s.update_time"),
                "s.scene_id DESC","s.scene_id");
        int pageNum = requestedPage == null ? 1 : requestedPage;
        int pageSize = requestedSize == null ? 20 : requestedSize;
        if (pageNum < 1 || pageNum > 100_000) {
            throw new ServiceException("页码须为 1～100000");
        }
        if (pageSize < 1 || pageSize > 100) {
            throw new ServiceException("每页条数须为 1～100");
        }
        String appKey = requestedAppKey == null || requestedAppKey.isBlank()
                ? null : rules.appKey(requestedAppKey);
        String sceneKey = requestedSceneKey == null || requestedSceneKey.isBlank()
                ? null : rules.sceneKey(requestedSceneKey);

        StringBuilder where = new StringBuilder();
        List<Object> args = new ArrayList<>();
        if (appKey != null) {
            addPredicate(where, "s.app_key=?");
            args.add(appKey);
        }
        if (sceneKey != null) {
            addPredicate(where, "s.scene_key=?");
            args.add(sceneKey);
        }
        if (enabled != null) {
            addPredicate(where, "s.enabled=?");
            args.add(enabled);
        }
        if (published != null) {
            addPredicate(where, published ? "s.published_revision IS NOT NULL" : "s.published_revision IS NULL");
        }

        String from = " FROM sys_ai_prompt_scene s"
                + " LEFT JOIN sys_ai_model_config dm ON dm.model_id=s.draft_model_id"
                + " LEFT JOIN sys_ai_model_config pm ON pm.model_id=s.published_model_id"
                + where;
        Long total = jdbc.queryForObject("SELECT COUNT(*)" + from, Long.class, args.toArray());
        List<Object> pageArgs = new ArrayList<>(args);
        pageArgs.add(pageSize);
        pageArgs.add((pageNum - 1) * pageSize);
        List<Summary> records = jdbc.query("""
                SELECT s.scene_id,s.app_key,s.scene_key,s.draft_model_id,
                       dm.provider_code AS draft_provider_code,dm.model_code AS draft_model_code,
                       dm.enabled AS draft_model_enabled,s.published_model_id,
                       pm.provider_code AS published_provider_code,pm.model_code AS published_model_code,
                       pm.enabled AS published_model_enabled,s.published_revision,s.draft_revision,
                       s.enabled,s.revision,s.update_time
                """ + from + ordering + " LIMIT ? OFFSET ?", summaryMapper(), pageArgs.toArray());
        return new Page(records, total == null ? 0 : total, pageNum, pageSize);
    }

    public List<ModelOption> availableModels() {
        return jdbc.query("""
                SELECT model_id,provider_code,model_code
                  FROM sys_ai_model_config
                 WHERE enabled=TRUE
                 ORDER BY provider_code,model_code
                """, (rs, row) -> new ModelOption(rs.getLong("model_id"),
                rs.getString("provider_code"), rs.getString("model_code")));
    }

    public View get(long id) {
        if (id <= 0) {
            throw new ServiceException("场景不存在");
        }
        List<View> rows = jdbc.query(DETAIL_SELECT + " WHERE s.scene_id=?", viewMapper(), id);
        if (rows.isEmpty()) {
            throw new ServiceException("场景不存在");
        }
        return rows.get(0);
    }

    @Transactional
    public long create(CreateRequest request) {
        request = requireRequest(request);
        String appKey = rules.appKey(request.appKey());
        String sceneKey = rules.sceneKey(request.sceneKey());
        ModelOption model = lockEnabledModel(request.modelId());
        String prompt = rules.prompt(request.prompt());
        List<VariableDefinition> variables = rules.definitions(request.variables());
        String variablesJson = writeVariables(variables);
        KeyHolder keys = new GeneratedKeyHolder();
        try {
            jdbc.update(connection -> {
                PreparedStatement statement = connection.prepareStatement("""
                        INSERT INTO sys_ai_prompt_scene
                          (app_key,scene_key,draft_model_id,draft_prompt,draft_variables_json,
                           draft_revision,enabled,revision)
                        VALUES (?,?,?,?,?,1,FALSE,0)
                        """, new String[] {"scene_id"});
                statement.setString(1, appKey);
                statement.setString(2, sceneKey);
                statement.setLong(3, model.id());
                statement.setString(4, prompt);
                statement.setString(5, variablesJson);
                return statement;
            }, keys);
        } catch (DuplicateKeyException duplicate) {
            throw new ServiceException("该应用下的场景标识已存在");
        }
        Number id = keys.getKey();
        if (id == null) {
            throw new ServiceException("新增场景失败");
        }
        return id.longValue();
    }

    @Transactional
    public View updateDraft(long id, DraftRequest request) {
        request = requireRequest(request);
        int revision = checkedRevision(request.revision());
        View current = get(id);
        requireRevision(current.revision(), revision);
        ModelOption model = lockEnabledModel(request.modelId());
        String prompt = rules.prompt(request.prompt());
        List<VariableDefinition> variables = rules.definitions(request.variables());
        int changed = jdbc.update("""
                UPDATE sys_ai_prompt_scene
                   SET draft_model_id=?,draft_prompt=?,draft_variables_json=?,draft_revision=draft_revision+1,
                       revision=revision+1,update_time=CURRENT_TIMESTAMP
                 WHERE scene_id=? AND revision=?
                """, model.id(), prompt, writeVariables(variables), id, revision);
        if (changed != 1) {
            throwConflictOrMissing(id);
        }
        return get(id);
    }

    @Transactional
    public View publish(long id, RevisionRequest request) {
        request = requireRequest(request);
        int revision = checkedRevision(request.revision());
        View current = get(id);
        requireRevision(current.revision(), revision);
        lockEnabledModel(current.draftModelId());
        rules.validateTemplate(current.draftPrompt(), current.draftVariables());
        int changed = jdbc.update("""
                UPDATE sys_ai_prompt_scene
                   SET published_model_id=draft_model_id,published_prompt=draft_prompt,
                       published_variables_json=draft_variables_json,published_revision=draft_revision,
                       revision=revision+1,update_time=CURRENT_TIMESTAMP
                 WHERE scene_id=? AND revision=?
                """, id, revision);
        if (changed != 1) {
            throwConflictOrMissing(id);
        }
        return get(id);
    }

    public PreviewView preview(long id, PreviewRequest request) {
        request = requireRequest(request);
        View current = get(id);
        requireRevision(current.revision(), checkedRevision(request.revision()));
        if (!current.draftModelEnabled()) {
            throw new ServiceException("草稿关联模型已停用，请改选已启用模型后再预览");
        }
        rules.validateTemplate(current.draftPrompt(), current.draftVariables());
        String rendered = rules.render(current.draftPrompt(), current.draftVariables(), request.values());
        return new PreviewView(rendered, current.draftModelId(), current.draftProviderCode(),
                current.draftModelCode(), current.draftRevision());
    }

    @Transactional
    public View setEnabled(long id, StatusRequest request) {
        request = requireRequest(request);
        int revision = checkedRevision(request.revision());
        if (request.enabled() == null) {
            throw new ServiceException("场景状态不能为空");
        }
        View current = get(id);
        requireRevision(current.revision(), revision);
        if (request.enabled()) {
            if (current.publishedRevision() == null || current.publishedPrompt() == null) {
                throw new ServiceException("场景须先通过校验并发布后才能启用");
            }
            lockEnabledModel(current.publishedModelId());
            rules.validateTemplate(current.publishedPrompt(), current.publishedVariables());
        }
        int changed = jdbc.update("""
                UPDATE sys_ai_prompt_scene
                   SET enabled=?,revision=revision+1,update_time=CURRENT_TIMESTAMP
                 WHERE scene_id=? AND revision=?
                """, request.enabled(), id, revision);
        if (changed != 1) {
            throwConflictOrMissing(id);
        }
        return get(id);
    }

    @Transactional
    public void delete(long id, int requestedRevision) {
        int revision = checkedRevision(requestedRevision);
        View current = get(id);
        requireRevision(current.revision(), revision);
        if (current.enabled()) {
            throw new ServiceException("启用中的场景不能删除，请先停用");
        }
        try {
            int deleted = jdbc.update("""
                    DELETE FROM sys_ai_prompt_scene
                     WHERE scene_id=? AND enabled=FALSE AND revision=?
                    """, id, revision);
            if (deleted != 1) {
                throwConflictOrMissing(id);
            }
        } catch (DataIntegrityViolationException referenced) {
            throw new ServiceException("该场景已被引用，不能删除");
        }
    }

    /** Internal-only contract for a future runtime: enabled, published and model-active scenes only. */
    public PublishedScene publishedForUse(String requestedAppKey, String requestedSceneKey) {
        String appKey = rules.appKey(requestedAppKey);
        String sceneKey = rules.sceneKey(requestedSceneKey);
        List<View> rows = jdbc.query(DETAIL_SELECT + " WHERE s.app_key=? AND s.scene_key=?",
                viewMapper(), appKey, sceneKey);
        if (rows.isEmpty()) {
            throw new ServiceException("场景不存在");
        }
        View current = rows.get(0);
        if (!current.enabled()) {
            throw new ServiceException("场景当前未启用");
        }
        if (current.publishedRevision() == null || current.publishedPrompt() == null
                || current.publishedModelId() == null) {
            throw new ServiceException("场景尚未发布");
        }
        if (!current.publishedModelEnabled()) {
            throw new ServiceException("已发布场景关联的模型已停用");
        }
        rules.validateTemplate(current.publishedPrompt(), current.publishedVariables());
        return new PublishedScene(current.appKey(), current.sceneKey(), current.publishedModelId(),
                current.publishedPrompt(), current.publishedVariables(), current.publishedRevision());
    }

    private ModelOption lockEnabledModel(Long modelId) {
        if (modelId == null || modelId <= 0) {
            throw new ServiceException("只能选择当前已启用的模型");
        }
        List<ModelOption> models = jdbc.query("""
                SELECT model_id,provider_code,model_code
                  FROM sys_ai_model_config
                 WHERE model_id=? AND enabled=TRUE
                 FOR UPDATE
                """, (rs, row) -> new ModelOption(rs.getLong("model_id"),
                rs.getString("provider_code"), rs.getString("model_code")), modelId);
        if (models.isEmpty()) {
            throw new ServiceException("只能选择当前已启用的模型");
        }
        return models.get(0);
    }

    private void requireRevision(int current, int requested) {
        if (current != requested) {
            throw new ServiceException("场景已被修改，请刷新后重试");
        }
    }

    private void throwConflictOrMissing(long id) {
        List<Integer> revisions = jdbc.query("SELECT revision FROM " + TABLE + " WHERE scene_id=?",
                (rs, row) -> rs.getInt(1), id);
        if (revisions.isEmpty()) {
            throw new ServiceException("场景不存在");
        }
        throw new ServiceException("场景已被修改，请刷新后重试");
    }

    private int checkedRevision(Integer revision) {
        if (revision == null || revision < 0) {
            throw new ServiceException("场景修订号无效");
        }
        return revision;
    }

    private <T> T requireRequest(T request) {
        if (request == null) {
            throw new ServiceException("请求数据不能为空");
        }
        return request;
    }

    private String writeVariables(List<VariableDefinition> variables) {
        try {
            return objectMapper.writeValueAsString(variables);
        } catch (JsonProcessingException error) {
            throw new ServiceException("变量声明无法保存");
        }
    }

    private List<VariableDefinition> readVariables(String json) {
        if (json == null || json.isBlank()) {
            return List.of();
        }
        try {
            return objectMapper.readValue(json, new TypeReference<>() {});
        } catch (JsonProcessingException error) {
            throw new ServiceException("场景变量声明数据无效");
        }
    }

    private RowMapper<Summary> summaryMapper() {
        return (rs, row) -> new Summary(rs.getLong("scene_id"), rs.getString("app_key"),
                rs.getString("scene_key"), rs.getLong("draft_model_id"),
                rs.getString("draft_provider_code"), rs.getString("draft_model_code"),
                rs.getBoolean("draft_model_enabled"), nullableLong(rs.getObject("published_model_id")),
                rs.getString("published_provider_code"), rs.getString("published_model_code"),
                rs.getBoolean("published_model_enabled"), rs.getObject("published_revision") != null,
                rs.getInt("draft_revision"), nullableInt(rs.getObject("published_revision")),
                rs.getBoolean("enabled"), rs.getInt("revision"), timestamp(rs.getTimestamp("update_time")));
    }

    private RowMapper<View> viewMapper() {
        return (rs, row) -> new View(rs.getLong("scene_id"), rs.getString("app_key"),
                rs.getString("scene_key"), rs.getLong("draft_model_id"),
                rs.getString("draft_provider_code"), rs.getString("draft_model_code"),
                rs.getBoolean("draft_model_enabled"), rs.getString("draft_prompt"),
                readVariables(rs.getString("draft_variables_json")), rs.getInt("draft_revision"),
                nullableLong(rs.getObject("published_model_id")), rs.getString("published_provider_code"),
                rs.getString("published_model_code"), rs.getBoolean("published_model_enabled"),
                rs.getString("published_prompt"), readVariables(rs.getString("published_variables_json")),
                nullableInt(rs.getObject("published_revision")), rs.getObject("published_revision") != null,
                rs.getBoolean("enabled"), rs.getInt("revision"),
                timestamp(rs.getTimestamp("create_time")), timestamp(rs.getTimestamp("update_time")));
    }

    private void addPredicate(StringBuilder where, String predicate) {
        where.append(where.isEmpty() ? " WHERE " : " AND ").append(predicate);
    }

    private Long nullableLong(Object value) {
        return value == null ? null : ((Number) value).longValue();
    }

    private Integer nullableInt(Object value) {
        return value == null ? null : ((Number) value).intValue();
    }

    private java.time.LocalDateTime timestamp(Timestamp value) {
        return value == null ? null : value.toLocalDateTime();
    }
}
