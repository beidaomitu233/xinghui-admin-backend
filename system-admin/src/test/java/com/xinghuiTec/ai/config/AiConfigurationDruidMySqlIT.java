package com.xinghuiTec.ai.config;

import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.JdbcTemplate;
import static org.junit.jupiter.api.Assertions.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

/** Runs after ReleaseServicesIT against its migrated disposable MySQL container. No conditional skip. */
class AiConfigurationDruidMySqlIT {
    @Test void migratedCiSchemaSupportsEmptyAndFilteredListsThroughRealMysqlWall() throws Exception {
        assertEquals("true",System.getenv("CI"),"Only the disposable CI services job may run this test");
        String url=System.getenv("DB_URL");
        assertNotNull(url,"Missing isolated DB_URL");
        assertTrue(url.startsWith("jdbc:mysql://127.0.0.1:3306/xinghui_oss04_ci?"),"Unexpected CI database target");
        assertEquals("oss04_ci",System.getenv("DB_USERNAME"));
        String password=System.getenv("DB_PASSWORD"); assertNotNull(password,"Missing isolated DB_PASSWORD");
        try(var source=AiConfigurationDruidHttpTest.dataSource(url,"oss04_ci",password)) {
            source.setDefaultReadOnly(true); source.setDriverClassName("com.mysql.cj.jdbc.Driver");
            var jdbc=new JdbcTemplate(source);
            assertEquals("xinghui_oss04_ci",jdbc.queryForObject("SELECT DATABASE()",String.class));
            String version=jdbc.queryForObject("SELECT VERSION()",String.class);
            assertTrue(version.startsWith(System.getenv("OSS04_MYSQL_SERIES")+"."),"Wrong MySQL matrix series");
            var blocked=assertThrows(org.springframework.jdbc.UncategorizedSQLException.class,()->jdbc.queryForObject("SELECT COUNT(*) FROM sys_ai_configuration WHERE 1=1",Long.class));
            assertTrue(blocked.getMostSpecificCause().getMessage().toLowerCase(java.util.Locale.ROOT).contains("sql injection violation"));
            var mvc=new AiConfigurationDruidHttpTest().http(source);
            mvc.perform(get("/system/ai/configurations").param("name","").param("providerCode","").param("environment","").param("status","").param("pageNum","1").param("pageSize","10"))
                    .andExpect(status().isOk()).andExpect(jsonPath("$.code").value(200)).andExpect(jsonPath("$.data.records").isArray());
            mvc.perform(get("/system/ai/configurations").param("name","ci-absent-configuration").param("providerCode","openrouter").param("environment","ci").param("status","DRAFT").param("pageNum","2").param("pageSize","3"))
                    .andExpect(status().isOk()).andExpect(jsonPath("$.data.records").isEmpty()).andExpect(jsonPath("$.data.pageNum").value(2));
            mvc.perform(get("/system/ai/configurations").param("pageNum","invalid"))
                    .andExpect(status().isBadRequest()).andExpect(jsonPath("$.code").value(202));
        }
    }
}
