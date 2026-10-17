package com.xinghuiTec.ai.conversation;

import com.alibaba.druid.DbType;
import com.alibaba.druid.filter.stat.StatFilter;
import com.alibaba.druid.pool.DruidDataSource;
import com.alibaba.druid.wall.WallConfig;
import com.alibaba.druid.wall.WallFilter;
import com.xinghuiTec.ai.application.AiRequestStore;
import org.junit.jupiter.api.*;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DataSourceTransactionManager;

import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Objects;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.*;

/** Real MySQL Druid Wall + stat filters and JDBC, with H2 as the isolated SQL storage backend. */
class ConversationDruidWallTest {
    private DruidDataSource source;
    private JdbcTemplate jdbc;
    private ConversationStore store;
    private final AiRequestStore.Owner owner = new AiRequestStore.Owner("101", "tenant_a");

    @BeforeEach
    void setup() throws Exception {
        source = new DruidDataSource();
        source.setUrl("jdbc:h2:mem:conversation_wall_" + UUID.randomUUID() + ";MODE=MySQL");
        source.setDriverClassName("org.h2.Driver");
        source.setUsername("sa");
        source.setPassword("");
        source.setDbType(DbType.mysql);
        source.setInitialSize(1);
        source.setMaxActive(2);
        source.setMaxWait(3000);
        source.setValidationQuery("SELECT 1");
        var wall = new WallFilter();
        wall.setDbType(DbType.mysql);
        wall.setConfig(new WallConfig());
        source.setProxyFilters(List.of(new StatFilter(), wall));
        jdbc = new JdbcTemplate(source);
        String migration;
        try (var input = getClass().getResourceAsStream("/db/migration/V20261018_240__conversation_history.sql")) {
            migration = new String(Objects.requireNonNull(input).readAllBytes(), StandardCharsets.UTF_8);
        }
        String schema = migration.substring(migration.indexOf("CREATE TABLE sys_ai_conversation ("),
                migration.indexOf("CREATE TABLE sys_ai_conversation_message"))
                .replace(" COLLATE utf8mb4_bin", "").replace(" ENGINE=InnoDB DEFAULT CHARSET=utf8mb4", "");
        jdbc.execute(schema);
        jdbc.execute("CREATE TABLE sys_ai_request(request_id BIGINT PRIMARY KEY,status VARCHAR(24))");
        store = new ConversationStore(jdbc, new AiRequestStore(jdbc), new DataSourceTransactionManager(source));
        row(owner, owner.scope(), "scene.alpha", "BUSINESS", "Weekly report");
        row(new AiRequestStore.Owner("202", "tenant_a"), owner.scope(), "scene.alpha", "BUSINESS", "Weekly other");
        row(new AiRequestStore.Owner("101", "tenant_b"), owner.scope(), "scene.beta", "TEST", "Private report");
    }

    private void row(AiRequestStore.Owner identity, String scope, String scene, String kind, String title) {
        // Deliberately reuse a scope on the other identities to test all three OWN predicates.
        jdbc.update("INSERT INTO sys_ai_conversation(owner_scope,subject_ref,tenant_ref,scene_key,scene_name,title,source_label,request_kind,business_type,business_id,last_status) VALUES(?,?,?,?,?,?,'Fixture',?,'ai.scene',?,'SUCCEEDED')",
                scope, identity.subjectRef(), identity.tenantRef(), scene, "Fixture", title, kind, scene);
    }

    @AfterEach
    void close() {
        if (source != null) source.close();
    }

    @Test
    void unfilteredAuditListAndCountPassWithAlwaysTrueProtectionStillEnabled() {
        assertThrows(org.springframework.jdbc.UncategorizedSQLException.class,
                () -> jdbc.queryForObject("SELECT COUNT(*) FROM sys_ai_conversation WHERE 1=1", Long.class));
        assertThrows(org.springframework.jdbc.UncategorizedSQLException.class,
                () -> jdbc.queryForList("SELECT * FROM sys_ai_conversation WHERE 1=1 ORDER BY conversation_id"));
        assertEquals(3, store.count(owner, true, null, null, null));
        assertEquals(3, store.page(owner, true, null, null, null, 1, 20).size());
        assertEquals(2, store.page(owner, true, null, null, "", 1, 2).size());
        assertEquals(1, store.page(owner, true, null, null, null, 2, 2).size());
    }

    @Test
    void auditFiltersAndHostileSearchRemainParameterizedThroughWall() {
        assertEquals(2, store.count(owner, true, "scene.alpha", "BUSINESS", "Weekly"));
        assertEquals(2, store.page(owner, true, "scene.alpha", "BUSINESS", "Weekly", 1, 20).size());
        assertEquals(1, store.count(owner, true, null, "TEST", null));
        assertEquals(0, store.count(owner, true, null, null, "x') OR 1=1 --"));
        assertTrue(store.page(owner, true, null, null, "x') OR 1=1 --", 1, 20).isEmpty());
    }

    @Test
    void ownListAndCountKeepSubjectAndTenantIsolation() {
        assertEquals(1, store.count(owner, false, null, null, null));
        var rows = store.page(owner, false, null, null, null, 1, 20);
        assertEquals(1, rows.size());
        assertEquals("101", rows.get(0).subject());
        assertEquals("tenant_a", rows.get(0).tenant());
        assertEquals(0, store.count(owner, false, "scene.beta", "TEST", null));
        assertTrue(store.page(owner, false, "scene.beta", "TEST", null, 1, 20).isEmpty());
    }
}
