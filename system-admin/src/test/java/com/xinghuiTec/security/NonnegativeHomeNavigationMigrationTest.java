package com.xinghuiTec.security;

import com.xinghuiTec.domain.dto.SysMenuAddDTO;
import jakarta.validation.Validation;
import org.flywaydb.core.Flyway;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;
import java.sql.Connection;
import java.sql.DriverManager;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.*;

class NonnegativeHomeNavigationMigrationTest {
    @TempDir Path migrations;

    @Test
    void upgradesAppliedV300WithoutBreakingMenuEditContractOrReapplyingUserOrder() throws Exception {
        for (var script : List.of("V20261023_300__independent_home_navigation.sql",
                "V20261023_301__nonnegative_home_navigation_order.sql")) {
            try (var resource = getClass().getResourceAsStream("/db/migration/" + script)) {
                assertNotNull(resource);
                Files.copy(resource, migrations.resolve(script));
            }
        }
        var url = "jdbc:h2:mem:home_order_" + UUID.randomUUID()
                + ";MODE=MySQL;DATABASE_TO_LOWER=TRUE;DB_CLOSE_DELAY=-1";
        try (var connection = DriverManager.getConnection(url, "sa", "");
             var validation = Validation.buildDefaultValidatorFactory()) {
            try (var statement = connection.createStatement()) {
                statement.execute("CREATE TABLE sys_menu(menu_id BIGINT PRIMARY KEY,parent_id BIGINT,path VARCHAR(100),order_num INT,icon VARCHAR(100),menu_type CHAR(1),component VARCHAR(100),is_deleted INT,visible CHAR(1),status CHAR(1),perms VARCHAR(100))");
                statement.execute("CREATE TABLE sys_role_menu(role_id BIGINT,menu_id BIGINT)");
                statement.execute("INSERT INTO sys_menu VALUES "
                        + "(10,2,'/workspace',4,'','C','dashboard/workspace/index',0,'0','0',NULL),"
                        + "(11,2,'/analytics',5,'','C','dashboard/analytics/index',0,'1','1','stats:read'),"
                        + "(2,0,'/ops',3,'keep','M','',0,'0','0',NULL),"
                        + "(3,0,'/system',3,'keep','M','',0,'0','0',NULL),"
                        + "(4,0,'/custom',-100,'keep','M','',0,'0','0',NULL),"
                        + "(5,0,'/last',2147483647,'keep','M','',0,'1','1','custom:read'),"
                        + "(6,0,'/unordered',NULL,'keep','M','',0,'0','0',NULL),"
                        + "(12,2,'/deleted',9,'keep','C','dashboard/workspace/index',1,'0','0',NULL),"
                        + "(20,2,'child',7,'keep','C','system/job/index',0,'0','0','job:read')");
                statement.execute("INSERT INTO sys_role_menu VALUES (1,10),(1,2),(2,11),(2,5),(3,20)");
            }
            var location = "filesystem:" + migrations.toAbsolutePath();
            var appliedV300 = Flyway.configure().dataSource(url, "sa", "")
                    .locations(location).baselineOnMigrate(true).baselineVersion("20261023.299")
                    .target("20261023.300").load();
            assertEquals(1, appliedV300.migrate().migrationsExecuted);
            assertFalse(validation.getValidator().validateValue(SysMenuAddDTO.class, "orderNum", -20).isEmpty());
            var metadata = query(connection, "SELECT menu_id,parent_id,path,icon,menu_type,component,is_deleted,visible,status,perms FROM sys_menu ORDER BY menu_id");
            var grants = query(connection, "SELECT role_id,menu_id FROM sys_role_menu ORDER BY role_id,menu_id");
            var oldChecksum = query(connection, "SELECT checksum FROM flyway_schema_history WHERE version='20261023.300'");

            var upgrade = Flyway.configure().dataSource(url, "sa", "").locations(location).load();
            assertEquals(1, upgrade.migrate().migrationsExecuted);
            upgrade.validate();
            assertEquals(oldChecksum, query(connection, "SELECT checksum FROM flyway_schema_history WHERE version='20261023.300'"));
            assertEquals(metadata, query(connection, "SELECT menu_id,parent_id,path,icon,menu_type,component,is_deleted,visible,status,perms FROM sys_menu ORDER BY menu_id"));
            assertEquals(grants, query(connection, "SELECT role_id,menu_id FROM sys_role_menu ORDER BY role_id,menu_id"));
            assertEquals(List.of(10L, 11L, 6L, 4L, 2L, 3L, 5L),
                    query(connection, "SELECT menu_id FROM sys_menu WHERE parent_id=0 AND is_deleted=0 ORDER BY order_num,menu_id")
                            .stream().map(row -> ((Number) row.get(0)).longValue()).toList());
            var orders = query(connection, "SELECT order_num FROM sys_menu WHERE parent_id=0 AND is_deleted=0 ORDER BY order_num,menu_id");
            for (int index = 0; index < orders.size(); index++) {
                int order = ((Number) orders.get(index).get(0)).intValue();
                assertEquals(index, order);
                assertTrue(validation.getValidator().validateValue(SysMenuAddDTO.class, "orderNum", order).isEmpty());
            }
            assertEquals(7, ((Number) query(connection, "SELECT order_num FROM sys_menu WHERE menu_id=20").get(0).get(0)).intValue());
            assertEquals(9, ((Number) query(connection, "SELECT order_num FROM sys_menu WHERE menu_id=12").get(0).get(0)).intValue());
            try (var statement = connection.createStatement()) {
                statement.executeUpdate("UPDATE sys_menu SET order_num=99 WHERE menu_id=2");
            }
            assertEquals(0, upgrade.migrate().migrationsExecuted);
            assertEquals(99, ((Number) query(connection, "SELECT order_num FROM sys_menu WHERE menu_id=2").get(0).get(0)).intValue());
        }
    }

    private static List<List<Object>> query(Connection connection, String sql) throws Exception {
        var result = new ArrayList<List<Object>>();
        try (var statement = connection.createStatement(); var rows = statement.executeQuery(sql)) {
            while (rows.next()) {
                var row = new ArrayList<Object>();
                for (int column = 1; column <= rows.getMetaData().getColumnCount(); column++) row.add(rows.getObject(column));
                result.add(row);
            }
        }
        return result;
    }
}
