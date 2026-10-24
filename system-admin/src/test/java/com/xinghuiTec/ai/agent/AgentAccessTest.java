package com.xinghuiTec.ai.agent;

import com.xinghuiTec.agent.api.*;
import com.xinghuiTec.domain.entity.LoginUser;
import com.xinghuiTec.utils.*;
import org.junit.jupiter.api.Test;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.context.SecurityContextHolder;
import java.util.List;
import static org.junit.jupiter.api.Assertions.*;

class AgentAccessTest {
    @Test void currentDatabasePermissionsAndAccountStatusAreRecheckedRatherThanWorkerIdentity(){try(var f=new AgentAdapterFixture()){
        boolean before=f.access.as(f.a,context->context.has("lowcode:record:edit"));assertTrue(before);
        f.permissions.remove("lowcode:record:edit");boolean after=f.access.as(f.a,context->context.has("lowcode:record:edit"));assertFalse(after);
        f.permissions.remove("ai:agent:invoke");assertEquals("FORBIDDEN",assertThrows(AgentProblem.class,()->f.access.as(f.a,context->true)).code());
        f.permissions.add("ai:agent:invoke");f.rows.get(1L).setStatus(0);assertThrows(AgentProblem.class,()->f.access.as(f.a,context->true));
        f.rows.get(1L).setStatus(1);assertThrows(AgentProblem.class,()->f.access.as(new AgentActor("tenant_b","1"),context->true));
    }}
    @Test void nestedWorkersAndFailuresRestoreBothSecurityAndDynamicTenant(){try(var f=new AgentAdapterFixture()){
        var login=new LoginUser(f.rows.get(3L),List.of("stale-worker-permission"));login.setTenantId("000000");
        var previous=SecurityContextHolder.createEmptyContext();previous.setAuthentication(new UsernamePasswordAuthenticationToken(login,null,login.getAuthorities()));SecurityContextHolder.setContext(previous);TenantHelper.setDynamic("previous_scope");
        assertThrows(AgentProblem.class,()->f.access.as(f.a,context->{
            assertEquals("tenant_a",TenantHelper.getDynamic());assertEquals("tenant_a",SecurityUtils.getLoginUser().getTenantId());
            f.access.as(f.b,nested->{assertEquals("tenant_b",TenantHelper.getDynamic());assertFalse(nested.platform());return null;});
            assertEquals("tenant_a",TenantHelper.getDynamic());assertEquals("tenant_a",SecurityUtils.getLoginUser().getTenantId());
            throw new AgentProblem("FIXTURE_STOP","Synthetic stop");
        }));
        assertSame(previous,SecurityContextHolder.getContext());assertEquals("previous_scope",TenantHelper.getDynamic());
    }}
}
