package com.xinghuiTec.lowcode;

import com.xinghuiTec.exception.ServiceException;
import org.junit.jupiter.api.Test;
import java.util.List;
import java.util.Set;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import static org.junit.jupiter.api.Assertions.*;
import static com.xinghuiTec.lowcode.LowcodeSharingFixture.*;

class LowcodeSharingServiceTest {
    private static final String KEY = "submission_request_0001";
    private static void status(int expected, org.junit.jupiter.api.function.Executable action) {
        assertEquals(expected, assertThrows(LowcodeSharingService.Failure.class, action).status);
    }
    @Test void sharingIsOffByDefaultAndPublicRepliesAreIndependentWhitelists() throws Exception {
        try (var fixture = new LowcodeSharingFixture()) {
            long id = fixture.form();
            assertFalse(fixture.sharing.settings(id).enabled());
            status(410, () -> fixture.sharing.view("a".repeat(43)));
            var link = fixture.configure(id, true, "public", false);
            assertEquals(link.token(), fixture.sharing.settings(id).token());
            assertNotEquals(link.token(), fixture.jdbc.queryForObject("SELECT token_hash FROM sys_lowcode_form_share", String.class));
            assertFalse(fixture.jdbc.queryForObject("SELECT token_ciphertext FROM sys_lowcode_form_share", String.class).contains(link.token()));
            guest();
            var view = fixture.json.valueToTree(fixture.sharing.view(link.token()));
            assertEquals(Set.of("title", "schemaVersion", "accessMode", "fields"), names(view));
            assertEquals(Set.of("fieldKey", "label", "type", "required", "defaultValue", "options", "help", "maxLength", "min", "max"), names(view.path("fields").get(0)));
            var receipt = fixture.sharing.submit(link.token(), fixture.submission(KEY, "{\"name\":\"填写人\"}"));
            assertEquals(Set.of("submitted", "receipt"), names(fixture.json.valueToTree(receipt)));
            var row = fixture.jdbc.queryForMap("SELECT tenant_id,creator FROM sys_lowcode_record");
            assertEquals("000000", row.get("tenant_id")); assertEquals("0", row.get("creator"));
            assertEquals(1, fixture.jdbc.queryForObject("SELECT COUNT(*) FROM sys_lowcode_record", Integer.class));
        }
    }
    @Test void rootAndDataIdentityInjectionUnknownFieldsAndWrongTypesCannotChangeOwnership() throws Exception {
        try (var fixture = new LowcodeSharingFixture()) {
            var link = fixture.configure(fixture.form(), true, "public", false); guest();
            for (var key : List.of("tenantId", "creator", "owner", "formId", "recordId")) {
                String root = "{\"schemaVersion\":1,\"requestKey\":\"" + KEY + "\",\"data\":{\"name\":\"a\"},\"" + key + "\":\"999\"}";
                assertThrows(com.fasterxml.jackson.core.JsonProcessingException.class, () -> fixture.json.readValue(root, LowcodeSharingService.FillSubmission.class));
                var input = fixture.submission(KEY, "{\"name\":\"a\",\"" + key + "\":\"999\"}");
                assertThrows(ServiceException.class, () -> fixture.sharing.submit(link.token(), input));
            }
            for (var bad : List.of("{}", "{\"name\":5}", "{\"name\":\"" + "a".repeat(101) + "\"}")) {
                var input = fixture.submission(KEY, bad);
                assertThrows(ServiceException.class, () -> fixture.sharing.submit(link.token(), input));
            }
            assertEquals(0, fixture.jdbc.queryForObject("SELECT COUNT(*) FROM sys_lowcode_record", Integer.class));
            authenticate(3, "other_tenant");
            status(403, () -> fixture.sharing.view(link.token()));
            status(403, () -> fixture.sharing.submit(link.token(), fixture.submission(KEY, "{\"name\":\"a\"}")));
            assertEquals(0, fixture.jdbc.queryForObject("SELECT COUNT(*) FROM sys_lowcode_record", Integer.class));
        }
    }
    @Test void loginLinksRequireSameTenantIdentityWithoutAnyManagementGrant() throws Exception {
        try (var fixture = new LowcodeSharingFixture()) {
            var link = fixture.configure(fixture.form(), true, "login", false); guest();
            status(401, () -> fixture.sharing.view(link.token()));
            status(401, () -> fixture.sharing.submit(link.token(), fixture.submission(KEY, "{\"name\":\"a\"}")));
            authenticate(9, "000000");
            assertNotNull(fixture.sharing.view(link.token()));
            fixture.sharing.submit(link.token(), fixture.submission(KEY, "{\"name\":\"a\"}"));
            assertEquals("9", fixture.jdbc.queryForObject("SELECT creator FROM sys_lowcode_record", String.class));
        }
    }
    @Test void revokeRotateModeChangeAndUnpublishPermanentlyInvalidateOldTokens() throws Exception {
        try (var fixture = new LowcodeSharingFixture()) {
            long id = fixture.form();
            var original = fixture.configure(id, true, "public", false);
            fixture.configure(id, false, "public", false); guest();
            status(410, () -> fixture.sharing.submit(original.token(), fixture.submission(KEY, "{\"name\":\"a\"}")));
            var reopened = fixture.configure(id, true, "public", false);
            assertNotEquals(original.token(), reopened.token()); guest();
            status(410, () -> fixture.sharing.view(original.token()));
            var rotated = fixture.configure(id, true, "public", true); guest();
            status(410, () -> fixture.sharing.submit(reopened.token(), fixture.submission(KEY, "{\"name\":\"a\"}")));
            var loginOnly = fixture.configure(id, true, "login", false); guest();
            status(410, () -> fixture.sharing.submit(rotated.token(), fixture.submission(KEY, "{\"name\":\"a\"}")));
            status(401, () -> fixture.sharing.submit(loginOnly.token(), fixture.submission(KEY, "{\"name\":\"a\"}")));
            manager(); var form = fixture.forms.form(id);
            fixture.forms.update(id, new LowcodeService.Draft(String.valueOf(form.get("name")), String.valueOf(form.get("formKey")), "disabled", ((Number) form.get("revision")).intValue(), fixture.schema()));
            assertFalse(fixture.sharing.settings(id).enabled());
            fixture.forms.publish(id, ((Number) fixture.forms.form(id).get("revision")).intValue());
            var fresh = fixture.configure(id, true, "public", false); guest();
            assertNotEquals(loginOnly.token(), fresh.token());
            status(410, () -> fixture.sharing.submit(loginOnly.token(), fixture.submission(KEY, "{\"name\":\"a\"}")));
            assertEquals(0, fixture.jdbc.queryForObject("SELECT COUNT(*) FROM sys_lowcode_record", Integer.class));
        }
    }
    @Test void replayReturnsOnlySameReceiptAndChangedPayloadCannotCreateAnotherAnswer() throws Exception {
        try (var fixture = new LowcodeSharingFixture()) {
            var link = fixture.configure(fixture.form(), true, "public", false); guest();
            var input = fixture.submission(KEY, "{\"name\":\"a\"}");
            var first = fixture.sharing.submit(link.token(), input);
            assertEquals(first, fixture.sharing.submit(link.token(), input));
            status(409, () -> fixture.sharing.submit(link.token(), fixture.submission(KEY, "{\"name\":\"b\"}")));
            assertEquals(1, fixture.jdbc.queryForObject("SELECT COUNT(*) FROM sys_lowcode_record", Integer.class));
        }
    }
    @Test void concurrentDuplicateSubmissionsCreateOneRecord() throws Exception {
        try (var fixture = new LowcodeSharingFixture()) {
            var link = fixture.configure(fixture.form(), true, "public", false);
            var input = fixture.submission(KEY, "{\"name\":\"same\"}");
            var pool = Executors.newFixedThreadPool(2);
            try {
                var start = new CountDownLatch(1);
                var first = pool.submit(() -> { guest(); start.await(); return fixture.sharing.submit(link.token(), input); });
                var second = pool.submit(() -> { guest(); start.await(); return fixture.sharing.submit(link.token(), input); });
                start.countDown();
                assertEquals(first.get(15, TimeUnit.SECONDS), second.get(15, TimeUnit.SECONDS));
                assertEquals(1, fixture.jdbc.queryForObject("SELECT COUNT(*) FROM sys_lowcode_record", Integer.class));
            } finally { pool.shutdownNow(); }
        }
    }
    @Test void revocationAfterTokenLookupButBeforeWriteStillRejectsSubmission() throws Exception {
        try (var fixture = new LowcodeSharingFixture()) {
            long id = fixture.form(); var link = fixture.configure(id, true, "public", false);
            var lookedUp = new CountDownLatch(1); var resume = new CountDownLatch(1);
            fixture.jdbc.afterTokenLookup = () -> { lookedUp.countDown(); await(resume); };
            var pool = Executors.newSingleThreadExecutor();
            try {
                var posting = pool.submit(() -> { guest(); return assertThrows(LowcodeSharingService.Failure.class,
                        () -> fixture.sharing.submit(link.token(), fixture.submission(KEY, "{\"name\":\"late\"}"))).status; });
                assertTrue(lookedUp.await(10, TimeUnit.SECONDS));
                fixture.configure(id, false, "public", false);
                resume.countDown();
                assertEquals(410, posting.get(15, TimeUnit.SECONDS));
                assertEquals(0, fixture.jdbc.queryForObject("SELECT COUNT(*) FROM sys_lowcode_record", Integer.class));
            } finally { resume.countDown(); pool.shutdownNow(); }
        }
    }
    @Test void publishedVersionChangePreservesOriginalAnswersAndRejectsStalePage() throws Exception {
        try (var fixture = new LowcodeSharingFixture()) {
            long id = fixture.form(); var link = fixture.configure(id, true, "public", false); guest();
            fixture.sharing.submit(link.token(), fixture.submission(KEY, "{\"name\":\"original\"}"));
            long recordId = fixture.jdbc.queryForObject("SELECT id FROM sys_lowcode_record", Long.class);
            manager(); var form = fixture.forms.form(id);
            var next = new LowcodeSchema.Schema(List.of(fixture.text("name", true), fixture.text("extra", false)));
            fixture.forms.update(id, new LowcodeService.Draft(String.valueOf(form.get("name")), String.valueOf(form.get("formKey")), "enabled", ((Number) form.get("revision")).intValue(), next));
            fixture.forms.publish(id, ((Number) fixture.forms.form(id).get("revision")).intValue());
            guest(); status(409, () -> fixture.sharing.submit(link.token(), fixture.submission("stale_request_0001", "{\"name\":\"stale\"}")));
            assertEquals(2, fixture.sharing.view(link.token()).schemaVersion());
            manager(); var old = fixture.forms.record(id, recordId);
            assertEquals(1, old.get("schemaVersion"));
            assertEquals("original", ((com.fasterxml.jackson.databind.JsonNode) old.get("data")).path("name").asText());
            fixture.forms.updateRecord(id, recordId, new LowcodeService.Submission(1, 0, fixture.json.readTree("{\"name\":\"edited\"}")));
            assertEquals(1, fixture.forms.record(id, recordId).get("schemaVersion"));
        }
    }
    private static Set<String> names(com.fasterxml.jackson.databind.JsonNode json) {
        var names = new java.util.HashSet<String>(); json.fieldNames().forEachRemaining(names::add); return names;
    }
    private static void await(CountDownLatch latch) {
        try { if (!latch.await(15, TimeUnit.SECONDS)) throw new AssertionError("Concurrent operation did not resume"); }
        catch (InterruptedException interrupted) { Thread.currentThread().interrupt(); throw new AssertionError(interrupted); }
    }
}
