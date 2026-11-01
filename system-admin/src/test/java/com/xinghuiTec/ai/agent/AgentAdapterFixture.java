package com.xinghuiTec.ai.agent;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.xinghuiTec.agent.api.*;
import com.xinghuiTec.domain.entity.*;
import com.xinghuiTec.mapper.SysUserMapper;
import com.xinghuiTec.security.PlatformAccessService;
import com.xinghuiTec.service.impl.UserDetailManageImpl;
import com.xinghuiTec.utils.*;
import org.springframework.context.support.GenericApplicationContext;
import org.springframework.core.env.MapPropertySource;
import org.springframework.security.core.context.SecurityContextHolder;
import java.time.Instant;
import java.util.*;
import static org.mockito.Mockito.*;

final class AgentAdapterFixture implements AutoCloseable {
    final ObjectMapper json=new ObjectMapper();
    final org.springframework.context.ApplicationContext previous=SpringUtils.getApplicationContext();
    final GenericApplicationContext spring=new GenericApplicationContext();
    final SysUserMapper users=mock(SysUserMapper.class);
    final UserDetailManageImpl identities=mock(UserDetailManageImpl.class);
    final Map<Long,SysUser> rows=new HashMap<>();
    Set<String> permissions=new HashSet<>(Set.of("ai:agent:invoke","lowcode:form:list","lowcode:record:list","lowcode:record:query","lowcode:record:add","lowcode:record:edit","lowcode:record:remove","system:businessConfig:query","system:businessConfig:edit","system:config:list","system:config:query","system:config:add","system:config:edit","system:config:remove"));
    final AgentActor a=new AgentActor("tenant_a","1"),b=new AgentActor("tenant_b","2"),platform=new AgentActor("000000","3");
    final AgentAccess access;
    AgentAdapterFixture(){
        spring.getEnvironment().getPropertySources().addFirst(new MapPropertySource("agent-fixture",Map.of("tenant.enable","true")));spring.refresh();new SpringUtils().setApplicationContext(spring);
        for(var actor:List.of(a,b,platform)){var user=new SysUser();user.setUserId(Long.valueOf(actor.userId()));user.setTenantId(actor.tenantId());user.setStatus(1);user.setMobile("1990000000"+actor.userId());rows.put(user.getUserId(),user);}
        when(users.selectById(any(java.io.Serializable.class))).thenAnswer(invocation->rows.get(Long.valueOf(invocation.getArgument(0).toString())));
        when(identities.loadUserByUsername(anyString())).thenAnswer(invocation->{var user=rows.values().stream().filter(row->row.getMobile().equals(invocation.getArgument(0))).findFirst().orElseThrow();var login=new LoginUser(user,new ArrayList<>(permissions));login.setTenantId(user.getTenantId());return login;});
        access=new AgentAccess(users,identities,new PlatformAccessService(true));
    }
    AgentCall call(AgentActor actor){return new AgentCall(actor,"fixture-session","fixture-run",UUID.randomUUID().toString(),Instant.now().plusSeconds(60),()->false);}
    public void close(){TenantHelper.clearDynamic();SecurityContextHolder.clearContext();new SpringUtils().setApplicationContext(previous);spring.close();}
}
