package com.xinghuiTec.ai.scene;

import com.xinghuiTec.security.PlatformAccessService;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.stereotype.Component;

import java.util.Set;

/** Navigation entry is an OR, never a grant of log/audit/configuration API permissions. */
@Component("aiWorkspaceAccess")
public class AiWorkspaceAccessService {
    private static final Set<String> MONITOR=Set.of("ai:scene:invoke","ai:request:list","ai:config:list","ai:config:query","ai:config:add","ai:config:edit","ai:config:test","ai:config:enable");
    private final PlatformAccessService platform;
    public AiWorkspaceAccessService(PlatformAccessService platform){this.platform=platform;}
    public boolean canEnterMonitor(){var auth=SecurityContextHolder.getContext().getAuthentication();return platform.isPlatformIdentity() && auth!=null
            && auth.isAuthenticated() && auth.getAuthorities().stream().anyMatch(a->MONITOR.contains(a.getAuthority()));}
}
