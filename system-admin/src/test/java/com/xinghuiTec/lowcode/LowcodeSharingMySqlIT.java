package com.xinghuiTec.lowcode;

import org.flywaydb.core.Flyway;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import java.sql.Connection;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.CyclicBarrier;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import static org.junit.jupiter.api.Assertions.*;
import static com.xinghuiTec.lowcode.LowcodeSharingFixture.*;

/** Required MySQL 5.7/8.0 service gate; no opt-in skip and no alternative/production schemas. */
class LowcodeSharingMySqlIT {
    static DriverManagerDataSource source() throws Exception {
        String url = System.getenv("DB_URL"); assertNotNull(url, "Isolated MySQL DB_URL required");
        boolean ci = "true".equals(System.getenv("CI"));
        assertTrue(ci ? url.startsWith("jdbc:mysql://127.0.0.1:3306/xinghui_oss04_ci?")
                : url.startsWith("jdbc:mysql://127.0.0.1:13323/use04_20261023?"), "Only assigned service schema is allowed");
        var source = new DriverManagerDataSource(url, System.getenv("DB_USERNAME"), System.getenv("DB_PASSWORD"));
        try (var connection = source.getConnection()) {
            assertEquals(Connection.TRANSACTION_REPEATABLE_READ, connection.getTransactionIsolation(), "Prove native default RR, not H2/read-committed-by-default");
            assertTrue(connection.getMetaData().getDatabaseProductName().contains("MySQL"));
        }
        Flyway.configure().dataSource(source).locations("classpath:db/migration").cleanDisabled(true)
                .baselineOnMigrate(false).outOfOrder(false).load().migrate();
        return source;
    }
    @Test void sameRequestUsesCurrentLedgerAfterBothTransactionsReadTheToken() throws Exception {
        try (var fixture = new LowcodeSharingFixture(source(), false)) {
            long formId = fixture.form(); var link = fixture.configure(formId, true, "public", false);
            var barrier = new CyclicBarrier(2);
            fixture.jdbc.afterTokenLookup = () -> {
                assertEquals(Connection.TRANSACTION_READ_COMMITTED, isolation(fixture));
                await(barrier);
            };
            var input = fixture.submission("native_same_request_0001", "{\"name\":\"same\"}");
            var pool = Executors.newFixedThreadPool(2);
            try {
                var first = pool.submit(() -> { guest(); return fixture.sharing.submit(link.token(), input); });
                var second = pool.submit(() -> { guest(); return fixture.sharing.submit(link.token(), input); });
                assertEquals(first.get(20, TimeUnit.SECONDS), second.get(20, TimeUnit.SECONDS));
                assertEquals(1, fixture.jdbc.queryForObject("SELECT COUNT(*) FROM sys_lowcode_record WHERE form_id=?", Integer.class, formId));
                assertEquals(1, fixture.jdbc.queryForObject("SELECT COUNT(*) FROM sys_lowcode_submission s JOIN sys_lowcode_form_share f ON s.share_id=f.id WHERE f.form_id=?", Integer.class, formId));
            } finally { pool.shutdownNow(); }
        }
    }
    @Test void concurrentSameKeyDifferentPayloadReturns409InsteadOfGenericDuplicateError() throws Exception {
        try (var fixture = new LowcodeSharingFixture(source(), false)) {
            long formId = fixture.form(); var link = fixture.configure(formId, true, "public", false);
            var barrier = new CyclicBarrier(2); fixture.jdbc.afterTokenLookup = () -> await(barrier);
            var firstInput = fixture.submission("native_changed_request_0001", "{\"name\":\"first\"}");
            var secondInput = fixture.submission("native_changed_request_0001", "{\"name\":\"second\"}");
            var pool = Executors.newFixedThreadPool(2);
            try {
                var first = pool.submit(() -> postStatus(fixture, link.token(), firstInput));
                var second = pool.submit(() -> postStatus(fixture, link.token(), secondInput));
                var statuses = List.of(first.get(20, TimeUnit.SECONDS), second.get(20, TimeUnit.SECONDS));
                assertTrue(statuses.contains(200)); assertTrue(statuses.contains(409));
                assertEquals(1, fixture.jdbc.queryForObject("SELECT COUNT(*) FROM sys_lowcode_record WHERE form_id=?", Integer.class, formId));
            } finally { pool.shutdownNow(); }
        }
    }
    @Test void publishBetweenTokenReadAndFormLockRejectsOldVersionAndReadsCommittedNewVersion() throws Exception {
        for (int requestedVersion : List.of(1, 2)) {
            try (var fixture = new LowcodeSharingFixture(source(), false)) {
                long formId = fixture.form(); var link = fixture.configure(formId, true, "public", false);
                var read = new CountDownLatch(1); var resume = new CountDownLatch(1);
                fixture.jdbc.afterTokenLookup = () -> { read.countDown(); await(resume); };
                var pool = Executors.newSingleThreadExecutor();
                try {
                    var input = new LowcodeSharingService.FillSubmission(requestedVersion, "native_publish_race_0001", fixture.json.readTree("{\"name\":\"new\"}"));
                    var posting = pool.submit(() -> postStatus(fixture, link.token(), input));
                    assertTrue(read.await(15, TimeUnit.SECONDS));
                    manager(); var form = fixture.forms.form(formId);
                    var next = new LowcodeSchema.Schema(List.of(fixture.text("name", true), fixture.text("extra", false)));
                    fixture.forms.update(formId, new LowcodeService.Draft(String.valueOf(form.get("name")), String.valueOf(form.get("formKey")), "enabled", ((Number) form.get("revision")).intValue(), next));
                    fixture.forms.publish(formId, ((Number) fixture.forms.form(formId).get("revision")).intValue());
                    resume.countDown();
                    assertEquals(requestedVersion == 1 ? 409 : 200, posting.get(20, TimeUnit.SECONDS));
                    assertEquals(requestedVersion == 1 ? 0 : 1, fixture.jdbc.queryForObject("SELECT COUNT(*) FROM sys_lowcode_record WHERE form_id=?", Integer.class, formId));
                } finally { resume.countDown(); pool.shutdownNow(); }
            }
        }
    }
    @Test void revokeRotateLoginOnlyAndUnpublishWinBeforePreviouslyStartedPostWrites() throws Exception {
        for (String action : List.of("revoke", "rotate", "login", "unpublish")) {
            try (var fixture = new LowcodeSharingFixture(source(), false)) {
                long formId = fixture.form(); var link = fixture.configure(formId, true, "public", false);
                var read = new CountDownLatch(1); var resume = new CountDownLatch(1);
                fixture.jdbc.afterTokenLookup = () -> { read.countDown(); await(resume); };
                var pool = Executors.newSingleThreadExecutor();
                try {
                    var input = fixture.submission("native_revocation_race_0001", "{\"name\":\"late\"}");
                    var posting = pool.submit(() -> postStatus(fixture, link.token(), input));
                    assertTrue(read.await(15, TimeUnit.SECONDS));
                    if (action.equals("unpublish")) {
                        manager(); var form = fixture.forms.form(formId);
                        fixture.forms.update(formId, new LowcodeService.Draft(String.valueOf(form.get("name")), String.valueOf(form.get("formKey")), "disabled", ((Number) form.get("revision")).intValue(), fixture.schema()));
                    } else fixture.configure(formId, !action.equals("revoke"), action.equals("login") ? "login" : "public", action.equals("rotate"));
                    resume.countDown();
                    assertEquals(410, posting.get(20, TimeUnit.SECONDS));
                    assertEquals(0, fixture.jdbc.queryForObject("SELECT COUNT(*) FROM sys_lowcode_record WHERE form_id=?", Integer.class, formId));
                } finally { resume.countDown(); pool.shutdownNow(); }
            }
        }
    }
    private static int postStatus(LowcodeSharingFixture fixture, String token, LowcodeSharingService.FillSubmission input) {
        guest();
        try { fixture.sharing.submit(token, input); return 200; }
        catch (LowcodeSharingService.Failure failure) { return failure.status; }
    }
    private static int isolation(LowcodeSharingFixture fixture) {
        return fixture.jdbc.execute((org.springframework.jdbc.core.ConnectionCallback<Integer>) Connection::getTransactionIsolation);
    }
    private static void await(CountDownLatch latch) {
        try { assertTrue(latch.await(20, TimeUnit.SECONDS)); }
        catch (InterruptedException failure) { Thread.currentThread().interrupt(); throw new AssertionError(failure); }
    }
    private static void await(CyclicBarrier barrier) {
        try { barrier.await(20, TimeUnit.SECONDS); }
        catch (Exception failure) { throw new AssertionError(failure); }
    }
}
