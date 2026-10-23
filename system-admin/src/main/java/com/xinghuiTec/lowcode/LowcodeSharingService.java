package com.xinghuiTec.lowcode;

import com.fasterxml.jackson.annotation.JsonAnySetter;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.annotation.JsonDeserialize;
import com.xinghuiTec.config.SecretCipher;
import com.xinghuiTec.utils.SecurityUtils;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.support.GeneratedKeyHolder;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.annotation.Isolation;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.SecureRandom;
import java.util.Base64;
import java.util.HexFormat;
import java.util.List;
import java.util.Map;
import java.util.Set;

/** A share grants filling only. It never grants access to record-management APIs. */
@Service
public class LowcodeSharingService {
    public record ShareChange(Boolean enabled, String accessMode,
            @JsonDeserialize(using = LowcodeIntegerDeserializer.class) Integer revision, Boolean rotate) {
        @JsonAnySetter public void unknown(String name, Object value) { throw new IllegalArgumentException("分享设置包含未定义字段"); }
    }
    public record ShareView(boolean enabled, String accessMode, int revision, String token) {
        @Override public String toString() { return "LowcodeSharing[link=REDACTED]"; }
    }
    public record FillSubmission(@JsonDeserialize(using = LowcodeIntegerDeserializer.class) Integer schemaVersion,
                                 String requestKey, JsonNode data) {
        @JsonAnySetter public void unknown(String name, Object value) { throw new IllegalArgumentException("填写请求包含未定义字段"); }
        @Override public String toString() { return "LowcodeSubmission[data=REDACTED]"; }
    }
    // Explicit public DTOs: no form/record IDs, tenant, creator, draft, list/query configuration or answers.
    public record FillField(String fieldKey, String label, String type, boolean required,
                            JsonNode defaultValue, List<LowcodeSchema.Option> options, String help,
                            Integer maxLength, java.math.BigDecimal min, java.math.BigDecimal max) {}
    public record FillView(String title, int schemaVersion, String accessMode, List<FillField> fields) {}
    public record FillReceipt(boolean submitted, String receipt) {}
    public static class Failure extends RuntimeException {
        public final int status;
        public Failure(int status, String message) { super(message); this.status = status; }
    }

    private final JdbcTemplate jdbc;
    private final LowcodeSchema schemas;
    private final LowcodeIdentity identity;
    private final SecretCipher cipher;
    private final SecureRandom random = new SecureRandom();

    public LowcodeSharingService(JdbcTemplate jdbc, LowcodeSchema schemas, LowcodeIdentity identity, SecretCipher cipher) {
        this.jdbc = jdbc; this.schemas = schemas; this.identity = identity; this.cipher = cipher;
    }

    public ShareView settings(long formId) {
        form(formId, false);
        var rows = jdbc.queryForList("SELECT * FROM sys_lowcode_form_share WHERE form_id=?", formId);
        if (rows.isEmpty()) return new ShareView(false, "login", 0, null);
        var row = rows.get(0);
        sameTenant(row);
        return shareView(formId, row);
    }

