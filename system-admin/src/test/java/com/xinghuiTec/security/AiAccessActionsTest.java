package com.xinghuiTec.security;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.core.io.ClassPathResource;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import org.springframework.jdbc.datasource.init.ScriptUtils;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.UUID;
import static org.junit.jupiter.api.Assertions.*;

/** Executes V210 and the actual navigation mapper SELECT; no broad template grants for data users. */
class AiAccessActionsTest {
    JdbcTemplate jdbc;
    @BeforeEach void setup() throws Exception {
        var source=new DriverManagerDataSource("jdbc:h2:mem:ai_access_"+UUID.randomUUID()+";MODE=MySQL;DB_CLOSE_DELAY=-1","sa","");
        jdbc=new JdbcTemplate(source);
        jdbc.execute("CREATE TABLE sys_menu(menu_id BIGINT PRIMARY KEY,menu_name VARCHAR(100),parent_id BIGINT,order_num INT,path VARCHAR(100),component VARCHAR(100),is_frame INT,menu_type VARCHAR(1),visible VARCHAR(1),status VARCHAR(1),perms VARCHAR(100),icon VARCHAR(100),is_deleted INT)");
        jdbc.execute("CREATE TABLE sys_role(role_id BIGINT PRIMARY KEY,tenant_id VARCHAR(20),role_key VARCHAR(40),status INT,is_deleted INT)");
        jdbc.execute("CREATE TABLE sys_role_menu(role_id BIGINT,menu_id BIGINT,PRIMARY KEY(role_id,menu_id))");
        jdbc.execute("CREATE TABLE sys_user_role(user_id BIGINT,role_id BIGINT)");
        jdbc.update("INSERT INTO sys_role VALUES(1,'000000','legacy',1,0),(2,'000000','data_manager',1,0),(3,'000000','admin',1,0),(4,'000001','admin',1,0),(5,'000000','legacy_prompt',1,0)");
        jdbc.update("INSERT INTO sys_user_role VALUES(11,1),(22,2),(33,3),(44,4),(55,5)");
        jdbc.update("INSERT INTO sys_menu(menu_id,parent_id,order_num,menu_type,status,perms,is_deleted,path) VALUES(202610160180000001,0,4,'M','0','',0,'/ai'),(107,202610160180000001,3,'C','0','ai:template:list',0,'/ai/templates'),(202610160180000003,202610160180000001,4,'C','0','ai:request:list',0,'/ai/requests'),(900,107,90,'F','0','ai:data:list',0,''),(901,107,91,'F','0','ai:prompt:list',0,'')");
        jdbc.update("INSERT INTO sys_role_menu VALUES(1,107),(2,900),(3,107),(3,202610160180000003),(4,202610160180000003),(5,901)");
        try(var connection=source.getConnection()) {
            ScriptUtils.executeSqlScript(connection,new ClassPathResource("db/migration/V20261016_210__ai_access_actions.sql"));
        }
    }
    List<String> permissions(long role) {
        return jdbc.query("SELECT DISTINCT m.perms FROM sys_menu m JOIN sys_role_menu rm ON rm.menu_id=m.menu_id WHERE rm.role_id=? AND m.status='0'",(rs,n)->rs.getString(1),role);
    }
    List<Long> routes(long user) throws Exception {
        String mapper;
        try(var stream=new ClassPathResource("mapper/SysMenuMapper.xml").getInputStream()) {
            mapper=new String(stream.readAllBytes(),StandardCharsets.UTF_8);
        }
        int start=mapper.indexOf('>',mapper.indexOf("<select id=\"selectMenuByUserId\""))+1;
        String sql=mapper.substring(start,mapper.indexOf("</select>",start)).replaceAll("(?s)<!--.*?-->","").replace("#{userId}","?");
        return jdbc.query(sql,(rs,n)->rs.getLong("menu_id"),user,user);
    }
    @Test void dataOnlyRoleGetsSharedRouteWithoutTemplateApiAndFutureGrantsAndRevocationWork() throws Exception {
        assertEquals(List.of(202610160180000001L,107L),routes(22));
        assertTrue(permissions(2).contains("ai:data:list"));
        assertFalse(permissions(2).contains("ai:template:list"));
        jdbc.update("DELETE FROM sys_role_menu WHERE role_id=2 AND menu_id=900");
        assertTrue(routes(22).isEmpty());
        jdbc.update("INSERT INTO sys_role_menu VALUES(2,202610160210000020)");
        assertEquals(List.of(202610160180000001L,107L),routes(22));
        assertFalse(permissions(2).contains("ai:template:list"));
    }
    @Test void formerTemplateAndPromptOwnersKeepTemplateListAndNavigation() throws Exception {
        assertTrue(permissions(1).contains("ai:template:list"));
        assertTrue(permissions(1).contains("ai:workspace:enter"));
        assertTrue(permissions(5).contains("ai:template:list"));
        assertTrue(routes(11).contains(107L)); assertTrue(routes(55).contains(107L));
        assertFalse(permissions(1).contains("ai:data:approve"));
    }
    @Test void auditIsIndependentAndDefaultsOnlyToExistingPlatformAdministrators() {
        assertTrue(permissions(3).contains("ai:request:audit"));
        for(long role:List.of(1L,2L,4L,5L)) assertFalse(permissions(role).contains("ai:request:audit"));
        assertTrue(permissions(4).contains("ai:request:list"));
        assertFalse(permissions(4).contains("ai:request:audit"));
    }
}
