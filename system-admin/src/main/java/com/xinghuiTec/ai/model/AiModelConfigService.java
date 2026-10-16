package com.xinghuiTec.ai.model;

import com.xinghuiTec.exception.ServiceException;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.RowMapper;
import org.springframework.jdbc.support.GeneratedKeyHolder;
import org.springframework.jdbc.support.KeyHolder;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;

import java.sql.PreparedStatement;
import java.sql.Statement;
import java.sql.Timestamp;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;

/** Platform-owned metadata only. This service never resolves environment variables or contacts endpoints. */
@Service
public class AiModelConfigService {
    private static final String TABLE = "sys_ai_model_config";
    private static final String COLUMNS = "model_id,provider_code,model_code,endpoint_url,"
            + "credential_env_name,credential_mode,CASE WHEN credential_ciphertext IS NOT NULL THEN 1 ELSE 0 END AS has_cipher,timeout_seconds,enabled,revision,create_time,update_time";

    private final JdbcTemplate jdbc;
    private final AiModelConfigRules rules;

    public AiModelConfigService(JdbcTemplate jdbc, AiModelConfigRules rules) {
        this.jdbc = jdbc;
        this.rules = rules;
    }

    public record View(long id, String providerCode, String modelCode, String endpointUrl,
                       String credentialEnvName, int timeoutSeconds, boolean enabled,
                       int revision, java.time.LocalDateTime createTime,
                       java.time.LocalDateTime updateTime, String credentialMode, boolean credentialConfigured) {
        public View(long id, String providerCode, String modelCode, String endpointUrl,
                    String credentialEnvName, int timeoutSeconds, boolean enabled, int revision,
                    java.time.LocalDateTime createTime, java.time.LocalDateTime updateTime) {
            this(id,providerCode,modelCode,endpointUrl,credentialEnvName,timeoutSeconds,enabled,revision,createTime,updateTime,"ENV",false);
        }
    }

    public record Page(List<View> records, long total, int pageNum, int pageSize) {}

    public record CreateRequest(
            @NotBlank @Size(max = 64) @Pattern(regexp = "[a-z][a-z0-9.-]{0,63}") String providerCode,
            @NotBlank @Size(max = 128) @Pattern(regexp = "[A-Za-z0-9][A-Za-z0-9._:/-]{0,127}") String modelCode,
            @NotBlank @Size(max = 2048) String endpointUrl,
            @Size(max = 128) @Pattern(regexp = "(?:[A-Z][A-Z0-9]*(?:_[A-Z0-9]+)+)?") String credentialEnvName,
            @NotNull @Min(1) @Max(300) Integer timeoutSeconds,
            @NotNull Boolean enabled) {}

    public record UpdateRequest(
            @NotBlank @Size(max = 64) @Pattern(regexp = "[a-z][a-z0-9.-]{0,63}") String providerCode,
            @NotBlank @Size(max = 128) @Pattern(regexp = "[A-Za-z0-9][A-Za-z0-9._:/-]{0,127}") String modelCode,
            @NotBlank @Size(max = 2048) String endpointUrl,
            @Size(max = 128) @Pattern(regexp = "(?:[A-Z][A-Z0-9]*(?:_[A-Z0-9]+)+)?") String credentialEnvName,
            @NotNull @Min(1) @Max(300) Integer timeoutSeconds,
            @NotNull Boolean enabled,
            @NotNull @Min(0) Integer revision) {}

    public record StatusRequest(@NotNull Boolean enabled, @NotNull @Min(0) Integer revision) {}

    private record Values(String providerCode, String modelCode, String endpointUrl,
                          String credentialEnvName, int timeoutSeconds, boolean enabled) {}