    @Transactional
    public ShareView configure(long formId, ShareChange change) {
        var form = form(formId, true); // Every writer takes form then share, including revocation.
        if (change == null || change.enabled() == null || change.revision() == null || change.revision() < 0
                || change.accessMode() == null || !Set.of("login", "public").contains(change.accessMode())) throw fail(400, "分享设置不正确");
        var tenant = identity.tenant();
        var issuer = identity.creator();
        var rows = jdbc.queryForList("SELECT * FROM sys_lowcode_form_share WHERE form_id=? FOR UPDATE", formId);
        if (rows.isEmpty()) {
            if (change.revision() != 0) throw fail(409, "分享设置已变化，请重新读取");
            jdbc.update("INSERT INTO sys_lowcode_form_share(form_id,tenant_id,issuer_id) VALUES(?,?,?)", formId, tenant, issuer);
            rows = jdbc.queryForList("SELECT * FROM sys_lowcode_form_share WHERE form_id=? FOR UPDATE", formId);
        }
        var row = rows.get(0);
        sameTenant(row);
        if (number(row, "revision") != change.revision()) throw fail(409, "分享设置已变化，请重新读取");
        boolean enabled = change.enabled();
        if (enabled) published(form);
        boolean regenerate = enabled && (number(row, "enabled") == 0 || Boolean.TRUE.equals(change.rotate())
                || !change.accessMode().equals(row.get("access_mode")));
        String tokenHash = (String) row.get("token_hash");
        String encrypted = (String) row.get("token_ciphertext");
        int generation = number(row, "token_version");
        if (!enabled || regenerate) generation++;
        if (!enabled) { tokenHash = null; encrypted = null; }
        if (regenerate) {
            if (!cipher.ready()) throw fail(503, "填写链接暂不可用，请联系管理员");
            var token = randomToken();
            tokenHash = hash(token);
            encrypted = cipher.encrypt(purpose(formId), token);
        }
        jdbc.update("UPDATE sys_lowcode_form_share SET enabled=?,access_mode=?,token_hash=?,token_ciphertext=?,"
                        + "token_version=?,revision=revision+1,update_time=CURRENT_TIMESTAMP WHERE form_id=?",
                enabled ? 1 : 0, change.accessMode(), tokenHash, encrypted, generation, formId);
        return settings(formId);
    }

    /** Called in the form update transaction after its row has been locked/updated. */
    public void revokeForForm(long formId) {
        jdbc.update("UPDATE sys_lowcode_form_share SET enabled=0,token_hash=NULL,token_ciphertext=NULL,"
                + "token_version=token_version+1,revision=revision+1,update_time=CURRENT_TIMESTAMP WHERE form_id=? AND enabled=1", formId);
    }

    @Transactional(readOnly = true)
    public FillView view(String token) {
        var access = access(token, false);
        actor(access.share());
        var schema = publishedSchema(access.form());
        return new FillView(String.valueOf(access.form().get("name")), number(access.form(), "published_version"),
                String.valueOf(access.share().get("access_mode")), schema.fields().stream().map(field -> new FillField(
                field.fieldKey(), field.label(), field.type(), field.required(), field.defaultValue(), field.options(),
                field.help(), field.maxLength(), field.min(), field.max())).toList());
    }

    @Transactional(isolation = Isolation.READ_COMMITTED)
    public FillReceipt submit(String token, FillSubmission input) {
        // A preliminary token lookup is never sufficient: locks and all checks happen again before insert.
        var access = access(token, true);
        var actor = actor(access.share());
        if (input == null || input.schemaVersion() == null || input.schemaVersion() != number(access.form(), "published_version"))
            throw fail(409, "表单已更新，请重新读取后填写；输入已保留");
        if (input.requestKey() == null || !input.requestKey().matches("[A-Za-z0-9_-]{16,80}"))
            throw fail(400, "提交标识不正确，请刷新后重试");
        var data = schemas.validateData(publishedSchema(access.form()), input.data(), true);
        var encoded = schemas.encode(data, 262144, "填写数据");
        var fingerprint = hash(input.schemaVersion() + ":" + encoded);
        long shareId = ((Number) access.share().get("id")).longValue();
        int generation = number(access.share(), "token_version");
        var previous = jdbc.queryForList("SELECT payload_hash,receipt FROM sys_lowcode_submission WHERE share_id=? "
                        + "AND token_version=? AND actor_key=? AND request_key=?", shareId, generation, actor.key(), input.requestKey());
        if (!previous.isEmpty()) {
            if (!fingerprint.equals(previous.get(0).get("payload_hash"))) throw fail(409, "此提交已完成，请勿重复更改提交内容");
            return new FillReceipt(true, String.valueOf(previous.get(0).get("receipt")));
        }
        long formId = ((Number) access.form().get("id")).longValue();
        var generated = new GeneratedKeyHolder();
        jdbc.update(connection -> {
            var statement = connection.prepareStatement("INSERT INTO sys_lowcode_record(form_id,schema_version,tenant_id,creator,data_json) VALUES(?,?,?,?,?)", new String[]{"id"});
            statement.setLong(1, formId); statement.setInt(2, input.schemaVersion()); statement.setString(3, actor.tenant());
            statement.setString(4, actor.creator()); statement.setString(5, encoded); return statement;
        }, generated);
        if (generated.getKey() == null) throw fail(500, "提交未成功，请重试");
        var receipt = randomToken();
        jdbc.update("INSERT INTO sys_lowcode_submission(share_id,token_version,actor_key,request_key,payload_hash,receipt,record_id) VALUES(?,?,?,?,?,?,?)",
                shareId, generation, actor.key(), input.requestKey(), fingerprint, receipt, generated.getKey().longValue());
        return new FillReceipt(true, receipt);
    }

