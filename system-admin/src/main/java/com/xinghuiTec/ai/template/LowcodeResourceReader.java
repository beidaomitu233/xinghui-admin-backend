package com.xinghuiTec.ai.template;

import com.fasterxml.jackson.databind.JsonNode;
import com.xinghuiTec.domain.entity.LoginUser;
import com.xinghuiTec.domain.entity.SysUser;
import com.xinghuiTec.lowcode.LowcodeIdentity;
import com.xinghuiTec.lowcode.LowcodeSchema;
import com.xinghuiTec.lowcode.LowcodeService;
import com.xinghuiTec.utils.SecurityUtils;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.stereotype.Component;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;
import com.xinghuiTec.ai.contract.AiTypes.InvocationControl;
import jakarta.annotation.PreDestroy;
import java.time.Instant;
import java.time.Duration;
import java.util.concurrent.*;
import java.util.*;

/** Code registered read-only source. No request DTO may supply an Actor. */
@Component
public class LowcodeResourceReader {
    public record Actor(String subjectId, String tenantId, Set<String> authorities) {
        public Actor { authorities = Set.copyOf(authorities); }
        @Override public String toString() { return "Actor[REDACTED]"; }
    }
    public record Read(Map<String, JsonNode> fields, int schemaVersion) {
        @Override public String toString() { return "Read[REDACTED]"; }
    }
    public static final class ResourceFailure extends RuntimeException {
        private final String code;
        public ResourceFailure(String code) { super(code); this.code = code; }
        public String code() { return code; }
    }
    private final LowcodeService lowcode;
    private final LowcodeIdentity identity;
    private final JdbcTemplate jdbc;
    private final PlatformTransactionManager transactions;
    private final ExecutorService reads = new ThreadPoolExecutor(2, 4, 30, TimeUnit.SECONDS,
            new ArrayBlockingQueue<>(16), r -> { var t = new Thread(r, "ai-resource-read"); t.setDaemon(true); return t; }, new ThreadPoolExecutor.AbortPolicy());
    public LowcodeResourceReader(LowcodeService lowcode, LowcodeIdentity identity, JdbcTemplate jdbc, PlatformTransactionManager transactions) {
        this.lowcode = lowcode; this.identity = identity; this.jdbc = jdbc; this.transactions = transactions;
    }
    @PreDestroy public void close() { reads.shutdownNow(); }
    public Actor currentActor() {
        LoginUser login = SecurityUtils.getLoginUser();
        if (!SecurityUtils.isLogin() || login == null || !login.isEnabled() || login.getUser() == null
                || login.getUser().getUserId() == null) throw new AccessDeniedException("需要真实登录身份");
        return new Actor(String.valueOf(login.getUser().getUserId()), identity.tenant(),
                login.getAuthorities().stream().map(a -> a.getAuthority()).collect(java.util.stream.Collectors.toSet()));
    }
    public Read read(ResourceDefinition d, Actor actor, String appId, Map<String, String> parameters) {
        // The resource limit is classified separately from the enclosing invocation deadline.
        final Instant deadline = Instant.now().plusMillis(d.timeoutMillis() + 1000L);
        return read(d, actor, appId, parameters, new InvocationControl() {
            public Instant deadline() { return deadline; }
            public boolean cancelled() { return Thread.currentThread().isInterrupted(); }
        });
    }
    public Read read(ResourceDefinition d, Actor actor, String appId, Map<String, String> parameters, InvocationControl control) {
        control.check();
        final Instant resourceDeadline = Instant.now().plusMillis(d.timeoutMillis());
        final Instant deadline = resourceDeadline.isBefore(control.deadline()) ? resourceDeadline : control.deadline();
        Future<Read> future;
        try {
            future = reads.submit(() -> {
                if (!Instant.now().isBefore(deadline)) throw new ResourceFailure("TIMEOUT");
                var transaction = new TransactionTemplate(transactions);
                transaction.setReadOnly(true);
                // JdbcTemplate applies the transaction's remaining timeout to every Statement.
                transaction.setTimeout(Math.max(1, (int) Math.ceil(Duration.between(Instant.now(), deadline).toMillis() / 1000.0)));
                return transaction.execute(status -> { control.check(); return readNow(d, actor, appId, parameters); });
            });
        } catch (RejectedExecutionException overloaded) { throw new ResourceFailure("UNAVAILABLE"); }
        try {
            while (true) {
                control.check(); long millis = Duration.between(Instant.now(), deadline).toMillis();
                if (millis <= 0) throw new ResourceFailure("TIMEOUT");
                try { return future.get(Math.min(50, millis), TimeUnit.MILLISECONDS); }
                catch (TimeoutException waiting) { /* Recheck cancellation and the fixed absolute deadline. */ }
                catch (ExecutionException failure) {
                    if (failure.getCause() instanceof ResourceFailure classified) throw classified;
                    if (failure.getCause() instanceof com.xinghuiTec.ai.contract.AiRuntimeException classified) throw classified;
                    if (failure.getCause() instanceof org.springframework.dao.QueryTimeoutException
                            || failure.getCause() instanceof org.springframework.transaction.TransactionTimedOutException) throw new ResourceFailure("TIMEOUT");
                    throw new ResourceFailure("UNAVAILABLE");
                }
            }
        } catch (InterruptedException interrupted) {
            Thread.currentThread().interrupt(); throw new ResourceFailure("CANCELLED");
        } finally { future.cancel(true); }
    }
    private Read readNow(ResourceDefinition d, Actor actor, String appId, Map<String, String> parameters) {
        TemplateRules.validate(d);
        if (actor == null || !TemplateRules.id(actor.subjectId()) || !actor.authorities().contains("lowcode:record:query")
                || !d.subjects().contains(actor.subjectId()) || !d.applications().contains(appId)) throw new ResourceFailure("FORBIDDEN");
        if (parameters == null || !parameters.keySet().equals(Set.of("formId", "recordId"))
                || !d.formId().equals(parameters.get("formId")) || !TemplateRules.id(parameters.get("recordId"))) throw new ResourceFailure("INVALID_INPUT");
        // Recheck the account and its tenant immediately before reading; authority is captured at acceptance.
        var users = jdbc.queryForList("SELECT user_id,tenant_id FROM sys_user WHERE user_id=? AND tenant_id=? AND status=1 AND is_deleted=0",
                actor.subjectId(), actor.tenantId());
        if (users.size() != 1) throw new ResourceFailure("FORBIDDEN");
        var saved = SecurityContextHolder.getContext();
        long start = System.nanoTime();
        try {
            var user = new SysUser(); user.setUserId(Long.valueOf(actor.subjectId())); user.setTenantId(actor.tenantId()); user.setStatus(1);
            var login = new LoginUser(user, new ArrayList<>(actor.authorities())); login.setTenantId(actor.tenantId());
            var context = SecurityContextHolder.createEmptyContext();
            context.setAuthentication(new UsernamePasswordAuthenticationToken(login, null, login.getAuthorities()));
            SecurityContextHolder.setContext(context);
            if (!identity.tenant().equals(actor.tenantId())) throw new ResourceFailure("FORBIDDEN");
            Map<String, Object> record;
            try { record = lowcode.record(Long.parseLong(d.formId()), Long.parseLong(parameters.get("recordId"))); }
            catch (AccessDeniedException denied) { throw new ResourceFailure("FORBIDDEN"); }
            catch (com.xinghuiTec.exception.ServiceException failure) {
                if (Set.of("记录不存在", "表单不存在").contains(failure.getMessage())) throw new ResourceFailure("NOT_FOUND");
                throw new ResourceFailure("SCHEMA_MISMATCH");
            }
            if ((System.nanoTime() - start) / 1_000_000 >= d.timeoutMillis()) throw new ResourceFailure("TIMEOUT");
            var schema = (LowcodeSchema.Schema) record.get("schema");
            var available = schema.fields().stream().map(LowcodeSchema.Field::fieldKey).collect(java.util.stream.Collectors.toSet());
            if (!available.containsAll(d.fields())) throw new ResourceFailure("SCHEMA_MISMATCH");
            JsonNode data = (JsonNode) record.get("data");
            Map<String, JsonNode> result = new LinkedHashMap<>();
            for (String field : d.fields()) if (data.hasNonNull(field)) result.put(field, data.get(field).deepCopy());
            if (result.isEmpty()) throw new ResourceFailure("EMPTY");
            return new Read(Collections.unmodifiableMap(result), ((Number) record.get("schemaVersion")).intValue());
        } finally { SecurityContextHolder.setContext(saved); }
    }
}
