package com.xinghuiTec.ai.agent;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.xinghuiTec.agent.persistence.JdbcAgentRepository;
import com.xinghuiTec.agent.persistence.JdbcAgentImages;
import com.xinghuiTec.agent.runtime.AgentWorkbench;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.*;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.transaction.PlatformTransactionManager;
import java.nio.file.Path;

@Configuration(proxyBeanMethods=false)
public class AgentWorkbenchConfiguration {
    @Bean public JdbcAgentImages agentImageStore(JdbcTemplate jdbc,JdbcAgentRepository repository,AgentPayloadCipher cipher,ObjectMapper json){return new JdbcAgentImages(jdbc,repository,cipher,json);}
    @Bean public JdbcAgentRepository agentRepository(JdbcTemplate jdbc,PlatformTransactionManager transactions,
                                                      AgentPayloadCipher cipher,AgentResourceRegistry resources,ObjectMapper json){
        return new JdbcAgentRepository(jdbc,transactions,cipher,resources,json);
    }
    @Bean(destroyMethod="close") public AgentWorkbench agentWorkbench(JdbcAgentRepository repository,AgentConfiguredModels prompts,
            AgentResourceRegistry resources,JdbcTemplate jdbc,PlatformTransactionManager transactions,AgentPayloadCipher cipher,ObjectMapper json,
            @Value("${ai.agent.workspace-root:${java.io.tmpdir}/xinghui-agent-workspaces}") String workspace,JdbcAgentImages images){
        var workbench=new AgentWorkbench(repository,prompts,resources,jdbc,transactions,cipher,json,Path.of(workspace));
        workbench.images(images);return workbench;
    }
}
