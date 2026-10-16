package com.xinghuiTec.ai.config;

import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.JdbcTemplate;
import static org.junit.jupiter.api.Assertions.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

/** Explicit local acceptance only. Never selected by CI; missing authorization fails instead of skipping. */
class AiConfigurationDruidNativeQaIT {
    @Test void integrationSchemaReadOnlyQueriesAlsoPassTheProductionWallWithoutAnyFixtures() throws Exception {
        assertEquals("true",System.getenv("AI_V04_A_LIST_READONLY"),"Assigned native read-only window required");
        String password=System.getenv("AI_V04_INTEGRATION_DB_PASSWORD");
        assertNotNull(password,"Assigned native password required");
        try(var source=AiConfigurationDruidHttpTest.dataSource("jdbc:mysql://127.0.0.1:13319/aiv04_integration_20261016?useSSL=false&allowPublicKeyRetrieval=true&serverTimezone=Asia/Shanghai","ai_v04_integration",password)) {
            source.setDefaultReadOnly(true); source.setDriverClassName("com.mysql.cj.jdbc.Driver");
            var jdbc=new JdbcTemplate(source);
            assertEquals("aiv04_integration_20261016",jdbc.queryForObject("SELECT DATABASE()",String.class));
            var blocked=assertThrows(org.springframework.jdbc.UncategorizedSQLException.class,()->jdbc.queryForObject("SELECT COUNT(*) FROM sys_ai_configuration WHERE 1=1",Long.class));
            assertTrue(blocked.getMostSpecificCause().getMessage().toLowerCase(java.util.Locale.ROOT).contains("sql injection violation"));
            var result=AiConfigurationDruidHttpTest.service(source).page(1,10,"","","","");
            assertNotNull(result.records()); assertEquals(1,result.pageNum()); assertEquals(10,result.pageSize());
            new AiConfigurationDruidHttpTest().http(source).perform(get("/system/ai/configurations").param("name","").param("providerCode","").param("environment","").param("status","").param("pageNum","1").param("pageSize","10"))
                    .andExpect(status().isOk()).andExpect(jsonPath("$.code").value(200)).andExpect(jsonPath("$.data.records").isArray());
        }
    }
}
