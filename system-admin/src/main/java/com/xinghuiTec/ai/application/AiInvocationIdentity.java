package com.xinghuiTec.ai.application;

import com.xinghuiTec.ai.contract.AiRuntimeException;
import com.xinghuiTec.ai.contract.AiTypes.ErrorCode;
import com.xinghuiTec.ai.contract.AiTypes.TrustedActor;
import com.xinghuiTec.domain.entity.LoginUser;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.stereotype.Component;

import java.util.HashSet;

/** The HTTP caller cannot submit or choose a TrustedActor. Capture it before asynchronous work. */
@Component
public class AiInvocationIdentity {
    public TrustedActor current() {
        var authentication = SecurityContextHolder.getContext().getAuthentication();
        if (authentication == null || !authentication.isAuthenticated()
                || !(authentication.getPrincipal() instanceof LoginUser login)) {
            throw new AiRuntimeException(ErrorCode.UNAUTHENTICATED);
        }
        var user = login.getUser();
        String tenant = login.getTenantId();
        if (user == null || user.getUserId() == null || user.getUserId() <= 0 || !login.isEnabled()
                || tenant == null || tenant.isBlank() || tenant.length() > 64 || !tenant.equals(user.getTenantId())) {
            throw new AiRuntimeException(ErrorCode.UNAUTHENTICATED);
        }
        var authorities = new HashSet<String>();
        authentication.getAuthorities().forEach(authority -> authorities.add(authority.getAuthority()));
        return new TrustedActor(user.getUserId().toString(), tenant, authorities, null);
    }
}
