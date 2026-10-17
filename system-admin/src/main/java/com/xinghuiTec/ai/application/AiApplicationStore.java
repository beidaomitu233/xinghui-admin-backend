package com.xinghuiTec.ai.application;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.xinghuiTec.ai.contract.AiRuntimeException;
import com.xinghuiTec.ai.contract.AiTypes.ErrorCode;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.support.GeneratedKeyHolder;
import org.springframework.stereotype.Repository;

import java.util.List;

/** Application draft persistence; publication is owned by the guarded application service. */
@Repository
public class AiApplicationStore {
    private final JdbcTemplate jdbc;
    private final ObjectMapper mapper;
    private com.xinghuiTec.ai.contract.AiSceneOwnership sceneOwnership;
    @org.springframework.beans.factory.annotation.Autowired
    public void sceneOwnership(com.xinghuiTec.ai.contract.AiSceneOwnership ownership){this.sceneOwnership=ownership;}

    public AiApplicationStore(JdbcTemplate jdbc, ObjectMapper mapper) {
        this.jdbc = jdbc;
        this.mapper = mapper.copy().enable(com.fasterxml.jackson.databind.DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES);
    }

    public record Draft(String name, String purpose, String entryKey, AiApplicationPolicy policy) {
        public Draft {
            text(name, 128);
            text(purpose, 512);
            if (entryKey == null || !entryKey.matches("[a-z][a-z0-9_.:/-]{0,127}") || policy == null) {
                throw new AiRuntimeException(ErrorCode.INVALID_INPUT);
            }
            // A form entry is resolved on the server, never from a user-entered app ID or record body.
            if ((entryKey.startsWith("lowcode.record.summary/") && policy.formId() == null)
                    || (policy.formId() != null && !entryKey.equals("lowcode.record.summary/" + policy.formId()))) {
                throw new AiRuntimeException(ErrorCode.INVALID_INPUT);
            }
        }
    }

    public record View(String appId, String name, String purpose, String ownerId, String entryKey,
                       AiApplicationPolicy draft, int draftRevision, String publishedVersionId,
                       boolean enabled, int revision) {}
    public record Page(List<View> records, long total, int pageNum, int pageSize) {}

    public String create(Draft draft, long ownerId) {
        if (ownerId <= 0) throw new AiRuntimeException(ErrorCode.INVALID_INPUT);
        var keys = new GeneratedKeyHolder();
        String json = json(draft.policy());
        jdbc.update(connection -> {
            var statement = connection.prepareStatement("INSERT INTO sys_ai_application"
                            + "(name,purpose,owner_id,entry_key,draft_json) VALUES(?,?,?,?,?)",
                    new String[] {"app_id"});
            statement.setString(1, draft.name());
            statement.setString(2, draft.purpose());
            statement.setLong(3, ownerId);
            statement.setString(4, draft.entryKey());
            statement.setString(5, json);
            return statement;
        }, keys);
        if (keys.getKey() == null) throw new AiRuntimeException(ErrorCode.INTERNAL_FAILURE);
        return keys.getKey().toString();
    }

    public View get(long appId) { return read(appId, false); }

    View current(long appId) { return read(appId, true); }

    public View updateDraft(long appId, int revision, Draft draft) {
        if(sceneOwnership!=null)sceneOwnership.requireApplicationWritable(Long.toString(appId));
        if (revision < 0) throw new AiRuntimeException(ErrorCode.INVALID_INPUT);
        int changed = jdbc.update("UPDATE sys_ai_application SET name=?,purpose=?,draft_json=?,"
                        + "draft_revision=draft_revision+1,revision=revision+1,update_time=CURRENT_TIMESTAMP(3)"
                        + " WHERE app_id=? AND revision=? AND entry_key=?", draft.name(), draft.purpose(),
                json(draft.policy()), appId, revision, draft.entryKey());
        if (changed != 1) throw new AiRuntimeException(ErrorCode.VERSION_CONFLICT);
        return get(appId);
    }

    public Page page(int pageNum, int pageSize, String search) {
        if (pageNum < 1 || pageNum > 100_000 || pageSize < 1 || pageSize > 100
                || (search != null && search.length() > 128)) throw new AiRuntimeException(ErrorCode.INVALID_INPUT);
        // Literal substring search; no LIKE wildcard/escape dialect differences or user SQL.
        String where = search == null || search.isBlank() ? "" : " WHERE LOCATE(?,name)>0";
        List<Object> args = new java.util.ArrayList<>();
        if (!where.isEmpty()) args.add(search);
        Long total = jdbc.queryForObject("SELECT COUNT(*) FROM sys_ai_application" + where, Long.class, args.toArray());
        args.add(pageSize);
        args.add((pageNum - 1) * pageSize);
        List<View> rows = jdbc.query("SELECT * FROM sys_ai_application" + where + " ORDER BY app_id DESC LIMIT ? OFFSET ?",
                (rs, index) -> row(rs), args.toArray());
        return new Page(List.copyOf(rows), total == null ? 0 : total, pageNum, pageSize);
    }

    private View read(long appId, boolean current) {
        List<View> rows = jdbc.query("SELECT * FROM sys_ai_application WHERE app_id=?" + (current ? " FOR UPDATE" : ""),
                (rs, index) -> row(rs), appId);
        if (rows.size() != 1) throw new AiRuntimeException(ErrorCode.NOT_FOUND);
        return rows.get(0);
    }

    private View row(java.sql.ResultSet rs) throws java.sql.SQLException {
        Long published = rs.getObject("published_version_id", Long.class);
        try {
            return new View(Long.toString(rs.getLong("app_id")), rs.getString("name"), rs.getString("purpose"),
                    Long.toString(rs.getLong("owner_id")), rs.getString("entry_key"),
                    mapper.readValue(rs.getString("draft_json"), AiApplicationPolicy.class),
                    rs.getInt("draft_revision"), published == null ? null : published.toString(),
                    rs.getBoolean("enabled"), rs.getInt("revision"));
        } catch (JsonProcessingException invalidStoredData) {
            throw new AiRuntimeException(ErrorCode.INTERNAL_FAILURE);
        }
    }

    String json(Object value) {
        try {
            String json = mapper.writeValueAsString(value);
            if (json.length() > 65_536) throw new AiRuntimeException(ErrorCode.INVALID_INPUT);
            return json;
        } catch (JsonProcessingException invalid) {
            throw new AiRuntimeException(ErrorCode.INVALID_INPUT);
        }
    }

    private static void text(String value, int max) {
        if (value == null || value.isBlank() || value.length() > max
                || value.codePoints().anyMatch(Character::isISOControl)) throw new AiRuntimeException(ErrorCode.INVALID_INPUT);
    }
}
