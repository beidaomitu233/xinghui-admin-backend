package com.xinghuiTec.security;

import com.xinghuiTec.mapper.SysMenuMapper;
import org.apache.ibatis.builder.xml.XMLMapperBuilder;
import org.apache.ibatis.mapping.Environment;
import org.apache.ibatis.session.Configuration;
import org.apache.ibatis.session.SqlSessionFactory;
import org.apache.ibatis.session.SqlSessionFactoryBuilder;
import org.apache.ibatis.transaction.jdbc.JdbcTransactionFactory;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.core.io.ClassPathResource;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import org.springframework.jdbc.datasource.init.ScriptUtils;

import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.*;

/** Tests actual grant SQL and actual permission resolution, including later revocation. */
class AiDatabaseAccessMigrationTest {
    private JdbcTemplate jdbc;
    private SqlSessionFactory sessions;
    private static final long ACTION = 202610180242000001L;
    private static final String READ = "ai:data:database:read";

    @BeforeEach void setup() throws Exception {
        var source = new DriverManagerDataSource("jdbc:h2:mem:ai_db_grant_" + UUID.randomUUID()
                + ";MODE=MySQL;DATABASE_TO_LOWER=TRUE;DB_CLOSE_DELAY=-1", "sa", "");
        jdbc = new JdbcTemplate(source);
        jdbc.execute("CREATE TABLE sys_menu(menu_id BIGINT PRIMARY KEY,menu_name VARCHAR(100),parent_id BIGINT,order_num INT,path VARCHAR(200),component VARCHAR(200),is_frame INT,menu_type CHAR(1),visible CHAR(1),status CHAR(1),perms VARCHAR(200),icon VARCHAR(100),is_deleted INT)");
        jdbc.execute("CREATE TABLE sys_role(role_id BIGINT PRIMARY KEY,tenant_id VARCHAR(64),role_key VARCHAR(64),status INT,is_deleted INT)");
        jdbc.execute("CREATE TABLE sys_role_menu(role_id BIGINT,menu_id BIGINT,PRIMARY KEY(role_id,menu_id))");
        jdbc.execute("CREATE TABLE sys_user_role(user_id BIGINT,role_id BIGINT)");
        jdbc.update("INSERT INTO sys_role VALUES(1,'000000','admin',1,0),(2,'000000','super_admin',1,0),(3,'tenant-a','admin',1,0),(4,'000000','editor',1,0),(5,'000000','admin',0,0),(6,'000000','super_admin',1,1)");
        jdbc.update("INSERT INTO sys_menu(menu_id,status,is_deleted,menu_type,perms) VALUES(107,'0',0,'C','ai:workspace:enter'),(900,'0',0,'F','ai:scene:edit'),(901,'0',0,'F','ai:data:query')");
        jdbc.update("INSERT INTO sys_role_menu VALUES(3,900),(4,900),(4,901)");
        try (var connection = source.getConnection()) {
            ScriptUtils.executeSqlScript(connection, new ClassPathResource("db/migration/V20261018_242__ai_database_read_action.sql"));
        }
        var configuration = new Configuration(new Environment("ai-db-grant", new JdbcTransactionFactory(), source));
        try (var xml = new ClassPathResource("mapper/SysMenuMapper.xml").getInputStream()) {
            new XMLMapperBuilder(xml, configuration, "mapper/SysMenuMapper.xml", configuration.getSqlFragments()).parse();
        }
        sessions = new SqlSessionFactoryBuilder().build(configuration);
    }

    @AfterEach void close() { if (jdbc != null) jdbc.execute("SHUTDOWN"); }

    private Set<String> permissions(long role) {
        try (var session = sessions.openSession()) {
            return new HashSet<>(session.getMapper(SysMenuMapper.class).selectPermsByRoleIds(List.of(role)));
        }
    }

    @Test void onlyActivePlatformAdministratorsReceiveDatabaseCapability() {
        assertTrue(permissions(1).contains(READ));
        assertTrue(permissions(2).contains(READ));
        for (long role : List.of(3L, 4L, 5L, 6L)) assertFalse(permissions(role).contains(READ));
        assertTrue(permissions(4).contains("ai:scene:edit"));
        assertTrue(permissions(4).contains("ai:data:query"));
        assertEquals(2, jdbc.queryForObject("SELECT COUNT(*) FROM sys_role_menu WHERE menu_id=?", Integer.class, ACTION));
    }

    @Test void revokingActionOrDisablingItRemovesPermissionImmediatelyFromResolution() {
        jdbc.update("DELETE FROM sys_role_menu WHERE role_id=1 AND menu_id=?", ACTION);
        assertFalse(permissions(1).contains(READ));
        assertTrue(permissions(2).contains(READ));
        jdbc.update("UPDATE sys_menu SET status='1' WHERE menu_id=?", ACTION);
        assertFalse(permissions(2).contains(READ));
    }
}
