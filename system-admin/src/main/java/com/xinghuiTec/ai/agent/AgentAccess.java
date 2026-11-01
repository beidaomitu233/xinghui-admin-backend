package com.xinghuiTec.ai.agent;

import com.xinghuiTec.agent.api.*;
import com.xinghuiTec.ai.contract.AiTypes.TrustedActor;
import com.xinghuiTec.domain.entity.LoginUser;
import com.xinghuiTec.mapper.SysUserMapper;
import com.xinghuiTec.security.PlatformAccessService;
import com.xinghuiTec.service.impl.UserDetailManageImpl;
import com.xinghuiTec.utils.TenantHelper;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.security.core.userdetails.UsernameNotFoundException;
import org.springframework.stereotype.Component;
import java.util.Set;
import java.util.function.Function;
import java.util.stream.Collectors;

/** Background workers never inherit an HTTP thread's identity or cached login permissions. */
@Component
public class AgentAccess {
    private final SysUserMapper users;
    private final UserDetailManageImpl identities;
    private final PlatformAccessService platform;
    public AgentAccess(SysUserMapper users,UserDetailManageImpl identities,PlatformAccessService platform){this.users=users;this.identities=identities;this.platform=platform;}
    public record Context(TrustedActor actor,boolean platform) {
        public boolean has(String permission){return actor.authorities().contains(permission);}
        public void require(String permission){if(!has(permission))throw denied();}
        public void requirePlatform(){if(!platform)throw denied();}
    }
    public <T>T as(AgentActor actor,Function<Context,T> work) {
        var previous=SecurityContextHolder.getContext();String previousTenant=TenantHelper.getDynamic();
        try {
            if(actor==null||!actor.userId().matches("[1-9][0-9]{0,18}")||!actor.tenantId().matches("[A-Za-z0-9_-]{1,20}"))throw denied();
            SecurityContextHolder.setContext(SecurityContextHolder.createEmptyContext());TenantHelper.setDynamic(actor.tenantId());
            var user=TenantHelper.ignore(()->users.selectById(Long.parseLong(actor.userId())));
            if(user==null||!actor.tenantId().equals(user.getTenantId())||user.getMobile()==null||user.getStatus()!=null&&user.getStatus()!=1)throw denied();
            if(!(identities.loadUserByUsername(user.getMobile()) instanceof LoginUser login)||!login.isEnabled()
                    ||login.getUser()==null||!actor.userId().equals(String.valueOf(login.getUser().getUserId()))
                    ||!actor.tenantId().equals(login.getTenantId())||!actor.tenantId().equals(login.getUser().getTenantId()))throw denied();
            var authentication=new UsernamePasswordAuthenticationToken(login,null,login.getAuthorities());
            SecurityContextHolder.getContext().setAuthentication(authentication);
            Set<String> permissions=authentication.getAuthorities().stream().map(a->a.getAuthority()).collect(Collectors.toUnmodifiableSet());
            var context=new Context(new TrustedActor(actor.userId(),actor.tenantId(),permissions,null),platform.isPlatformIdentity());
            context.require("ai:agent:invoke");return work.apply(context);
        }catch(UsernameNotFoundException missing){throw denied();}
        finally {
            TenantHelper.clearDynamic();if(previousTenant!=null)TenantHelper.setDynamic(previousTenant);
            SecurityContextHolder.setContext(previous);
        }
    }
    /** Only for providers invoked inside as(...). A model-supplied identity cannot replace it. */
    public Context current(AgentActor actor) {
        var authentication=SecurityContextHolder.getContext().getAuthentication();
        if(authentication==null||!authentication.isAuthenticated()||!(authentication.getPrincipal() instanceof LoginUser login)
                ||!login.isEnabled()||login.getUser()==null||!actor.userId().equals(String.valueOf(login.getUser().getUserId()))
                ||!actor.tenantId().equals(login.getTenantId())||!actor.tenantId().equals(login.getUser().getTenantId()))throw denied();
        var permissions=authentication.getAuthorities().stream().map(a->a.getAuthority()).collect(Collectors.toUnmodifiableSet());
        var context=new Context(new TrustedActor(actor.userId(),actor.tenantId(),permissions,null),platform.isPlatformIdentity());
        context.require("ai:agent:invoke");return context;
    }
    static AgentProblem denied(){return new AgentProblem("FORBIDDEN","当前账号无权执行此操作。");}
}