    public Page page(Integer requestedPage, Integer requestedSize,
                     String requestedProvider, String requestedModel, Boolean enabled) {
        int pageNum = requestedPage == null ? 1 : requestedPage;
        int pageSize = requestedSize == null ? 20 : requestedSize;
        if (pageNum < 1 || pageNum > 100_000) {
            throw new ServiceException("页码须为 1～100000");
        }
        if (pageSize < 1 || pageSize > 100) {
            throw new ServiceException("每页条数须为 1～100");
        }

        String provider = requestedProvider == null || requestedProvider.isBlank()
                ? null : rules.providerCode(requestedProvider);
        String model = requestedModel == null || requestedModel.isBlank()
                ? null : rules.modelCode(requestedModel);
        StringBuilder from = new StringBuilder(" FROM ").append(TABLE);
        List<String> predicates = new ArrayList<>();
        List<Object> args = new ArrayList<>();
        if (provider != null) {
            predicates.add("provider_code=?");
            args.add(provider);
        }
        if (model != null) {
            predicates.add("model_code=?");
            args.add(model);
        }
        if (enabled != null) {
            predicates.add("enabled=?");
            args.add(enabled);
        }
        if (!predicates.isEmpty()) {
            from.append(" WHERE ").append(String.join(" AND ", predicates));
        }

        Long total = jdbc.queryForObject("SELECT COUNT(*)" + from, Long.class, args.toArray());
        List<Object> pageArgs = new ArrayList<>(args);
        pageArgs.add(pageSize);
        pageArgs.add((pageNum - 1) * pageSize);
        List<View> records = jdbc.query("SELECT " + COLUMNS + from
                        + " ORDER BY model_id DESC LIMIT ? OFFSET ?", rowMapper(), pageArgs.toArray());
        return new Page(records, total == null ? 0 : total, pageNum, pageSize);
    }

    public View get(long id) {
        return require(id);
    }

    @Transactional
    public long create(CreateRequest request) {
        Values values = validate(request.providerCode(), request.modelCode(), request.endpointUrl(),
                request.credentialEnvName(), request.timeoutSeconds(), request.enabled());
        KeyHolder keys = new GeneratedKeyHolder();
        try {
            jdbc.update(connection -> {
                PreparedStatement statement = connection.prepareStatement("""
                        INSERT INTO sys_ai_model_config
                          (provider_code,model_code,endpoint_url,credential_env_name,timeout_seconds,enabled)
                        VALUES (?,?,?,?,?,?)
                        """, new String[] {"model_id"});
                bind(statement, values, 1);
                return statement;
            }, keys);
        } catch (DuplicateKeyException duplicate) {
            throw new ServiceException("该供应方下的模型代号已存在");
        }
        Number id = keys.getKey();
        if (id == null) {
            throw new ServiceException("新增模型配置失败");
        }
        return id.longValue();
    }

    @Transactional
    public View update(long id, UpdateRequest request) {
        Values values = validate(request.providerCode(), request.modelCode(), request.endpointUrl(),
                request.credentialEnvName(), request.timeoutSeconds(), request.enabled());
        int revision = rules.revision(request.revision());
        try {
            int changed = jdbc.update("""
                    UPDATE sys_ai_model_config
                       SET provider_code=?,model_code=?,endpoint_url=?,credential_env_name=?,timeout_seconds=?,
                           enabled=?,revision=revision+1,update_time=CURRENT_TIMESTAMP
                     WHERE model_id=? AND revision=?
                    """, values.providerCode(), values.modelCode(), values.endpointUrl(),
                    values.credentialEnvName(), values.timeoutSeconds(), values.enabled(), id, revision);
            if (changed != 1) {
                throwUpdateConflictOrMissing(id, revision);
            }
        } catch (DuplicateKeyException duplicate) {
            throw new ServiceException("该供应方下的模型代号已存在");
        }
        return require(id);
    }

    @Transactional
    public View setEnabled(long id, StatusRequest request) {
        if (request.enabled() == null) {
            throw new ServiceException("模型状态不能为空");
        }
        int revision = rules.revision(request.revision());
        int changed = jdbc.update("""
                UPDATE sys_ai_model_config
                   SET enabled=?,revision=revision+1,update_time=CURRENT_TIMESTAMP
                 WHERE model_id=? AND revision=?
                """, request.enabled(), id, revision);
        if (changed != 1) {
            throwUpdateConflictOrMissing(id, revision);
        }
        return require(id);
    }

