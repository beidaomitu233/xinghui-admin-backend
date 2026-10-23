package com.xinghuiTec.lowcode;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.NullNode;
import com.xinghuiTec.config.SecretCipher;
import com.xinghuiTec.domain.entity.LoginUser;
import com.xinghuiTec.domain.entity.SysUser;
import org.springframework.context.annotation.AnnotationConfigApplicationContext;
import org.springframework.context.annotation.Configuration;
import org.springframework.core.io.ClassPathResource;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DataSourceTransactionManager;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import org.springframework.jdbc.datasource.init.ScriptUtils;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.annotation.EnableTransactionManagement;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.UUID;

class LowcodeSharingFixture implements AutoCloseable {
    final ObjectMapper json = new ObjectMapper().disable(com.fasterxml.jackson.databind.DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES);
    final ObservedJdbc jdbc;
    final AnnotationConfigApplicationContext context;
    final LowcodeService forms;
    final LowcodeSharingService sharing;
    final LowcodeSchema schemas;
    private final boolean h2;

    @Configuration @EnableTransactionManagement static class Transactions {}
    static class ObservedJdbc extends JdbcTemplate {
        volatile Runnable afterTokenLookup;
        volatile Runnable afterShareLock;
        ObservedJdbc(DriverManagerDataSource source) { super(source); }
        @Override public List<Map<String, Object>> queryForList(String sql, Object... arguments) {
            var result = super.queryForList(sql, arguments);
            if (sql.startsWith("SELECT form_id FROM sys_lowcode_form_share") && afterTokenLookup != null) afterTokenLookup.run();
            if (sql.startsWith("SELECT * FROM sys_lowcode_form_share") && sql.endsWith("FOR UPDATE") && afterShareLock != null) afterShareLock.run();
            return result;
        }
    }

    LowcodeSharingFixture() throws Exception {
        this(new DriverManagerDataSource("jdbc:h2:mem:sharing_" + UUID.randomUUID()
                + ";MODE=MySQL;DATABASE_TO_LOWER=TRUE;DB_CLOSE_DELAY=-1;LOCK_TIMEOUT=10000", "sa", ""), true);
    }
    LowcodeSharingFixture(DriverManagerDataSource source, boolean h2) throws Exception {
        this.h2 = h2;
        jdbc = new ObservedJdbc(source);
        if (h2) {
        jdbc.execute("CREATE TABLE sys_menu(menu_id BIGINT AUTO_INCREMENT PRIMARY KEY,menu_name VARCHAR(100),parent_id BIGINT,order_num INT,path VARCHAR(100),component VARCHAR(100),is_frame INT,menu_type CHAR(1),visible CHAR(1),status CHAR(1),perms VARCHAR(100),icon VARCHAR(100),is_deleted INT)");
        jdbc.execute("CREATE TABLE sys_role(role_id BIGINT PRIMARY KEY,tenant_id VARCHAR(20),role_key VARCHAR(40),status INT,is_deleted INT)");
        jdbc.execute("CREATE TABLE sys_role_menu(role_id BIGINT,menu_id BIGINT,PRIMARY KEY(role_id,menu_id))");
        try (var connection = source.getConnection()) {
            ScriptUtils.executeSqlScript(connection, new ClassPathResource("db/migration/V20261012_160__lowcode_forms.sql"));
            ScriptUtils.executeSqlScript(connection, new ClassPathResource("db/migration/V20261023_320__lowcode_form_sharing.sql"));
        }
        }
        schemas = new LowcodeSchema(json);
        context = new AnnotationConfigApplicationContext();
        context.registerBean(JdbcTemplate.class, () -> jdbc);
        context.registerBean(LowcodeSchema.class, () -> schemas);
        context.registerBean(LowcodeIdentity.class, () -> new LowcodeIdentity(true));
        context.registerBean(SecretCipher.class, () -> new SecretCipher(() -> System.getenv("CONFIG_MASTER_KEY") == null ? "AAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAA=" : System.getenv("CONFIG_MASTER_KEY")));
        context.registerBean(PlatformTransactionManager.class, () -> new DataSourceTransactionManager(source));
        context.register(Transactions.class, LowcodeService.class, LowcodeSharingService.class);
        context.refresh();
        forms = context.getBean(LowcodeService.class);
        sharing = context.getBean(LowcodeSharingService.class);
        manager();
    }
    static void authenticate(long userId, String tenant) {
        var user = new SysUser(); user.setUserId(userId); user.setTenantId(tenant); user.setStatus(1);
        var login = new LoginUser(user, new ArrayList<>()); login.setTenantId(tenant);
        SecurityContextHolder.getContext().setAuthentication(new UsernamePasswordAuthenticationToken(login, null, login.getAuthorities()));
    }
    static void manager() { authenticate(1, "000000"); }
    static void guest() { SecurityContextHolder.clearContext(); }
    LowcodeSchema.Field text(String key, boolean required) {
        return new LowcodeSchema.Field(key, key, "text", required, NullNode.getInstance(), List.of(), "", 100, null, null, true, true);
    }
    LowcodeSchema.Schema schema() { return new LowcodeSchema.Schema(List.of(text("name", true))); }
    long form() { return form(schema()); }
    long form(LowcodeSchema.Schema schema) {
        manager();
        var id = Long.parseLong(forms.create(new LowcodeService.Draft("填写测试", "form_" + UUID.randomUUID().toString().replace("-", ""), "draft", 0, schema)));
        forms.publish(id, 0); return id;
    }
    LowcodeSharingService.ShareView configure(long id, boolean enabled, String mode, boolean rotate) {
        manager(); var current = sharing.settings(id);
        return sharing.configure(id, new LowcodeSharingService.ShareChange(enabled, mode, current.revision(), rotate));
    }
    LowcodeSharingService.FillSubmission submission(String key, String value) throws Exception {
        return new LowcodeSharingService.FillSubmission(1, key, json.readTree(value));
    }
    @Override public void close() { guest(); context.close(); if (h2) jdbc.execute("SHUTDOWN"); }
}

