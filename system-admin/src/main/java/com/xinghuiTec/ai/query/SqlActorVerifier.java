package com.xinghuiTec.ai.query;

import com.xinghuiTec.ai.contract.AiRuntimeException;
import com.xinghuiTec.ai.contract.AiTypes.*;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;
import java.util.*;

/** Rechecks account and effective role grants even on executor threads without an HTTP context. */
@Component
public final class SqlActorVerifier {
    private static final Set<String> ENTRY = Set.of("ai:scene:invoke", "ai:scene:query", "ai:scene:add", "ai:scene:edit", "ai:scene:test");
    private final JdbcTemplate jdbc;
    private final boolean tenantEnabled;
    public SqlActorVerifier(JdbcTemplate jdbc, @Value("${tenant.enable:false}") boolean tenantEnabled) {
        this.jdbc = jdbc; this.tenantEnabled = tenantEnabled;
    }
    TrustedActor verify(TrustedActor actor) {
        if (actor == null || actor.servicePrincipalRef() != null || !actor.subjectId().matches("[1-9][0-9]{0,18}")
                || !actor.tenantId().matches("[A-Za-z0-9_-]{1,64}")) throw new AiRuntimeException(ErrorCode.UNAUTHENTICATED);
        final long userId;
        try { userId = Long.parseLong(actor.subjectId()); }
        catch (NumberFormatException invalid) { throw new AiRuntimeException(ErrorCode.UNAUTHENTICATED); }
        Long matches = jdbc.queryForObject("SELECT COUNT(*) FROM sys_user WHERE user_id=? AND tenant_id=? AND status=1 AND is_deleted=0",
                Long.class, userId, actor.tenantId());
        if (!Long.valueOf(1).equals(matches)) throw new AiRuntimeException(ErrorCode.RESOURCE_FORBIDDEN);
        if (tenantEnabled && !"000000".equals(actor.tenantId())) {
            Long active = jdbc.queryForObject("SELECT COUNT(*) FROM sys_tenant WHERE tenant_id=? AND is_deleted=0 "
                    + "AND status='0' AND (expire_time IS NULL OR expire_time>=CURRENT_TIMESTAMP)", Long.class, actor.tenantId());
            if (!Long.valueOf(1).equals(active)) throw new AiRuntimeException(ErrorCode.RESOURCE_FORBIDDEN);
        }
        String sql = "SELECT DISTINCT m.perms FROM sys_user_role ur JOIN sys_role r ON r.role_id=ur.role_id "
                + "AND r.status=1 AND r.is_deleted=0 JOIN sys_role_menu rm ON rm.role_id=r.role_id "
                + "JOIN sys_menu m ON m.menu_id=rm.menu_id AND m.status='0' AND m.is_deleted=0 "
                + "WHERE ur.user_id=?" + (tenantEnabled ? " AND r.tenant_id=?" : "");
        Object[] parameters = tenantEnabled ? new Object[]{userId, actor.tenantId()} : new Object[]{userId};
        var current = new HashSet<>(jdbc.query(sql, (rs, index) -> rs.getString(1), parameters));
        current.remove(null);
        current.retainAll(actor.authorities());
        if (current.stream().noneMatch(ENTRY::contains)) throw new AiRuntimeException(ErrorCode.RESOURCE_FORBIDDEN);
        return new TrustedActor(actor.subjectId(), actor.tenantId(), current, null);
    }
    boolean platform(TrustedActor actor) { return !tenantEnabled || "000000".equals(actor.tenantId()); }
    String tenant(TrustedActor actor) { return tenantEnabled ? actor.tenantId() : "000000"; }
}
