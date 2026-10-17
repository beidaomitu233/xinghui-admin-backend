package com.xinghuiTec.ai.scene;

import com.xinghuiTec.mapper.SysMenuMapper;
import org.apache.ibatis.builder.xml.XMLMapperBuilder;
import org.apache.ibatis.mapping.Environment;
import org.apache.ibatis.session.*;
import org.apache.ibatis.transaction.jdbc.JdbcTransactionFactory;
import org.junit.jupiter.api.*;
import org.springframework.core.io.ClassPathResource;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import org.springframework.jdbc.datasource.init.ScriptUtils;
import org.springframework.core.io.ByteArrayResource;
import java.nio.charset.StandardCharsets;
import java.util.*;
import static org.junit.jupiter.api.Assertions.*;

/** Executes actual 180/225 menu SQL and V227 against H2, then queries the actual MyBatis permission mapper. */
class SceneLogPermissionMigrationTest {
    private DriverManagerDataSource source;private JdbcTemplate jdbc;private SqlSessionFactory sessions;
    private static final long OLD_PAGE=202610160180000003L,LIST_ACTION=202610160227000001L;
    @BeforeEach void setup()throws Exception{
        source=new DriverManagerDataSource("jdbc:h2:mem:scene_log_permissions_"+UUID.randomUUID()+";MODE=MySQL;DATABASE_TO_LOWER=TRUE;DB_CLOSE_DELAY=-1","sa","");jdbc=new JdbcTemplate(source);
        jdbc.execute("CREATE TABLE sys_menu(menu_id BIGINT AUTO_INCREMENT PRIMARY KEY,menu_name VARCHAR(100),parent_id BIGINT,order_num INT,path VARCHAR(200),component VARCHAR(200),is_frame INT,menu_type CHAR(1),visible CHAR(1),status CHAR(1),perms VARCHAR(200),icon VARCHAR(100),is_deleted INT,create_time TIMESTAMP DEFAULT CURRENT_TIMESTAMP,update_time TIMESTAMP DEFAULT CURRENT_TIMESTAMP)");
        jdbc.execute("CREATE TABLE sys_role(role_id BIGINT PRIMARY KEY,tenant_id VARCHAR(64),role_key VARCHAR(64),status INT,is_deleted INT)");
        jdbc.execute("CREATE TABLE sys_role_menu(role_id BIGINT,menu_id BIGINT,PRIMARY KEY(role_id,menu_id))");
        jdbc.execute("CREATE TABLE sys_user_role(user_id BIGINT,role_id BIGINT)");
        jdbc.update("INSERT INTO sys_menu(menu_id,menu_name,status,is_deleted) VALUES(106,'config','0',0),(107,'template','0',0)");
        jdbc.update("INSERT INTO sys_role VALUES(1,'000000','admin',1,0),(2,'000000','super_admin',1,0),(3,'000000','legacy_log_reader',1,0),(4,'000000','config_only',1,0),(5,'000000','scene_only',1,0),(6,'000000','disabled_reader',0,0),(7,'000000','deleted_reader',1,1),(8,'000000','future_config',1,0)");
        String v180=read("V20261016_180__ai_config_v04.sql");script(v180.substring(v180.indexOf("INSERT INTO sys_menu(menu_id")));
        for(long role:List.of(3L,6L,7L))jdbc.update("INSERT INTO sys_role_menu VALUES(?,?)",role,OLD_PAGE);
        jdbc.update("INSERT INTO sys_role_menu VALUES(4,202610160180000011),(8,202610160180000011)");
        jdbc.update("INSERT INTO sys_user_role VALUES(80,8)");
        var configuration=new Configuration(new Environment("scene-permission-migration",new JdbcTransactionFactory(),source));
        try(var xml=new ClassPathResource("mapper/SysMenuMapper.xml").getInputStream()){new XMLMapperBuilder(xml,configuration,"mapper/SysMenuMapper.xml",configuration.getSqlFragments()).parse();}
        sessions=new SqlSessionFactoryBuilder().build(configuration);
    }
    private String read(String name)throws Exception{try(var in=new ClassPathResource("db/migration/"+name).getInputStream()){return new String(in.readAllBytes(),StandardCharsets.UTF_8);}}
    private void script(String sql)throws Exception{try(var c=source.getConnection()){ScriptUtils.executeSqlScript(c,new ByteArrayResource(sql.getBytes(StandardCharsets.UTF_8)));}}
    private void apply225()throws Exception{String sql=read("V20261016_225__direct_scene_runtime.sql");script(sql.substring(sql.indexOf("-- Two daily entries")));jdbc.update("INSERT INTO sys_role_menu VALUES(5,202610160225000001)");}
    private void apply227()throws Exception{script(read("V20261016_227__restore_ai_request_list_action.sql"));}
    private Set<String> permissions(long role){try(var session=sessions.openSession()){return new HashSet<>(session.getMapper(SysMenuMapper.class).selectPermsByRoleIds(List.of(role)));}}
    private boolean monitorRoute(){try(var session=sessions.openSession()){return session.getMapper(SysMenuMapper.class).selectMenuByUserId(80L).stream().anyMatch(m->m.getMenuId()==OLD_PAGE);}}
    @Test void freshChainAndUpgradeRestoreOnlyActualFormerLogOwners()throws Exception{
        assertTrue(permissions(1).contains("ai:request:list"));assertTrue(permissions(3).contains("ai:request:list"));
        apply225();assertFalse(permissions(1).contains("ai:request:list"));assertFalse(permissions(3).contains("ai:request:list"));
        apply227();
        for(long role:List.of(1L,2L,3L)){assertTrue(permissions(role).contains("ai:request:list"));assertTrue(permissions(role).contains("ai:monitor:enter"));}
        for(long role:List.of(4L,5L,6L,7L,8L))assertFalse(permissions(role).contains("ai:request:list"));
        assertEquals("F",jdbc.queryForObject("SELECT menu_type FROM sys_menu WHERE menu_id=?",String.class,LIST_ACTION));
        assertEquals(OLD_PAGE,jdbc.queryForObject("SELECT parent_id FROM sys_menu WHERE menu_id=?",Long.class,LIST_ACTION));
        assertEquals(3,jdbc.queryForObject("SELECT COUNT(*) FROM sys_role_menu WHERE menu_id=?",Integer.class,LIST_ACTION));
    }
    @Test void derivedMonitorNavigationNeverGrantsLogReadingAndExplicitActionRevocationTakesEffect()throws Exception{
        apply225();apply227();
        assertTrue(permissions(8).contains("ai:monitor:enter"));assertFalse(permissions(8).contains("ai:request:list"));assertTrue(monitorRoute());
        jdbc.update("INSERT INTO sys_role_menu VALUES(8,?)",LIST_ACTION);assertTrue(permissions(8).contains("ai:request:list"));
        jdbc.update("DELETE FROM sys_role_menu WHERE role_id=8 AND menu_id=?",LIST_ACTION);assertFalse(permissions(8).contains("ai:request:list"));assertTrue(monitorRoute());
        jdbc.update("DELETE FROM sys_role_menu WHERE role_id=8");assertFalse(permissions(8).contains("ai:monitor:enter"));assertFalse(monitorRoute());
    }
    @Test void inactiveFormerPageCannotBecomeANewDataAccessGrantSource()throws Exception{
        apply225();jdbc.update("UPDATE sys_menu SET status='1' WHERE menu_id=?",OLD_PAGE);apply227();
        assertEquals(0,jdbc.queryForObject("SELECT COUNT(*) FROM sys_role_menu WHERE menu_id=?",Integer.class,LIST_ACTION));
        assertFalse(permissions(1).contains("ai:request:list"));assertFalse(permissions(3).contains("ai:request:list"));
    }
}
