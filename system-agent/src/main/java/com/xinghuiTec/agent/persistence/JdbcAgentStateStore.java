package com.xinghuiTec.agent.persistence;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.xinghuiTec.agent.api.*;
import io.agentscope.core.state.AgentStateStore;
import io.agentscope.core.state.State;
import io.agentscope.core.state.VersionedState;
import io.agentscope.core.util.JsonUtils;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

import java.util.*;

/** Owner-bound SDK state storage. No SDK file state is used by the workbench. */
public final class JdbcAgentStateStore implements AgentStateStore {
    private final JdbcTemplate jdbc;
    private final TransactionTemplate tx;
    private final AgentActor actor;
    private final String sessionId;
    private final AgentPayloadCodec codec;
    private final Runnable writeGuard;
    private final ObjectMapper json = new ObjectMapper();

    public JdbcAgentStateStore(JdbcTemplate jdbc, PlatformTransactionManager transactions, AgentActor actor,
                               String sessionId, AgentPayloadCodec codec, Runnable writeGuard) {
        this.jdbc = Objects.requireNonNull(jdbc); this.tx = new TransactionTemplate(transactions);
        this.actor = Objects.requireNonNull(actor); this.sessionId = Objects.requireNonNull(sessionId);
        this.codec = Objects.requireNonNull(codec); this.writeGuard = Objects.requireNonNull(writeGuard);
    }
    @Override public boolean supportsVersioning() { return true; }
    @Override public void save(String userId, String session, String key, State value) {
        write(userId, session, key, JsonUtils.getJsonCodec().toJson(value), UNVERSIONED);
    }
    @Override public void save(String userId, String session, String key, List<? extends State> values) {
        var array = json.createArrayNode();
        for (State value : values) array.add(readTree(JsonUtils.getJsonCodec().toJson(value)));
        write(userId, session, key, array.toString(), UNVERSIONED);
    }
    @Override public long saveIfVersion(String userId, String session, String key, State value, long version) {
        return write(userId, session, key, JsonUtils.getJsonCodec().toJson(value), version);
    }
    private long write(String userId, String session, String key, String value, long expected) {
        requireOwner(userId, session); requireKey(key);
        if (value.length() > 4_194_304) throw new AgentProblem("STATE_LIMIT", "会话上下文过大，请新建会话");
        String encrypted = codec.seal(purpose(key), value);
        return Objects.requireNonNull(tx.execute(status -> {
            writeGuard.run();
            if (expected == UNVERSIONED) {
                if (jdbc.update("UPDATE sys_agent_state SET payload=?,version=version+1 WHERE tenant_id=? AND user_id=? AND session_id=? AND slot_key=?",
                        encrypted, actor.tenantId(), actor.userId(), sessionId, key) == 0) {
                    try { insert(key, encrypted); }
                    catch (DuplicateKeyException race) {
                        jdbc.update("UPDATE sys_agent_state SET payload=?,version=version+1 WHERE tenant_id=? AND user_id=? AND session_id=? AND slot_key=?",
                                encrypted, actor.tenantId(), actor.userId(), sessionId, key);
                    }
                }
                return UNVERSIONED;
            }
            if (expected == 0) {
                try { insert(key, encrypted); return 1L; }
                catch (DuplicateKeyException conflict) { return UNVERSIONED; }
            }
            int changed = jdbc.update("UPDATE sys_agent_state SET payload=?,version=version+1 WHERE tenant_id=? AND user_id=? AND session_id=? AND slot_key=? AND version=?",
                    encrypted, actor.tenantId(), actor.userId(), sessionId, key, expected);
            return changed == 1 ? expected + 1 : UNVERSIONED;
        }));
    }
    private void insert(String key, String payload) {
        jdbc.update("INSERT INTO sys_agent_state(tenant_id,user_id,session_id,slot_key,payload,version) VALUES(?,?,?,?,?,1)",
                actor.tenantId(), actor.userId(), sessionId, key, payload);
    }
    @Override public <T extends State> Optional<T> get(String userId, String session, String key, Class<T> type) {
        return Optional.ofNullable(getVersioned(userId, session, key, type).value());
    }
    @Override public <T extends State> VersionedState<T> getVersioned(String userId, String session, String key, Class<T> type) {
        requireOwner(userId, session); requireKey(key);
        var rows = jdbc.query("SELECT payload,version FROM sys_agent_state WHERE tenant_id=? AND user_id=? AND session_id=? AND slot_key=?",
                (rs, n) -> new VersionedState<>(JsonUtils.getJsonCodec().fromJson(codec.open(purpose(key), rs.getString(1)), type), rs.getLong(2)),
                actor.tenantId(), actor.userId(), sessionId, key);
        return rows.isEmpty() ? new VersionedState<>(null, 0) : rows.get(0);
    }
    @Override public <T extends State> List<T> getList(String userId, String session, String key, Class<T> type) {
        requireOwner(userId, session); requireKey(key);
        var rows = jdbc.queryForList("SELECT payload FROM sys_agent_state WHERE tenant_id=? AND user_id=? AND session_id=? AND slot_key=?",
                String.class, actor.tenantId(), actor.userId(), sessionId, key);
        if (rows.isEmpty()) return List.of();
        var array = readTree(codec.open(purpose(key), rows.get(0)));
        if (!array.isArray()) throw new IllegalStateException("Invalid persisted agent list");
        var result = new ArrayList<T>();
        array.forEach(value -> result.add(JsonUtils.getJsonCodec().fromJson(value.toString(), type)));
        return List.copyOf(result);
    }
    @Override public boolean exists(String userId, String session) {
        requireOwner(userId, session);
        return !jdbc.queryForList("SELECT slot_key FROM sys_agent_state WHERE tenant_id=? AND user_id=? AND session_id=?",
                String.class, actor.tenantId(), actor.userId(), sessionId).isEmpty();
    }
    @Override public void delete(String userId, String session) {
        requireOwner(userId, session);
        tx.executeWithoutResult(status -> { writeGuard.run(); jdbc.update("DELETE FROM sys_agent_state WHERE tenant_id=? AND user_id=? AND session_id=?",
                actor.tenantId(), actor.userId(), sessionId); });
    }
    @Override public void delete(String userId, String session, String key) {
        requireOwner(userId, session); requireKey(key);
        tx.executeWithoutResult(status -> { writeGuard.run(); jdbc.update("DELETE FROM sys_agent_state WHERE tenant_id=? AND user_id=? AND session_id=? AND slot_key=?",
                actor.tenantId(), actor.userId(), sessionId, key); });
    }
    @Override public Set<String> listSessionIds(String userId) {
        requireOwner(userId, sessionId);
        return exists(userId, sessionId) ? Set.of(sessionId) : Set.of();
    }
    private void requireOwner(String userId, String session) {
        if (!actor.stateUserId().equals(userId) || !sessionId.equals(session))
            throw new AgentProblem("FORBIDDEN", "无权访问该会话");
    }
    private void requireKey(String key) {
        if (key == null || key.isBlank() || key.length() > 128) throw new IllegalArgumentException("Invalid state key");
    }
    private String purpose(String key) { return "agent-state:" + actor.stateUserId() + ":" + sessionId + ":" + key; }
    private com.fasterxml.jackson.databind.JsonNode readTree(String value) {
        try { return json.readTree(value); } catch (Exception invalid) { throw new IllegalStateException("Invalid agent state JSON", invalid); }
    }
}
