package com.xinghuiTec.ai.agent;

import com.xinghuiTec.agent.api.AgentActor;
import com.xinghuiTec.agent.persistence.JdbcAgentRepository;
import com.xinghuiTec.ai.application.AiAgentCallAccess;
import com.xinghuiTec.ai.contract.AiTypes.TrustedActor;
import org.springframework.stereotype.Component;

@Component
public class AgentLogAccess implements AiAgentCallAccess {
    private final AgentAccess access;
    private final JdbcAgentRepository repository;
    public AgentLogAccess(AgentAccess access,JdbcAgentRepository repository){this.access=access;this.repository=repository;}
    @Override public boolean canRead(TrustedActor actor,String sessionId,String runId){
        if(actor==null||actor.servicePrincipalRef()!=null||sessionId==null||runId==null)return false;
        try{var owner=new AgentActor(actor.tenantId(),actor.subjectId());return access.as(owner,current->{
            var run=repository.run(owner,runId);return sessionId.equals(run.sessionId());
        });}catch(RuntimeException denied){return false;}
    }
}
