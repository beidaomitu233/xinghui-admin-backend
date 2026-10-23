package com.xinghuiTec.notification;

import com.xinghuiTec.domain.entity.LoginUser;
import com.xinghuiTec.domain.entity.SysUser;
import com.xinghuiTec.exception.ServiceException;
import org.flywaydb.core.Flyway;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DataSourceTransactionManager;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.context.SecurityContextHolder;
import java.util.List;
import static org.junit.jupiter.api.Assertions.*;

/** Opt-in against two explicitly allocated local schemas; never cleans an existing schema. */
class NotificationEventIsolatedDbTest {
    @Test
    @EnabledIfEnvironmentVariable(named="USE05_ISOLATED_DB", matches="true")
    void migratesFreshOrLegacyDatabaseAndPreservesDeliveryAndOwnerIsolation() {
        String url = System.getenv("DB_URL");
        boolean upgrade = "upgrade".equals(System.getenv("USE05_MIGRATION_MODE"));
        String name = upgrade ? "use05_upgrade_20261023" : "use05_20261023";
        if (url == null || !url.startsWith("jdbc:mysql://127.0.0.1:13323/" + name + "?"))
            throw new IllegalStateException("Only allocated USE05 local QA schema is allowed");
        var source = new DriverManagerDataSource(url, System.getenv("DB_USERNAME"), System.getenv("DB_PASSWORD"));
        var jdbc = new JdbcTemplate(source);
        assertEquals(0, jdbc.queryForObject("SELECT COUNT(*) FROM information_schema.tables WHERE table_schema=DATABASE()", Integer.class), "QA schema must be empty; do not reset an upgrade to rerun this test");
        if (upgrade) {
            flyway(source, "20261023.310").migrate();
            assertEquals(41, jdbc.queryForObject("SELECT COUNT(*) FROM flyway_schema_history WHERE success=1", Integer.class));
            jdbc.update("INSERT INTO sys_notification_template(template_id,event_code,template_name,title_template,body_template,allowed_variables,enabled) VALUES (9001,'use05.enabled','Enabled','Title','Body','',1),(9002,'use05.disabled','Disabled','Title','Body','',0)");
            oldRequest(jdbc, 9101, 9001L, "PENDING", null);
            oldRequest(jdbc, 9102, 9002L, "PENDING", null);
            oldRequest(jdbc, 9103, null, "PENDING", null);
            oldRequest(jdbc, 9104, 9002L, "PROCESSING", null);
            oldRequest(jdbc, 9105, 9002L, "FAILED", "DELIVERY_ERROR");
            oldRequest(jdbc, 9106, 9002L, "DELIVERED", null);
            jdbc.update("INSERT INTO sys_notification_inbox(source_request_id,tenant_id,recipient_user_id,event_code,event_key,title,body,target,read_time) VALUES (9106,'000000',80001,'use05.legacy','legacy-9106','Historical title','Historical body','/system/user','2026-10-20 10:00:00')");
        }
        flyway(source, null).migrate();
        flyway(source, null).validate();
        assertEquals(0, flyway(source, null).info().pending().length);
        assertEquals(1, jdbc.queryForObject("SELECT COUNT(*) FROM flyway_schema_history WHERE version='20261023.330' AND success=1", Integer.class));
        assertEquals(0, jdbc.queryForObject("SELECT COUNT(*) FROM information_schema.tables WHERE table_schema=DATABASE() AND table_name IN ('sys_news','sys_notification_template')", Integer.class));
        assertEquals(0, jdbc.queryForObject("SELECT COUNT(*) FROM information_schema.columns WHERE table_schema=DATABASE() AND table_name='sys_notification_event_request' AND column_name='template_id'", Integer.class));
        assertEquals(0, jdbc.queryForObject("SELECT COUNT(*) FROM sys_menu WHERE perms LIKE 'system:news:%' OR perms LIKE 'system:notificationTemplate:%'", Integer.class));
        assertTrue(jdbc.queryForObject("SELECT COUNT(*) FROM sys_menu WHERE component='system/notice/index'", Integer.class)>0);
        if (upgrade) {
            assertEquals(6, jdbc.queryForObject("SELECT COUNT(*) FROM sys_notification_event_request", Integer.class));
            assertEquals(4, jdbc.queryForObject("SELECT COUNT(*) FROM sys_notification_event_request WHERE status='FAILED' AND failure_code='TEMPLATE_RETIRED' AND claim_token IS NULL AND lease_until IS NULL AND next_attempt_at IS NULL", Integer.class));
            assertEquals("DELIVERED", jdbc.queryForObject("SELECT status FROM sys_notification_event_request WHERE request_id=9106", String.class));
            assertEquals("Historical body", jdbc.queryForObject("SELECT body FROM sys_notification_inbox WHERE source_request_id=9106", String.class));
            assertNotNull(jdbc.queryForObject("SELECT read_time FROM sys_notification_inbox WHERE source_request_id=9106", java.sql.Timestamp.class));
        }
        jdbc.update("INSERT INTO sys_user(user_id,tenant_id,username,password,status) VALUES (80001,'000000','use05-owner','not-a-login-password',1),(80002,'000000','use05-other','not-a-login-password',1),(80003,'000001','use05-tenant','not-a-login-password',1),(80004,'000000','use05-disabled','not-a-login-password',0)");
        var rules = new NotificationContentRules("");
        var publisher = new NotificationEventService(jdbc, rules);
        var worker = new NotificationDeliveryService(jdbc, new DataSourceTransactionManager(source), false, rules);
        if (upgrade) {
            assertTrue(worker.deliverOne(9101));
            for (long id : List.of(9102L,9103L,9104L,9105L)) {
                assertFalse(worker.deliverOne(id)); assertFalse(worker.requeueTransientFailure(id));
            }
        }
        long a = publisher.submit(new NotificationEventPublisher.Event("000000",80001,"use05.created","one","Created","Snapshot body","/system/user")).requestId();
        long dup = publisher.submit(new NotificationEventPublisher.Event("000000",80001,"use05.created","one","Changed","Changed body",null)).requestId();
        long b = publisher.submit(new NotificationEventPublisher.Event("000000",80001,"use05.created","two","Second","Second body",null)).requestId();
        worker.deliverOne(a); worker.deliverOne(dup); worker.deliverOne(b);
        assertEquals(2, jdbc.queryForObject("SELECT COUNT(*) FROM sys_notification_inbox WHERE event_code='use05.created'", Integer.class));
        assertEquals("Created", jdbc.queryForObject("SELECT title FROM sys_notification_inbox WHERE event_code='use05.created' AND event_key='one'", String.class));
        for (long user : List.of(80003L,80004L,89999L)) {
            long bad = publisher.submit(new NotificationEventPublisher.Event("000000",user,"use05.invalid","user-"+user,"Title","Body",null)).requestId();
            worker.deliverOne(bad);
            assertEquals("FAILED", jdbc.queryForObject("SELECT status FROM sys_notification_event_request WHERE request_id=?", String.class,bad));
        }
        try {
            var inbox = new NotificationInboxService(jdbc,false);
            login(80001,"000000");
            long id = jdbc.queryForObject("SELECT notification_id FROM sys_notification_inbox WHERE source_request_id=?",Long.class,a);
            long second = jdbc.queryForObject("SELECT notification_id FROM sys_notification_inbox WHERE source_request_id=?",Long.class,b);
            assertEquals("Snapshot body", inbox.detail(id).body());
            assertTrue(inbox.page(1,1,false).total()>=2);
            login(80002,"000000");
            assertThrows(ServiceException.class,()->inbox.detail(id));
            assertThrows(ServiceException.class,()->inbox.markReadBatch(List.of(id,second)));
            login(80003,"000001");
            assertThrows(ServiceException.class,()->new NotificationInboxService(jdbc,true).detail(id));
            login(80001,"000000");
            inbox.markRead(id); inbox.markReadBatch(List.of(id,second));
            assertNotNull(new NotificationInboxService(jdbc,false).detail(id).readTime());
            assertNotNull(new NotificationInboxService(jdbc,false).detail(second).readTime());
        } finally { SecurityContextHolder.clearContext(); }
        System.out.println("USE05_DB_VERIFIED: " + name + " fresh/upgrade, retirement, retained history, snapshot delivery, idempotency, owner/tenant/read persistence");
    }
    private static Flyway flyway(DriverManagerDataSource source, String target) {
        var config=Flyway.configure().dataSource(source).locations("classpath:db/migration").cleanDisabled(true);
        if(target!=null)config.target(target);
        return config.load();
    }
    private static void oldRequest(JdbcTemplate jdbc,long id,Long template,String status,String failure) {
        jdbc.update("INSERT INTO sys_notification_event_request(request_id,tenant_id,recipient_user_id,event_code,event_key,template_id,title,body,target,status,failure_code,claim_token,lease_until,next_attempt_at) VALUES (?,'000000',80001,'use05.legacy',?,?,'Legacy snapshot','Legacy body','/system/user',?,?,'old-claim',CURRENT_TIMESTAMP,CURRENT_TIMESTAMP)",id,"legacy-"+id,template,status,failure);
    }
    private static void login(long id,String tenant) {
        var user=new SysUser();user.setUserId(id);user.setTenantId(tenant);
        var principal=new LoginUser(user,List.of());principal.setTenantId(tenant);
        SecurityContextHolder.getContext().setAuthentication(new UsernamePasswordAuthenticationToken(principal,null,principal.getAuthorities()));
    }
}
