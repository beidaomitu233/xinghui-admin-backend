package com.xinghuiTec.lowcode;

import org.flywaydb.core.Flyway;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import java.util.List;
import static org.junit.jupiter.api.Assertions.*;
import static com.xinghuiTec.lowcode.LowcodeSharingFixture.*;

/** Explicit assigned old-version QA schema. Never cleans, repairs or recreates a database. */
class LowcodeSharingUpgradeIT {
    @Test void old41MigrationsAndExistingFormVersionsAndAnswersSurviveV320() throws Exception {
        String url = System.getenv("DB_URL");
        assertNotNull(url);
        assertTrue(url.startsWith("jdbc:mysql://127.0.0.1:13323/use04_upgrade_20261023?"));
        var source = new DriverManagerDataSource(url, System.getenv("DB_USERNAME"), System.getenv("DB_PASSWORD"));
        var jdbc = new JdbcTemplate(source);
        assertEquals("use04_upgrade_20261023", jdbc.queryForObject("SELECT DATABASE()", String.class));
        var before = Flyway.configure().dataSource(source).locations("classpath:db/migration").cleanDisabled(true)
                .baselineOnMigrate(false).outOfOrder(false).target("20261023.310").load();
        before.migrate(); before.validate();
        assertEquals(41, jdbc.queryForObject("SELECT COUNT(*) FROM flyway_schema_history WHERE success=1", Integer.class));
        assertEquals("20261023.310", before.info().current().getVersion().toString());
        var grants = jdbc.queryForList("SELECT role_id,menu_id FROM sys_role_menu ORDER BY role_id,menu_id");
        try (var fixture = new LowcodeSharingFixture(source, false)) {
            manager();
            long formId = Long.parseLong(fixture.forms.create(new LowcodeService.Draft("升级前表单", "use04_upgrade_20261023", "draft", 0, fixture.schema())));
            fixture.forms.publish(formId, 0);
            var oldRecord = fixture.forms.addRecord(formId, new LowcodeService.Submission(1, null, fixture.json.readTree("{\"name\":\"升级前答案\"}")));
            long recordId = Long.parseLong(String.valueOf(oldRecord.get("id")));
            var forms = jdbc.queryForList("SELECT * FROM sys_lowcode_form WHERE id=?", formId);
            var versions = jdbc.queryForList("SELECT * FROM sys_lowcode_form_version WHERE form_id=?", formId);
            var records = jdbc.queryForList("SELECT * FROM sys_lowcode_record WHERE form_id=?", formId);
            var upgrade = Flyway.configure().dataSource(source).locations("classpath:db/migration").cleanDisabled(true)
                    .baselineOnMigrate(false).outOfOrder(false).load();
            assertEquals(1, upgrade.migrate().migrationsExecuted); upgrade.validate();
            assertEquals(42, jdbc.queryForObject("SELECT COUNT(*) FROM flyway_schema_history WHERE success=1", Integer.class));
            assertEquals(forms, jdbc.queryForList("SELECT * FROM sys_lowcode_form WHERE id=?", formId));
            assertEquals(versions, jdbc.queryForList("SELECT * FROM sys_lowcode_form_version WHERE form_id=?", formId));
            assertEquals(records, jdbc.queryForList("SELECT * FROM sys_lowcode_record WHERE form_id=?", formId));
            assertEquals(grants, jdbc.queryForList("SELECT role_id,menu_id FROM sys_role_menu ORDER BY role_id,menu_id"));
            assertFalse(fixture.sharing.settings(formId).enabled());
            var form = fixture.forms.form(formId);
            var next = new LowcodeSchema.Schema(List.of(fixture.text("name", true), fixture.text("extra", false)));
            fixture.forms.update(formId, new LowcodeService.Draft("升级前表单", "use04_upgrade_20261023", "enabled", ((Number) form.get("revision")).intValue(), next));
            fixture.forms.publish(formId, ((Number) fixture.forms.form(formId).get("revision")).intValue());
            var restored = fixture.forms.record(formId, recordId);
            assertEquals(1, restored.get("schemaVersion"));
            assertEquals("升级前答案", ((com.fasterxml.jackson.databind.JsonNode) restored.get("data")).path("name").asText());
            fixture.forms.updateRecord(formId, recordId, new LowcodeService.Submission(1, 0, fixture.json.readTree("{\"name\":\"升级后按原版本编辑\"}")));
            assertEquals("000000", fixture.forms.record(formId, recordId).get("tenantId"));
            assertEquals("1", fixture.forms.record(formId, recordId).get("creator"));
            assertEquals(0, upgrade.migrate().migrationsExecuted);
            assertEquals(0, jdbc.queryForObject("SELECT COUNT(*) FROM flyway_schema_history WHERE success=0", Integer.class));
        }
    }
}
