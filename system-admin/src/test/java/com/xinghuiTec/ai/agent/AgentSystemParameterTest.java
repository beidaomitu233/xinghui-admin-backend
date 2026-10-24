package com.xinghuiTec.ai.agent;

import com.xinghuiTec.agent.api.AgentProblem;
import com.xinghuiTec.domain.dto.ConfigChangeRequest;
import com.xinghuiTec.domain.vo.ConfigView;
import com.xinghuiTec.service.SysConfigService;
import org.junit.jupiter.api.Test;
import java.util.*;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

class AgentSystemParameterTest {
    @Test void parametersUseNativeCatalogPermissionsAndCompareAndSetRatherThanUnscopedConfigurationWrites()throws Exception{try(var f=new AgentAdapterFixture()){
        var configs=mock(SysConfigService.class);when(configs.listManaged()).thenReturn(List.of(new ConfigView(5,"sys.index.footerText","页脚","current",true,true)));
        when(configs.managedDefinitions()).thenReturn(List.of(new SysConfigService.Definition("sys.index.footerText","页脚",Set.of(),true)));
        var registry=new AgentResourceRegistry(List.of(new SystemParameterAgentResources(configs,f.access,f.json)),f.access,f.json);var admin=f.call(f.platform);
        assertEquals("5",registry.query(admin,"system-parameters",f.json.readTree("{}")).get(0).path("configId").asText());
        assertTrue(registry.describe(admin,"system-parameters").path("definitions").toString().contains("sys.index.footerText"));
        var change=f.json.readTree("{\"operation\":\"update\",\"id\":\"5\",\"configKey\":\"sys.index.footerText\",\"configValue\":\"next\",\"expectedValue\":\"current\"}");
        assertTrue(registry.mutate(admin,"config-edit","system-parameters",change).path("updated").asBoolean());
        verify(configs).updateManagedExpected(5,"current",new ConfigChangeRequest("sys.index.footerText","next"));
        assertThrows(AgentProblem.class,()->registry.query(f.call(f.a),"system-parameters",f.json.readTree("{}")));
        f.permissions.remove("system:config:edit");assertThrows(AgentProblem.class,()->registry.mutate(admin,"config-denied","system-parameters",change));
        verify(configs,times(1)).updateManagedExpected(anyInt(),anyString(),any());
        assertThrows(AgentProblem.class,()->registry.query(admin,"system-parameters",f.json.readTree("{\"tenantId\":\"tenant_a\"}")));
    }}
}
