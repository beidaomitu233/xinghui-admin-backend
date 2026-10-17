package com.xinghuiTec.ai.conversation;

import com.xinghuiTec.ai.application.*;
import com.xinghuiTec.ai.contract.AiRuntimeException;
import com.xinghuiTec.ai.contract.AiTypes.*;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.support.GeneratedKeyHolder;
import org.springframework.stereotype.Repository;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;
import java.nio.charset.StandardCharsets;
import java.sql.*;
import java.time.Instant;
import java.util.*;
import static com.xinghuiTec.ai.conversation.ConversationFailure.require;

/**
 * Same request admission transaction owns conversation claims; deltas commit
 * before SSE publication.
 */
@Repository
public class ConversationStore implements AiConversationRuntime {
    public record Head(String id, String subject, String tenant, String sceneKey, String sceneName, String title,
            String source, String kind, String businessType, String businessId, int revision, long sequence,
            String activeRequest, String status, String lastMessage, String history, Instant updated) {
        @Override
        public String toString() {
            return "ConversationHead[REDACTED]";
        }
    }
    private final JdbcTemplate jdbc;
    private final AiRequestStore requests;
    private final TransactionTemplate tx;
    public ConversationStore(JdbcTemplate jdbc, AiRequestStore requests, PlatformTransactionManager manager) {
        this.jdbc = jdbc;
        this.requests = requests;
        this.tx = new TransactionTemplate(manager);
    }
    public Head head(long id, boolean lock) {
        String sql = lock
                ? "SELECT c.*,NULL AS active_status FROM sys_ai_conversation c WHERE c.conversation_id=? FOR UPDATE"
                : "SELECT c.*,r.status AS active_status FROM sys_ai_conversation c LEFT JOIN sys_ai_request r ON r.request_id=COALESCE(c.active_request_id,c.legacy_request_id) WHERE c.conversation_id=?";
        var rows = jdbc.query(sql, (rs, n) -> head(rs), id);
        require(rows.size() == 1, 404, "NOT_FOUND");
        return rows.get(0);
    }
    private Head head(ResultSet r) throws SQLException {
        return new Head(r.getString("conversation_id"), r.getString("subject_ref"), r.getString("tenant_ref"),
                r.getString("scene_key"), r.getString("scene_name"), r.getString("title"), r.getString("source_label"),
                r.getString("request_kind"), r.getString("business_type"), r.getString("business_id"),
                r.getInt("revision"), r.getLong("last_sequence"), r.getString("active_request_id"),
                r.getString("active_status") == null ? r.getString("last_status") : r.getString("active_status"),
                r.getString("last_message"), r.getString("history_state"), r.getTimestamp("update_time").toInstant());
    }
    @Override
    public void admitted(AiRequestStore.Request request, AiRequestStore.Owner owner, Map<String, Object> inputs,
            Target target) {
        var scenes = jdbc.queryForList("SELECT scene_key,name,scene_kind FROM sys_ai_scene WHERE app_id=?",
                request.appId());
        if (scenes.isEmpty()) {
            require(target == null, 404, "NOT_FOUND");
            return;
        }
        var scene = scenes.get(0);
        String sceneKey = String.valueOf(scene.get("scene_key")), name = String.valueOf(scene.get("name"));
        Object supplied = inputs.get("user_request");
        require(supplied == null || supplied instanceof String, 400, "INVALID_INPUT");
        String content = supplied == null ? "" : (String) supplied;
        Head h;
        if (target == null) {
            long id = insert(
                    "INSERT INTO sys_ai_conversation(owner_scope,subject_ref,tenant_ref,scene_key,scene_name,title,source_label,request_kind,business_type,business_id,last_status) VALUES(?,?,?,?,?,?,?,?,?,?,'QUEUED')",
                    "conversation_id", owner.scope(), owner.subjectRef(), owner.tenantRef(), sceneKey, name,
                    limit(content.isBlank() ? name : content, 80),
                    "RECORD_SUMMARY".equals(scene.get("scene_kind"))
                            ? "记录摘要"
                            : "TEST".equals(request.kind().name()) ? "提示词试运行" : "对话",
                    request.kind().name(), request.businessType(), request.businessId());
            h = head(id, true);
        } else {
            h = head(positive(target.conversationId()), true);
            require(h.subject().equals(owner.subjectRef()) && h.tenant().equals(owner.tenantRef()), 404, "NOT_FOUND");
            require("COMPLETE".equals(h.history()), 409, "LEGACY_NO_BODY");
            require(Objects.equals(h.sceneKey(), sceneKey) && h.kind().equals(request.kind().name())
                    && h.businessType().equals(request.businessType()) && h.businessId().equals(request.businessId()),
                    409, "CONVERSATION_SCOPE_CONFLICT");
            var details = new ConversationDtos.ErrorDetails(h.id(), h.revision(), h.activeRequest());
            if (h.activeRequest() != null)
                throw new ConversationFailure(409, "CONVERSATION_BUSY", details);
            if (h.revision() != target.expectedRevision())
                throw new ConversationFailure(409, "VERSION_CONFLICT", details);
        }
        require(h.sequence() < 400, 409, "CONVERSATION_FULL");
        int added = jdbc.update(
                "UPDATE sys_ai_conversation SET body_bytes=body_bytes+? WHERE conversation_id=? AND body_bytes+?<=2097152",
                content.getBytes(StandardCharsets.UTF_8).length, h.id(),
                content.getBytes(StandardCharsets.UTF_8).length);
        require(added == 1, 409, "CONVERSATION_FULL");
        long user = insert(
                "INSERT INTO sys_ai_conversation_message(conversation_id,sequence_number,role,content,status,request_id,scene_version_id) VALUES(?,?,'USER',?,'QUEUED',?,?)",
                "message_id", h.id(), h.sequence() + 1, content, request.requestId(), request.sceneVersionId());
        long assistant = insert(
                "INSERT INTO sys_ai_conversation_message(conversation_id,sequence_number,role,content,status,request_id,scene_version_id) VALUES(?,?,'ASSISTANT','','QUEUED',?,?)",
                "message_id", h.id(), h.sequence() + 2, request.requestId(), request.sceneVersionId());
        jdbc.update(
                "INSERT INTO sys_ai_conversation_turn(request_id,conversation_id,user_message_id,assistant_message_id) VALUES(?,?,?,?)",
                request.requestId(), h.id(), user, assistant);
        jdbc.update(
                "UPDATE sys_ai_conversation SET revision=revision+1,last_sequence=last_sequence+2,active_request_id=?,last_status='QUEUED',last_message=?,update_time=CURRENT_TIMESTAMP(3) WHERE conversation_id=?",
                request.requestId(), limit(content, 256), h.id());
    }
    @Override
    public Link link(long requestId) {
        var rows = jdbc.query(
                "SELECT t.*,c.revision FROM sys_ai_conversation_turn t JOIN sys_ai_conversation c ON c.conversation_id=t.conversation_id WHERE t.request_id=?",
                (r, n) -> new Link(r.getString("conversation_id"), r.getInt("revision"), r.getString("user_message_id"),
                        r.getString("assistant_message_id"), r.getBoolean("context_truncated"),
                        r.getString("context_start_message_id")),
                requestId);
        return rows.isEmpty() ? null : rows.get(0);
    }
    @Override
    public History context(long requestId, int maxBytes) {
        var link = link(requestId);
        if (link == null)
            return new History(List.of(), List.of());
        record Pair(long request, String user, String assistant, String message, long sequence) {
        }
        var pairs = jdbc.query(
                "SELECT u.request_id,u.content AS user_content,a.content AS assistant_content,u.message_id,u.sequence_number FROM sys_ai_conversation_message u"
                        + " JOIN sys_ai_conversation_message a ON a.request_id=u.request_id AND a.role='ASSISTANT' JOIN sys_ai_conversation_message current_message ON current_message.request_id=? AND current_message.role='USER'"
                        + " WHERE u.conversation_id=? AND u.role='USER' AND u.status='SUCCEEDED' AND a.status='SUCCEEDED' AND u.sequence_number<current_message.sequence_number ORDER BY u.sequence_number DESC LIMIT 21",
                (r, n) -> new Pair(r.getLong(1), r.getString(2), r.getString(3), r.getString(4), r.getLong(5)),
                requestId, link.conversationId());
        var chosen = new ArrayList<Pair>();
        int bytes = 0;
        boolean truncated = false;
        for (var pair : pairs) {
            int size = pair.user().getBytes(StandardCharsets.UTF_8).length
                    + pair.assistant().getBytes(StandardCharsets.UTF_8).length;
            if (chosen.size() == 20 || bytes + size > maxBytes) {
                truncated = true;
                break;
            }
            chosen.add(pair);
            bytes += size;
        }
        Collections.reverse(chosen);
        var messages = new ArrayList<AiMessage>();
        var ids = new ArrayList<Long>();
        for (var pair : chosen) {
            messages.add(new AiMessage(MessageRole.USER, pair.user()));
            messages.add(new AiMessage(MessageRole.ASSISTANT, pair.assistant()));
            ids.add(pair.request());
        }
        String start = chosen.isEmpty() ? null : chosen.get(0).message();
        jdbc.update(
                "UPDATE sys_ai_conversation_turn SET context_truncated=?,context_start_message_id=? WHERE request_id=?",
                truncated, start, requestId);
        return new History(messages, ids);
    }
    @Override
    public void delta(AiRequestStore.Request request, String text) {
        var link = link(Long.parseLong(request.requestId()));
        if (link == null || link.assistantMessageId() == null)
            return;
        require(text != null, 400, "INVALID_INPUT");
        int bytes = text.getBytes(StandardCharsets.UTF_8).length;
        int changed = jdbc.update(
                "UPDATE sys_ai_conversation_message SET content=CONCAT(content,?),status='RUNNING',partial_output=TRUE WHERE message_id=? AND OCTET_LENGTH(content)+?<=262144",
                text, link.assistantMessageId(), bytes);
        if (changed != 1)
            throw new AiRuntimeException(ErrorCode.OUTPUT_LIMIT);
        if (jdbc.update(
                "UPDATE sys_ai_conversation SET body_bytes=body_bytes+? WHERE conversation_id=? AND body_bytes+?<=2097152",
                bytes, link.conversationId(), bytes) != 1)
            throw new AiRuntimeException(ErrorCode.OUTPUT_LIMIT);
    }
    @Override
    public List<Long> contextRequests(long requestId) {
        return jdbc.query("SELECT prior.request_id FROM sys_ai_conversation_turn turn"
                + " JOIN sys_ai_conversation_message first_message ON first_message.message_id=turn.context_start_message_id"
                + " JOIN sys_ai_conversation_message current_message ON current_message.message_id=turn.user_message_id"
                + " JOIN sys_ai_conversation_message prior ON prior.conversation_id=turn.conversation_id AND prior.role='USER'"
                + " WHERE turn.request_id=? AND prior.sequence_number>=first_message.sequence_number"
                + " AND prior.sequence_number<current_message.sequence_number AND prior.status='SUCCEEDED'"
                + " ORDER BY prior.sequence_number LIMIT 20", (row, index) -> row.getLong(1), requestId);
    }
    @Override
    public void finished(AiRequestStore.Request request) {
        if (!request.status().terminal())
            return;
        var link = link(Long.parseLong(request.requestId()));
        if (link == null)
            return;
        jdbc.update(
                "UPDATE sys_ai_conversation_message SET status=?,error_code=?,partial_output=CASE WHEN role='ASSISTANT' AND ?<>'SUCCEEDED' AND OCTET_LENGTH(content)>0 THEN TRUE ELSE FALSE END,finish_time=? WHERE request_id=?",
                request.status().name(), request.errorCode(), request.status().name(),
                Timestamp.from(request.finishedAt() == null ? Instant.now() : request.finishedAt()),
                request.requestId());
        var body = jdbc.query("SELECT content FROM sys_ai_conversation_message WHERE request_id=? AND role='ASSISTANT'",
                (r, n) -> r.getString(1), request.requestId());
        String text = body.isEmpty() ? "" : body.get(0);
        jdbc.update(
                "UPDATE sys_ai_conversation SET active_request_id=NULL,last_status=?,last_message=CASE WHEN ?='' THEN last_message ELSE ? END,revision=revision+1,update_time=CURRENT_TIMESTAMP(3) WHERE conversation_id=? AND active_request_id=?",
                request.status().name(), limit(text, 256), limit(text, 256), link.conversationId(),
                request.requestId());
    }
    @Override
    public void reconcile() {
        record Pending(long request, String subject, String tenant) {
        }
        var rows = jdbc.query(
                "SELECT r.request_id,c.subject_ref,c.tenant_ref FROM sys_ai_conversation c JOIN sys_ai_request r ON r.request_id=c.active_request_id WHERE r.status NOT IN ('QUEUED','RUNNING') LIMIT 100",
                (r, n) -> new Pending(r.getLong(1), r.getString(2), r.getString(3)));
        for (var row : rows)
            tx.executeWithoutResult(s -> {
                var owner = new AiRequestStore.Owner(row.subject(), row.tenant());
                var current = requests.owned(row.request(), owner);
                finished(current);
            });
    }
    public List<ConversationDtos.Message> messages(long conversationId) {
        return List.copyOf(jdbc.query(
                "SELECT m.*,r.status AS actual_status,r.error_code AS actual_error FROM sys_ai_conversation_message m JOIN sys_ai_request r ON r.request_id=m.request_id WHERE m.conversation_id=? ORDER BY sequence_number",
                (r, n) -> new ConversationDtos.Message(r.getString("message_id"), r.getLong("sequence_number"),
                        ConversationDtos.Role.valueOf(r.getString("role")), r.getString("content"),
                        AiRequestStatus.valueOf(r.getString("actual_status")), r.getString("request_id"),
                        r.getString("scene_version_id"), r.getString("actual_error"), r.getBoolean("partial_output"),
                        r.getTimestamp("create_time").toInstant(),
                        r.getTimestamp("finish_time") == null ? null : r.getTimestamp("finish_time").toInstant()),
                conversationId));
    }
    public List<Long> requestIds(long conversationId) {
        return jdbc.query("SELECT request_id FROM sys_ai_conversation_turn WHERE conversation_id=? ORDER BY request_id",
                (r, n) -> r.getLong(1), conversationId);
    }
    public List<Head> page(AiRequestStore.Owner owner, boolean audit, String sceneKey, String kind, String search,
            int page, int size) {
        require(page >= 1 && page <= 100000 && size >= 1 && size <= 100, 400, "INVALID_INPUT");
        var args = new ArrayList<Object>();
        String where = where(owner, audit, sceneKey, kind, search, args);
        args.add(size);
        args.add((page - 1L) * size);
        return jdbc.query(
                "SELECT c.*,r.status AS active_status FROM sys_ai_conversation c LEFT JOIN sys_ai_request r ON r.request_id=COALESCE(c.active_request_id,c.legacy_request_id)"
                        + where + " ORDER BY c.update_time DESC,c.conversation_id DESC LIMIT ? OFFSET ?",
                (r, n) -> head(r), args.toArray());
    }
    public long count(AiRequestStore.Owner owner, boolean audit, String sceneKey, String kind, String search) {
        var args = new ArrayList<Object>();
        return Objects.requireNonNull(jdbc.queryForObject(
                "SELECT COUNT(*) FROM sys_ai_conversation c" + where(owner, audit, sceneKey, kind, search, args),
                Long.class, args.toArray()));
    }
    private String where(AiRequestStore.Owner owner, boolean audit, String sceneKey, String kind, String search,
            List<Object> args) {
        var predicates = new ArrayList<String>();
        if (!audit) {
            predicates.add("c.owner_scope=? AND c.subject_ref=? AND c.tenant_ref=?");
            args.add(owner.scope());
            args.add(owner.subjectRef());
            args.add(owner.tenantRef());
        }
        if (sceneKey != null) {
            require(sceneKey.length() <= 128, 400, "INVALID_INPUT");
            predicates.add("c.scene_key=?");
            args.add(sceneKey);
        }
        if (kind != null) {
            require(Set.of("BUSINESS", "TEST").contains(kind), 400, "INVALID_INPUT");
            predicates.add("c.request_kind=?");
            args.add(kind);
        }
        if (search != null && !search.isBlank()) {
            require(search.length() <= 128, 400, "INVALID_INPUT");
            predicates.add("LOCATE(?,c.title)>0");
            args.add(search);
        }
        return predicates.isEmpty() ? "" : " WHERE " + String.join(" AND ", predicates);
    }
    private long insert(String sql, String column, Object... args) {
        var keys = new GeneratedKeyHolder();
        jdbc.update(c -> {
            var p = c.prepareStatement(sql, new String[]{column});
            for (int i = 0; i < args.length; i++)
                p.setObject(i + 1, args[i]);
            return p;
        }, keys);
        return Objects.requireNonNull(keys.getKey()).longValue();
    }
    public static long positive(String value) {
        try {
            long id = Long.parseLong(value);
            require(id > 0, 400, "INVALID_INPUT");
            return id;
        } catch (NumberFormatException invalid) {
            throw new ConversationFailure(400, "INVALID_INPUT");
        }
    }
    private static String limit(String value, int count) {
        return value.codePoints().limit(count)
                .collect(StringBuilder::new, StringBuilder::appendCodePoint, StringBuilder::append).toString();
    }
}
