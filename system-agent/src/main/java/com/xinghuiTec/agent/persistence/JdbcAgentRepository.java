package com.xinghuiTec.agent.persistence;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.xinghuiTec.agent.api.*;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.RowMapper;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.sql.Timestamp;
import java.time.Duration;
import java.time.Instant;
import java.util.*;
import java.util.function.Supplier;

/** Durable run admission, history cursors and transactional business-tool receipts. */
public final class JdbcAgentRepository {
    public record Session(String id, String title, String activeRunId, long version, Instant updatedAt) {}
    public record Run(String id, String sessionId, String status, String promptSceneKey, String input,
                      String errorCode, Instant deadline, List<String> imageIds, String mode) {
        public Run(String id,String sessionId,String status,String promptSceneKey,String input,String errorCode,Instant deadline,List<String> images){this(id,sessionId,status,promptSceneKey,input,errorCode,deadline,images,"EXECUTE");}
        public Run(String id,String sessionId,String status,String promptSceneKey,String input,String errorCode,Instant deadline){this(id,sessionId,status,promptSceneKey,input,errorCode,deadline,List.of());}
        public Run {imageIds=imageIds==null?List.of():List.copyOf(imageIds);}
    }
    public record Admission(Run run, boolean created) {}
    public record Event(String id, String runId, String type, Map<String, Object> data, Instant createdAt) {}
    private static final Set<String> TERMINAL = Set.of("COMPLETED", "CANCELLED", "FAILED", "INTERRUPTED");
    private final JdbcTemplate jdbc;
    private final TransactionTemplate tx;
    private final AgentPayloadCodec codec;
    private final AgentHistoryAccess access;
    private final ObjectMapper json;

