package com.xinghuiTec.quartz;

import org.h2.tools.RunScript;
import org.junit.jupiter.api.Test;

import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.sql.DriverManager;

import static org.junit.jupiter.api.Assertions.*;

class JobMigrationTest {
    @Test
    void oldTargetsRemainForAuditButEveryLegacyRowIsPausedAndUnbound() throws Exception {
        try (var connection = DriverManager.getConnection("jdbc:h2:mem:job01;MODE=MySQL;DB_CLOSE_DELAY=-1")) {
            try (var statement = connection.createStatement()) {
                statement.execute("DROP TABLE IF EXISTS sys_job");
                statement.execute("""
                        CREATE TABLE sys_job (
                          job_id BIGINT PRIMARY KEY,
                          invoke_target VARCHAR(500) NOT NULL,
                          status CHAR(1) NOT NULL
                        )
                        """);
                statement.execute("INSERT INTO sys_job VALUES (1, 'danger.Bean.method', '0')");
                statement.execute("INSERT INTO sys_job VALUES (2, 'backup.run', '1')");
            }
            try (var resource = getClass().getResourceAsStream(
                    "/db/migration/V20260926_30__safe_scheduler_model.sql")) {
                assertNotNull(resource);
                RunScript.execute(connection, new InputStreamReader(resource, StandardCharsets.UTF_8));
            }
            try (var statement = connection.createStatement();
                    var rows = statement.executeQuery(
                            "SELECT invoke_target, handler_key, status, handler_params, time_zone FROM sys_job ORDER BY job_id")) {
                assertTrue(rows.next());
                assertEquals("danger.Bean.method", rows.getString(1));
                assertNull(rows.getString(2));
                assertEquals("1", rows.getString(3));
                assertEquals("{}", rows.getString(4));
                assertEquals("Asia/Shanghai", rows.getString(5));
                assertTrue(rows.next());
                assertEquals("backup.run", rows.getString(1));
                assertNull(rows.getString(2));
                assertEquals("1", rows.getString(3));
                assertFalse(rows.next());
            }
        }
    }
}
