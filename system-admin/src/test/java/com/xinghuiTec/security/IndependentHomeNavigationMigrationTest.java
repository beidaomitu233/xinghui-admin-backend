package com.xinghuiTec.security;

import org.h2.tools.RunScript;
import org.junit.jupiter.api.Test;

import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.sql.DriverManager;

import static org.junit.jupiter.api.Assertions.*;

class IndependentHomeNavigationMigrationTest {
    @Test
    void promotesOnlyExistingHomesWithoutChangingRoleGrantsOrHiddenState() throws Exception {
        try (var connection = DriverManager.getConnection("jdbc:h2:mem:use01;MODE=MySQL;DB_CLOSE_DELAY=-1")) {
            try (var statement = connection.createStatement()) {
                statement.execute("CREATE TABLE sys_menu(menu_id BIGINT PRIMARY KEY,parent_id BIGINT,path VARCHAR(100),order_num INT,icon VARCHAR(100),menu_type CHAR(1),component VARCHAR(100),is_deleted INT,visible CHAR(1),status CHAR(1),perms VARCHAR(100))");
                statement.execute("CREATE TABLE sys_role_menu(role_id BIGINT,menu_id BIGINT)");
                statement.execute("INSERT INTO sys_menu VALUES(10,2,'/workspace',4,'','C','dashboard/workspace/index',0,'0','0',NULL),(11,2,'/analytics',5,'','C','dashboard/analytics/index',0,'1','1','stats:read'),(2,0,'/ops',3,'carbon:dictionary','M','',0,'0','0',NULL),(12,2,'/old',6,'keep','C','dashboard/workspace/index',1,'0','0',NULL)");
                statement.execute("INSERT INTO sys_role_menu VALUES(1,10),(2,2)");
            }
            for (int repeat = 0; repeat < 2; repeat++) {
                try (var resource = getClass().getResourceAsStream("/db/migration/V20261023_300__independent_home_navigation.sql")) {
                    assertNotNull(resource);
                    RunScript.execute(connection, new InputStreamReader(resource, StandardCharsets.UTF_8));
                }
            }
            try (var statement = connection.createStatement();
                 var rows = statement.executeQuery("SELECT menu_id,parent_id,path,order_num,visible,status,perms,icon FROM sys_menu WHERE is_deleted=0 ORDER BY order_num")) {
                assertTrue(rows.next());
                assertEquals(10, rows.getLong("menu_id"));
                assertEquals(0, rows.getLong("parent_id"));
                assertEquals("/workspace", rows.getString("path"));
                assertEquals(-20, rows.getInt("order_num"));
                assertEquals("carbon:home", rows.getString("icon"));
                assertTrue(rows.next());
                assertEquals(11, rows.getLong("menu_id"));
                assertEquals(0, rows.getLong("parent_id"));
                assertEquals("/analytics", rows.getString("path"));
                assertEquals(-10, rows.getInt("order_num"));
                assertEquals("1", rows.getString("visible"));
                assertEquals("1", rows.getString("status"));
                assertEquals("stats:read", rows.getString("perms"));
                assertTrue(rows.next());
                assertEquals(2, rows.getLong("menu_id"));
                assertEquals("carbon:notebook", rows.getString("icon"));
                assertFalse(rows.next());
            }
            try (var statement = connection.createStatement();
                 var rows = statement.executeQuery("SELECT role_id,menu_id FROM sys_role_menu ORDER BY role_id")) {
                assertTrue(rows.next());
                assertEquals(1, rows.getLong(1));
                assertEquals(10, rows.getLong(2));
                assertTrue(rows.next());
                assertEquals(2, rows.getLong(1));
                assertEquals(2, rows.getLong(2));
                assertFalse(rows.next());
            }
            try (var statement = connection.createStatement();
                 var rows = statement.executeQuery("SELECT parent_id,icon FROM sys_menu WHERE menu_id=12")) {
                assertTrue(rows.next());
                assertEquals(2, rows.getLong(1));
                assertEquals("keep", rows.getString(2));
            }
        }
    }
}