    public JdbcAgentRepository(JdbcTemplate jdbc, PlatformTransactionManager transactions, AgentPayloadCodec codec,
                               AgentHistoryAccess access, ObjectMapper json) {
        this.jdbc = Objects.requireNonNull(jdbc); this.tx = new TransactionTemplate(transactions);
        this.codec = Objects.requireNonNull(codec); this.access = Objects.requireNonNull(access); this.json = Objects.requireNonNull(json);
    }
    public Session create(AgentActor actor, String title) {
        String id = UUID.randomUUID().toString(); Instant now = Instant.now();
        String name = title == null || title.isBlank() ? "新会话" : title.trim();
        if (name.length() > 160) throw new AgentProblem("INVALID_TITLE", "会话名称最多160个字符");
        jdbc.update("INSERT INTO sys_agent_session(session_id,tenant_id,user_id,title,version,created_at,updated_at) VALUES(?,?,?,?,1,?,?)",
                id, actor.tenantId(), actor.userId(), name, time(now), time(now));
        return new Session(id, name, null, 1, now);
    }
    public List<Session> sessions(AgentActor actor) {
        var candidates = jdbc.query("SELECT * FROM sys_agent_session WHERE tenant_id=? AND user_id=? AND archived=0 ORDER BY updated_at DESC LIMIT 100",
                sessionMapper(), actor.tenantId(), actor.userId());
        var visible = new ArrayList<Session>();
        for (Session session : candidates) {
            try { requireHistory(actor, session.id()); visible.add(publicSession(actor,session)); }
            catch (AgentProblem denied) { if (!"FORBIDDEN".equals(denied.code())) throw denied; }
        }
        return List.copyOf(visible);
    }
    public Session session(AgentActor actor, String sessionId) {
        Session session = ownedSession(actor, sessionId, false); requireHistory(actor, sessionId); return publicSession(actor,session);
    }
    /** Keep the internal latest-run pointer for checkpoint ordering, but expose only a live run.
     * Reading a reconnected session also settles a deadline left behind by a terminated process. */
    private Session publicSession(AgentActor actor,Session session) {
        if(session.activeRunId()==null)return session;
        Run latest=run(actor,session.activeRunId());
        return TERMINAL.contains(latest.status())
                ?new Session(session.id(),session.title(),null,session.version(),session.updatedAt()):session;
    }
    public void archive(AgentActor actor,String sessionId){
        tx.executeWithoutResult(status->{var session=ownedSession(actor,sessionId,true);requireHistory(actor,sessionId);
            if(session.activeRunId()!=null&&!TERMINAL.contains(runInternal(actor,session.activeRunId(),false).status()))throw new AgentProblem("RUN_BUSY","请先停止当前任务");
            jdbc.update("UPDATE sys_agent_session SET archived=1,version=version+1 WHERE session_id=? AND tenant_id=? AND user_id=?",sessionId,actor.tenantId(),actor.userId());
        });
    }
    public void rename(AgentActor actor, String sessionId, String title, long expectedVersion) {
        session(actor, sessionId);
        if (title == null || title.isBlank() || title.trim().length() > 160) throw new AgentProblem("INVALID_TITLE", "请输入会话名称，最多160个字符");
        if (jdbc.update("UPDATE sys_agent_session SET title=?,version=version+1,updated_at=? WHERE session_id=? AND tenant_id=? AND user_id=? AND version=?",
                title.trim(), time(Instant.now()), sessionId, actor.tenantId(), actor.userId(), expectedVersion) != 1)
            throw new AgentProblem("VERSION_CONFLICT", "会话已更新，请刷新后重试");
    }
    public Admission admit(AgentActor actor, String sessionId, String clientRequest, String input, String sceneKey, Duration budget) {
        return admit(actor,sessionId,clientRequest,input,sceneKey,budget,List.of());
    }
    public Admission admit(AgentActor actor, String sessionId, String clientRequest, String input, String sceneKey, Duration budget,List<JdbcAgentImages.Image> images) {
        return admit(actor,sessionId,clientRequest,input,sceneKey,budget,images,"EXECUTE");
    }
    public Admission admit(AgentActor actor, String sessionId, String clientRequest, String input, String sceneKey, Duration budget,List<JdbcAgentImages.Image> images,String mode) {
        if(!Set.of("PLAN","EXECUTE").contains(mode))throw new AgentProblem("INVALID_MODE","请选择计划或执行模式");
        if (clientRequest == null || !clientRequest.matches("[a-zA-Z0-9_-]{16,64}")) throw new AgentProblem("INVALID_REQUEST_ID", "缺少有效的请求标识");
        if (input == null || input.isBlank() && images.isEmpty() || input.length() > 32768 || sceneKey == null || !sceneKey.matches("[A-Za-z0-9._:-]{1,128}"))
            throw new AgentProblem("INVALID_MESSAGE", "请选择Agent提示词并输入内容，最多32768个字符");
        if (budget.isNegative() || budget.isZero() || budget.compareTo(Duration.ofMinutes(10)) > 0) throw new IllegalArgumentException("Invalid run budget");
        return Objects.requireNonNull(tx.execute(status -> {
            Session session = ownedSession(actor, sessionId, true); requireHistory(actor, sessionId);
            String imageIds=canonical(json.valueToTree(images.stream().map(JdbcAgentImages.Image::id).toList()));
            String hash = digest(mode + "\n" + sceneKey + "\n" + input + (images.isEmpty()?"":"\n"+imageIds));
            var duplicate = jdbc.query("SELECT request_hash,run_id FROM sys_agent_run WHERE session_id=? AND tenant_id=? AND user_id=? AND client_request_id=?",
                    (rs, n) -> Map.entry(rs.getString(1), rs.getString(2)), sessionId, actor.tenantId(), actor.userId(), clientRequest);
            if (!duplicate.isEmpty()) {
                if (!hash.equals(duplicate.get(0).getKey())) throw new AgentProblem("IDEMPOTENCY_CONFLICT", "同一请求标识不能提交不同内容");
                return new Admission(runInternal(actor, duplicate.get(0).getValue(), false), false);
            }
            if (session.activeRunId() != null) {
                Run active = runInternal(actor, session.activeRunId(), true);
                if (!TERMINAL.contains(active.status())) {
                    if (Instant.now().isBefore(active.deadline())) throw new AgentProblem("RUN_BUSY", "该会话仍在执行任务");
                    terminal(actor, active, "INTERRUPTED", "DEADLINE_EXCEEDED");
                }
            }
            Instant now = Instant.now(); String id = UUID.randomUUID().toString();
            String payload = codec.seal(purpose(actor, sessionId, "input:" + id), input);
            jdbc.update("INSERT INTO sys_agent_run(run_id,session_id,tenant_id,user_id,client_request_id,request_hash,prompt_scene_key,input_payload,status,deadline,created_at,updated_at,image_ids_json,mode) VALUES(?,?,?,?,?,?,?,?,?,?,?,?,?,?)",
                    id, sessionId, actor.tenantId(), actor.userId(), clientRequest, hash, sceneKey, payload, "QUEUED", time(now.plus(budget)), time(now), time(now),imageIds,mode);
            jdbc.update("UPDATE sys_agent_session SET active_run_id=?,version=version+1,updated_at=? WHERE session_id=? AND tenant_id=? AND user_id=?",
                    id, time(now), sessionId, actor.tenantId(), actor.userId());
            Run run = new Run(id, sessionId, "QUEUED", sceneKey, input, null, now.plus(budget),images.stream().map(JdbcAgentImages.Image::id).toList(),mode);
            append(actor, run, new AgentEventView("USER_MESSAGE", images.isEmpty()?Map.of("text", input):Map.of("text", input,"images",images)));
            append(actor, run, new AgentEventView("RUN_STATE", Map.of("status", "QUEUED")));
            return new Admission(run, true);
        }));
    }
    public Run claim(AgentActor actor, String runId) {
        return Objects.requireNonNull(tx.execute(status -> {
            Run before = runInternal(actor, runId, false); ownedSession(actor, before.sessionId(), true);
            Run run = runInternal(actor, runId, true);
            if (!"QUEUED".equals(run.status()) || !Instant.now().isBefore(run.deadline())) throw new AgentProblem("RUN_STOPPED", "任务已停止");
            jdbc.update("UPDATE sys_agent_run SET status='RUNNING',updated_at=? WHERE run_id=? AND tenant_id=? AND user_id=?",
                    time(Instant.now()), runId, actor.tenantId(), actor.userId());
            append(actor, run, new AgentEventView("RUN_STATE", Map.of("status", "RUNNING")));
            return new Run(run.id(), run.sessionId(), "RUNNING", run.promptSceneKey(), run.input(), null, run.deadline(),run.imageIds(),run.mode());
        }));
    }
    public Run run(AgentActor actor, String runId) {
        Run run = runInternal(actor, runId, false); requireHistory(actor, run.sessionId());
        if (!TERMINAL.contains(run.status()) && !Instant.now().isBefore(run.deadline())) {
            finish(actor, runId, "INTERRUPTED", "DEADLINE_EXCEEDED"); return runInternal(actor, runId, false);
        }
        return run;
    }
    public boolean running(AgentActor actor, String runId) {
        // The transport cancellation watcher polls frequently; never decrypt the whole input here.
        var rows=jdbc.query("SELECT status,deadline FROM sys_agent_run WHERE run_id=? AND tenant_id=? AND user_id=?",
                (rs,n)->"RUNNING".equals(rs.getString(1))&&Instant.now().isBefore(rs.getTimestamp(2).toInstant()),
                runId,actor.tenantId(),actor.userId());
        if(rows.size()!=1)throw new AgentProblem("FORBIDDEN","无权访问该任务");
        return rows.get(0);
    }
    /** Must run in the same transaction as state saves / business writes. Locks session first. */
    public void guard(AgentActor actor, String runId, boolean checkpoint) {
        Run before = runInternal(actor, runId, false);
        Session session = ownedSession(actor, before.sessionId(), true);
        Run run = runInternal(actor, runId, true);
        // A terminating SDK may flush its last checkpoint after the public run is terminal.
        // It can never overwrite a later run: the session pointer is checked under the same lock.
        boolean allowed = checkpoint ? !"QUEUED".equals(run.status()) : "RUNNING".equals(run.status()) && Instant.now().isBefore(run.deadline());
        if (!runId.equals(session.activeRunId()) || !allowed)
            throw new AgentProblem("RUN_STOPPED", "任务已停止");
    }
    public void append(AgentActor actor, String runId, AgentEventView event) {
        tx.executeWithoutResult(status -> {
            Run run = runInternal(actor, runId, true);
            if (!TERMINAL.contains(run.status())) append(actor, run, event);
        });
    }
    public List<Event> events(AgentActor actor, String sessionId, long after) {
        session(actor, sessionId);
        if (after < 0) throw new AgentProblem("INVALID_CURSOR", "事件游标无效");
        return jdbc.query("SELECT * FROM sys_agent_event WHERE session_id=? AND tenant_id=? AND user_id=? AND event_id>? ORDER BY event_id LIMIT 200",
                (rs, n) -> new Event(Long.toString(rs.getLong("event_id")), rs.getString("run_id"), rs.getString("event_type"),
                        readMap(codec.open(purpose(actor, sessionId, "event:" + rs.getString("run_id") + ":" + rs.getString("event_type")), rs.getString("payload"))),
                        rs.getTimestamp("created_at").toInstant()), sessionId, actor.tenantId(), actor.userId(), after);
    }
    public void cancel(AgentActor actor, String runId) {
        tx.executeWithoutResult(status -> {
            Run before = runInternal(actor, runId, false); ownedSession(actor, before.sessionId(), true);
            Run run = runInternal(actor, runId, true); requireHistory(actor, run.sessionId());
            if (TERMINAL.contains(run.status())) return;
            if ("QUEUED".equals(run.status())) { terminal(actor, run, "CANCELLED", null); return; }
            jdbc.update("UPDATE sys_agent_run SET status='CANCELLING',updated_at=? WHERE run_id=? AND tenant_id=? AND user_id=?",
                    time(Instant.now()), runId, actor.tenantId(), actor.userId());
            append(actor, run, new AgentEventView("RUN_STATE", Map.of("status", "CANCELLING")));
        });
    }
    public void finish(AgentActor actor, String runId, String outcome, String errorCode) {
        if (!TERMINAL.contains(outcome)) throw new IllegalArgumentException("Invalid terminal state");
        tx.executeWithoutResult(status -> {
            Run before = runInternal(actor, runId, false); ownedSession(actor, before.sessionId(), true);
            Run run = runInternal(actor, runId, true);
            if (TERMINAL.contains(run.status())) return;
            terminal(actor, run, "CANCELLING".equals(run.status()) ? "CANCELLED" : outcome, errorCode);
        });
    }
    /** Business mutation and receipt commit atomically; receipt replay still revalidates permissions. */
    public JsonNode mutateOnce(AgentCall call, String toolCallId, String resource, JsonNode arguments, Supplier<JsonNode> work) {
        if (toolCallId == null || toolCallId.isBlank() || toolCallId.length() > 128 || resource == null || resource.length() > 128)
            throw new AgentProblem("INVALID_TOOL_CALL", "工具调用标识无效");
        return tx.execute(status -> {
            guard(call.actor(), call.runId(), false);
            if (!runInternal(call.actor(), call.runId(), false).sessionId().equals(call.sessionId()))
                throw new AgentProblem("FORBIDDEN", "任务与会话不匹配");
            String hash = digest(resource + "\n" + canonical(arguments));
            var receipts = jdbc.query("SELECT arguments_hash,payload FROM sys_agent_tool_receipt WHERE session_id=? AND tenant_id=? AND user_id=? AND tool_call_id=?",
                    (rs, n) -> Map.entry(rs.getString(1), rs.getString(2)), call.sessionId(), call.actor().tenantId(), call.actor().userId(), toolCallId);
            if (!receipts.isEmpty()) {
                if (!hash.equals(receipts.get(0).getKey())) throw new AgentProblem("IDEMPOTENCY_CONFLICT", "工具调用标识已用于其他操作");
                access.requireReadable(call.actor(), Set.of(resource));
                return readTree(codec.open(purpose(call.actor(), call.sessionId(), "tool:" + toolCallId), receipts.get(0).getValue()));
            }
            JsonNode result = Objects.requireNonNull(work.get());
            call.check();
            String encoded = result.toString();
            if (encoded.length() > 262144) throw new AgentProblem("TOOL_OUTPUT_LIMIT", "操作结果过大，请缩小处理范围");
            reference(call.actor(), call.sessionId(), resource);
            jdbc.update("INSERT INTO sys_agent_tool_receipt(session_id,tool_call_id,tenant_id,user_id,run_id,arguments_hash,payload,created_at) VALUES(?,?,?,?,?,?,?,?)",
                    call.sessionId(), toolCallId, call.actor().tenantId(), call.actor().userId(), call.runId(), hash,
                    codec.seal(purpose(call.actor(), call.sessionId(), "tool:" + toolCallId), encoded), time(Instant.now()));
            return result;
        });
    }
    public void reference(AgentActor actor, String sessionId, String resource) {
        ownedSession(actor, sessionId, false);
        if (resource == null || resource.isBlank() || resource.length() > 128) throw new IllegalArgumentException("Invalid resource");
        Integer count = jdbc.queryForObject("SELECT COUNT(*) FROM sys_agent_resource_ref WHERE session_id=? AND resource_id=?", Integer.class, sessionId, resource);
        if (count != null && count > 0) return;
        try {
            jdbc.update("INSERT INTO sys_agent_resource_ref(session_id,tenant_id,user_id,resource_id) VALUES(?,?,?,?)",
                    sessionId, actor.tenantId(), actor.userId(), resource);
        } catch (org.springframework.dao.DuplicateKeyException concurrentRead) {
            // The same owner may use a resource in several parallel read-only tool calls.
        }
    }
    private void requireHistory(AgentActor actor, String sessionId) {
        Set<String> resources = new HashSet<>(jdbc.queryForList("SELECT resource_id FROM sys_agent_resource_ref WHERE session_id=? AND tenant_id=? AND user_id=?",
                String.class, sessionId, actor.tenantId(), actor.userId()));
        access.requireReadable(actor, Set.copyOf(resources));
    }
    private Session ownedSession(AgentActor actor, String id, boolean lock) {
        var rows = jdbc.query("SELECT * FROM sys_agent_session WHERE session_id=? AND tenant_id=? AND user_id=?" + (lock ? " FOR UPDATE" : ""),
                sessionMapper(), id, actor.tenantId(), actor.userId());
        if (rows.isEmpty()) throw new AgentProblem("FORBIDDEN", "无权访问该会话");
        return rows.get(0);
    }
    private Run runInternal(AgentActor actor, String id, boolean lock) {
        var rows = jdbc.query("SELECT * FROM sys_agent_run WHERE run_id=? AND tenant_id=? AND user_id=?" + (lock ? " FOR UPDATE" : ""),
                (rs, n) -> new Run(rs.getString("run_id"), rs.getString("session_id"), rs.getString("status"), rs.getString("prompt_scene_key"),
                        codec.open(purpose(actor, rs.getString("session_id"), "input:" + id), rs.getString("input_payload")),
                        rs.getString("error_code"), rs.getTimestamp("deadline").toInstant(),imageIds(rs.getString("image_ids_json")),rs.getString("mode")), id, actor.tenantId(), actor.userId());
        if (rows.isEmpty()) throw new AgentProblem("FORBIDDEN", "无权访问该任务");
        return rows.get(0);
    }
    private RowMapper<Session> sessionMapper() {
        return (rs, n) -> new Session(rs.getString("session_id"), rs.getString("title"), rs.getString("active_run_id"), rs.getLong("version"), rs.getTimestamp("updated_at").toInstant());
    }
    private List<String> imageIds(String value){
        if(value==null)return List.of();
        try{return json.readValue(value,new TypeReference<List<String>>(){});}catch(Exception invalid){throw new AgentProblem("STATE_UNAVAILABLE","任务图片无法读取");}
    }
    private void terminal(AgentActor actor, Run run, String outcome, String code) {
        jdbc.update("UPDATE sys_agent_run SET status=?,error_code=?,updated_at=? WHERE run_id=? AND tenant_id=? AND user_id=?",
                outcome, code, time(Instant.now()), run.id(), actor.tenantId(), actor.userId());
        append(actor, run, new AgentEventView("RUN_STATE", code == null ? Map.of("status", outcome) : Map.of("status", outcome, "errorCode", code)));
    }
    private void append(AgentActor actor, Run run, AgentEventView event) {
        String encoded;
        try { encoded = json.writeValueAsString(event.data()); } catch (Exception invalid) { throw new IllegalArgumentException("Invalid event", invalid); }
        if (encoded.length() > 262144) throw new AgentProblem("EVENT_LIMIT", "事件内容过大");
        String payload = codec.seal(purpose(actor, run.sessionId(), "event:" + run.id() + ":" + event.type()), encoded);
        jdbc.update("INSERT INTO sys_agent_event(session_id,run_id,tenant_id,user_id,event_type,payload,created_at) VALUES(?,?,?,?,?,?,?)",
                run.sessionId(), run.id(), actor.tenantId(), actor.userId(), event.type(), payload, time(Instant.now()));
    }
    private String canonical(JsonNode node) {
        if (node.isObject()) {
            var object = json.createObjectNode(); var names = new TreeSet<String>(); node.fieldNames().forEachRemaining(names::add);
            for (String name : names) object.set(name, readTree(canonical(node.get(name)))); return object.toString();
        }
        if (node.isArray()) { var array = json.createArrayNode(); node.forEach(value -> array.add(readTree(canonical(value)))); return array.toString(); }
        return node.toString();
    }
    private JsonNode readTree(String value) { try { return json.readTree(value); } catch (Exception e) { throw new IllegalStateException("Invalid agent JSON", e); } }
    private Map<String, Object> readMap(String value) { try { return json.readValue(value, new TypeReference<>() {}); } catch (Exception e) { throw new IllegalStateException("Invalid agent event", e); } }
    private static String purpose(AgentActor actor, String session, String slot) { return "agent:" + actor.stateUserId() + ":" + session + ":" + slot; }
    private static Timestamp time(Instant value) { return Timestamp.from(value); }
    private static String digest(String value) {
        try { return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(value.getBytes(StandardCharsets.UTF_8))); }
        catch (Exception impossible) { throw new IllegalStateException(impossible); }
    }
}
