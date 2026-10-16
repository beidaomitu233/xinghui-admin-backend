package com.xinghuiTec.ai.template;

import org.flywaydb.core.Flyway;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import java.util.List;
import static org.junit.jupiter.api.Assertions.*;

/** Explicit C window only. No alternate URL/schema credentials, repair, reset or external provider. */
class TemplateDataMySqlIT {
    static DriverManagerDataSource source() {
        assertEquals("true", System.getenv("AI_V04_C_NATIVE"), "PM native window flag required");
        String password = System.getenv("AI_V04_C_DB_PASSWORD"); assertNotNull(password, "Private C password required");
        var source = new DriverManagerDataSource("jdbc:mysql://127.0.0.1:13319/aiv04_c_20261016?useSSL=false&allowPublicKeyRetrieval=true&serverTimezone=Asia/Shanghai", "ai_v04_c", password);
        var jdbc = new JdbcTemplate(source);
        assertEquals("aiv04_c_20261016", jdbc.queryForObject("SELECT DATABASE()", String.class));
        assertEquals(13319, jdbc.queryForObject("SELECT @@port", Integer.class));
        assertTrue(jdbc.queryForObject("SELECT CURRENT_USER()", String.class).startsWith("ai_v04_c@"));
        return source;
    }
    @Test void migrateAssignedEmptySchema() {
        var source = source(); var jdbc = new JdbcTemplate(source);
        assertEquals(0, jdbc.queryForObject("SELECT COUNT(*) FROM information_schema.tables WHERE table_schema=DATABASE()", Integer.class));
        var flyway = Flyway.configure().dataSource(source).locations("classpath:db/migration")
                .baselineOnMigrate(false).outOfOrder(false).cleanDisabled(true).target("20261016.210").load();
        var migrated = flyway.migrate(); flyway.validate();
        var versions = jdbc.queryForList("SELECT version FROM flyway_schema_history WHERE success=1 AND version LIKE '20261016.%' ORDER BY installed_rank", String.class);
        assertEquals(List.of("20261016.180", "20261016.190", "20261016.200", "20261016.210"), versions);
        assertEquals(0, jdbc.queryForObject("SELECT COUNT(*) FROM flyway_schema_history WHERE success=0", Integer.class));
        System.out.println("AI_V04_C_NATIVE normalFlyway=" + versions + " applied=" + migrated.migrationsExecuted + " validation=PASS");
    }
}
