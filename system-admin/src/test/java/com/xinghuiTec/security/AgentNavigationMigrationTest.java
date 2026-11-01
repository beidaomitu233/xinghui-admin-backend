package com.xinghuiTec.security;

import org.h2.tools.RunScript;
import org.junit.jupiter.api.Test;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.sql.DriverManager;
import java.util.*;
import static org.junit.jupiter.api.Assertions.*;

class AgentNavigationMigrationTest {
    @Test void independentEntryFollowsHomesPreservesOtherRootsAndDoesNotGrantTenantsOrDisabledRoles()throws Exception{
        try(var connection=DriverManager.getConnection("jdbc:h2:mem:agent_menu_"+UUID.randomUUID()+";MODE=MySQL")){
            try(var statement=connection.createStatement()){
                statement.execute("CREATE TABLE sys_menu(menu_id BIGINT PRIMARY KEY,menu_name VARCHAR(100),parent_id BIGINT,order_num INT,path VARCHAR(100),component VARCHAR(100),is_frame INT,menu_type CHAR(1),visible CHAR(1),status CHAR(1),perms VARCHAR(100),icon VARCHAR(100),is_deleted INT)");
                statement.execute("CREATE TABLE sys_role(role_id BIGINT PRIMARY KEY,tenant_id VARCHAR(20),role_key VARCHAR(30),status INT,is_deleted INT)");
                statement.execute("CREATE TABLE sys_role_menu(role_id BIGINT,menu_id BIGINT,PRIMARY KEY(role_id,menu_id))");
                statement.execute("INSERT INTO sys_menu VALUES(10,'工作台',0,0,'/workspace','dashboard/workspace/index',1,'C','0','0','home:read','keep',0),(11,'看板',0,1,'/analytics','dashboard/analytics/index',1,'C','0','0','stats:read','keep',0),(2,'系统',0,2,'/system','',1,'M','0','0',NULL,'keep',0),(3,'自定义',0,2147483647,'/custom','',1,'M','1','1','custom:read','keep',0),(12,'删除项',0,9,'/deleted','',1,'M','0','0',NULL,'keep',1)");
                statement.execute("INSERT INTO sys_role VALUES(1,'000000','admin',1,0),(2,'tenant-a','admin',1,0),(3,'000000','operator',1,0),(4,'000000','super_admin',0,0),(5,'000000','super_admin',1,1)");
                statement.execute("INSERT INTO sys_role_menu VALUES(2,2),(3,3)");
            }
            for(int repeat=0;repeat<2;repeat++)try(var input=getClass().getResourceAsStream("/db/migration/V20261024_420__agent_workbench_navigation.sql")){
                assertNotNull(input);RunScript.execute(connection,new InputStreamReader(input,StandardCharsets.UTF_8));
            }
            try(var statement=connection.createStatement();var rows=statement.executeQuery("SELECT component FROM sys_menu WHERE parent_id=0 AND is_deleted=0 ORDER BY order_num,menu_id")){
                var components=new ArrayList<String>();while(rows.next())components.add(rows.getString(1));
                assertEquals(List.of("dashboard/workspace/index","dashboard/analytics/index","ai/workbench/index","",""),components);
            }
            try(var statement=connection.createStatement();var rows=statement.executeQuery("SELECT r.role_id FROM sys_role_menu r JOIN sys_menu m ON m.menu_id=r.menu_id WHERE m.perms='ai:agent:invoke'")){
                assertTrue(rows.next());assertEquals(1,rows.getLong(1));assertFalse(rows.next());
            }
            try(var statement=connection.createStatement();var rows=statement.executeQuery("SELECT path,parent_id,menu_type FROM sys_menu WHERE perms='ai:agent:invoke'")){
                assertTrue(rows.next());assertEquals("/ai/workbench",rows.getString(1));assertEquals(0,rows.getLong(2));assertEquals("C",rows.getString(3));assertFalse(rows.next());
            }
        }
    }
}