    @Transactional
    public void delete(long id, int expectedRevision) {
        int revision = rules.revision(expectedRevision);
        View current = require(id);
        if (current.revision() != revision) {
            throw new ServiceException("配置已被修改，请刷新后重试");
        }
        if (current.enabled()) {
            throw new ServiceException("启用中的模型不能删除，请先停用");
        }
        try {
            int deleted = jdbc.update("""
                    DELETE FROM sys_ai_model_config
                     WHERE model_id=? AND enabled=FALSE AND revision=?
                    """, id, revision);
            if (deleted != 1) {
                throwUpdateConflictOrMissing(id, revision);
            }
        } catch (DataIntegrityViolationException referenced) {
            throw new ServiceException("该模型已被场景引用，不能删除；请先解除引用");
        }
    }

    private View require(long id) {
        if (id <= 0) {
            throw new ServiceException("模型配置不存在");
        }
        List<View> rows = jdbc.query("SELECT " + COLUMNS + " FROM " + TABLE + " WHERE model_id=?",
                rowMapper(), id);
        if (rows.isEmpty()) {
            throw new ServiceException("模型配置不存在");
        }
        return rows.get(0);
    }

    private void throwUpdateConflictOrMissing(long id, int revision) {
        if (id <= 0 || jdbc.query("SELECT model_id FROM " + TABLE + " WHERE model_id=?", (rs, row) -> rs.getLong(1), id).isEmpty()) {
            throw new ServiceException("模型配置不存在");
        }
        if (jdbc.query("SELECT model_id FROM " + TABLE + " WHERE model_id=? AND revision=?",
                (rs, row) -> rs.getLong(1), id, revision).isEmpty()) {
            throw new ServiceException("配置已被修改，请刷新后重试");
        }
        throw new ServiceException("模型配置更新冲突，请刷新后重试");
    }

    private Values validate(String providerCode, String modelCode, String endpointUrl,
                            String credentialEnvName, Integer timeoutSeconds, Boolean enabled) {
        if (enabled == null) {
            throw new ServiceException("模型状态不能为空");
        }
        return new Values(rules.providerCode(providerCode), rules.modelCode(modelCode),
                rules.endpointUrl(endpointUrl), rules.credentialEnvName(credentialEnvName),
                rules.timeoutSeconds(timeoutSeconds), enabled);
    }

    private void bind(PreparedStatement statement, Values values, int offset) throws java.sql.SQLException {
        statement.setString(offset, values.providerCode());
        statement.setString(offset + 1, values.modelCode());
        statement.setString(offset + 2, values.endpointUrl());
        statement.setString(offset + 3, values.credentialEnvName());
        statement.setInt(offset + 4, values.timeoutSeconds());
        statement.setBoolean(offset + 5, values.enabled());
    }

    private RowMapper<View> rowMapper() {
        return (rs, row) -> new View(rs.getLong("model_id"), rs.getString("provider_code"),
                rs.getString("model_code"), rs.getString("endpoint_url"), rs.getString("credential_env_name"),
                rs.getInt("timeout_seconds"), rs.getBoolean("enabled"), rs.getInt("revision"),
                timestamp(rs.getTimestamp("create_time")), timestamp(rs.getTimestamp("update_time")),
                rs.getString("credential_mode"), "DB".equals(rs.getString("credential_mode")) ? rs.getBoolean("has_cipher")
                : rs.getString("credential_env_name") != null && System.getenv(rs.getString("credential_env_name")) != null
                && !System.getenv(rs.getString("credential_env_name")).isBlank());
    }

    private java.time.LocalDateTime timestamp(Timestamp value) {
        return value == null ? null : value.toLocalDateTime();
    }
}