    private record Access(Map<String, Object> form, Map<String, Object> share) {}
    private record Actor(String key, String tenant, String creator) {}

    private Access access(String token, boolean lock) {
        if (token == null || !token.matches("[A-Za-z0-9_-]{43}")) throw unavailable();
        var tokenHash = hash(token);
        var found = jdbc.queryForList("SELECT form_id FROM sys_lowcode_form_share WHERE token_hash=?", tokenHash);
        if (found.isEmpty()) throw unavailable();
        long formId = ((Number) found.get(0).get("form_id")).longValue();
        var form = form(formId, lock);
        var rows = jdbc.queryForList("SELECT * FROM sys_lowcode_form_share WHERE form_id=?" + (lock ? " FOR UPDATE" : ""), formId);
        if (rows.isEmpty()) throw unavailable();
        var share = rows.get(0);
        if (number(share, "enabled") != 1 || !tokenHash.equals(share.get("token_hash"))) throw unavailable();
        published(form);
        return new Access(form, share);
    }

    private Actor actor(Map<String, Object> share) {
        var tenant = String.valueOf(share.get("tenant_id"));
        if (SecurityUtils.isLogin()) {
            if (!tenant.equals(identity.tenant())) throw fail(403, "当前账号不在此表单的填写范围内");
            var creator = identity.creator();
            return new Actor("user:" + tenant + ":" + creator, tenant, creator);
        }
        if (!"public".equals(share.get("access_mode"))) throw fail(401, "请登录后填写此表单");
        // Anonymous submissions belong to the share's server-bound tenant, never its administrator.
        return new Actor("guest", tenant, "0");
    }

    private Map<String, Object> form(long id, boolean lock) {
        var rows = jdbc.queryForList("SELECT * FROM sys_lowcode_form WHERE id=? AND is_deleted=0" + (lock ? " FOR UPDATE" : ""), id);
        if (rows.isEmpty()) throw unavailable();
        return rows.get(0);
    }
    private void published(Map<String, Object> form) {
        if (!"enabled".equals(form.get("status")) || number(form, "published_version") < 1) throw unavailable();
    }
    private LowcodeSchema.Schema publishedSchema(Map<String, Object> form) {
        return schemas.decode(jdbc.queryForObject("SELECT schema_json FROM sys_lowcode_form_version WHERE form_id=? AND schema_version=?",
                String.class, form.get("id"), form.get("published_version")));
    }
    private void sameTenant(Map<String, Object> share) {
        if (!identity.tenant().equals(share.get("tenant_id"))) throw fail(403, "不能管理其他范围的填写链接");
    }
    private ShareView shareView(long formId, Map<String, Object> share) {
        boolean enabled = number(share, "enabled") == 1;
        String token = enabled ? cipher.decrypt(purpose(formId), String.valueOf(share.get("token_ciphertext"))) : null;
        return new ShareView(enabled, String.valueOf(share.get("access_mode")), number(share, "revision"), token);
    }
    private static int number(Map<String, Object> row, String key) { return ((Number) row.get(key)).intValue(); }
    private String randomToken() { var bytes = new byte[32]; random.nextBytes(bytes); return Base64.getUrlEncoder().withoutPadding().encodeToString(bytes); }
    private static String purpose(long formId) { return "lowcode:share:" + formId; }
    private static String hash(String text) {
        try { return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(text.getBytes(StandardCharsets.UTF_8))); }
        catch (java.security.NoSuchAlgorithmException impossible) { throw new IllegalStateException(impossible); }
    }
    private static Failure unavailable() { return fail(410, "表单暂不可填写，请联系发布者获取有效链接"); }
    private static Failure fail(int status, String message) { return new Failure(status, message); }
}
