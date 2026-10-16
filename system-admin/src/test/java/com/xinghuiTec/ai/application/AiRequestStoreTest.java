package com.xinghuiTec.ai.application;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DataSourceTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

import java.time.Instant;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;

import static com.xinghuiTec.ai.application.AiRequestStatus.*;
import static org.junit.jupiter.api.Assertions.*;

/** Real in-memory SQL/transactions; not supplier or MySQL integration evidence. */
class AiRequestStoreTest {
    private static final Instant NOW = Instant.parse("2026-10-16T04:00:00Z");
    private static final AiRequestStore.Owner OWNER = new AiRequestStore.Owner("user:101", "tenant:7");
    private JdbcTemplate jdbc;
    private AiRequestStore store;
    private TransactionTemplate transaction;

    @BeforeEach
    void setup() throws Exception {
        var source = AiApplicationTestDatabase.create();
        jdbc = new JdbcTemplate(source);
        transaction = new TransactionTemplate(new DataSourceTransactionManager(source));
        store = new AiRequestStore(jdbc);
        jdbc.update("INSERT INTO sys_ai_application(app_id,name,purpose,owner_id,entry_key,draft_json)"
                + " VALUES (1,'summary','record summary',101,'lowcode.record.summary','{}')");
        jdbc.update("INSERT INTO sys_ai_application_version(app_version_id,app_id,version_number,draft_revision,"
                + "snapshot_json,tested_request_id,created_by) VALUES (11,1,1,1,'{}',1,101)");
    }

    @Test
    void replaysOneRequestWithoutDispatchingAgainAndConflictingInputIsRejected() {
        var first = accept(admission(OWNER, "nonce-1", "a".repeat(64)));
        var second = accept(admission(OWNER, "nonce-1", "a".repeat(64)));
        assertTrue(first.created());
        assertFalse(second.created());
        assertEquals(first.request().requestId(), second.request().requestId());
        assertEquals(1, jdbc.queryForObject("SELECT COUNT(*) FROM sys_ai_request", Integer.class));
        assertEquals("IDEMPOTENCY_CONFLICT", assertThrows(IllegalArgumentException.class,
                () -> accept(admission(OWNER, "nonce-1", "b".repeat(64)))).getMessage());
        assertEquals(QUEUED, first.request().status());
        assertNull(first.request().inputTokens());
        assertNull(first.request().outputTokens());
        assertFalse(first.request().usageKnown());
        String stored = jdbc.queryForObject("SELECT idempotency_scope FROM sys_ai_request", String.class);
        assertNotEquals("nonce-1", stored);
    }

    @Test
    void concurrentAdmissionHasExactlyOneCreator() throws Exception {
        var ready = new CountDownLatch(2);
        var release = new CountDownLatch(1);
        var pool = Executors.newFixedThreadPool(2);
        try {
            java.util.concurrent.Callable<AiRequestStore.Accepted> call = () -> {
                ready.countDown();
                assertTrue(release.await(5, TimeUnit.SECONDS));
                return accept(admission(OWNER, "same-nonce", "c".repeat(64)));
            };
            var a = pool.submit(call);
            var b = pool.submit(call);
            assertTrue(ready.await(5, TimeUnit.SECONDS));
            release.countDown();
            var first = a.get(10, TimeUnit.SECONDS);
            var second = b.get(10, TimeUnit.SECONDS);
            assertNotEquals(first.created(), second.created());
            assertEquals(first.request().requestId(), second.request().requestId());
            assertEquals(1, jdbc.queryForObject("SELECT COUNT(*) FROM sys_ai_request", Integer.class));
        } finally {
            pool.shutdownNow();
        }
    }

    @Test
    void ownershipAndIdempotencyAreIsolatedAcrossSubjectsAndTenants() {
        long id = id(accept(admission(OWNER, "same", "a".repeat(64))));
        var otherSubject = new AiRequestStore.Owner("user:102", "tenant:7");
        var otherTenant = new AiRequestStore.Owner("user:101", "tenant:8");
        assertThrows(IllegalArgumentException.class, () -> store.owned(id, otherSubject));
        assertThrows(IllegalArgumentException.class, () -> store.owned(id, otherTenant));
        assertNotEquals(id, id(accept(admission(otherSubject, "same", "a".repeat(64)))));
        assertNotEquals(id, id(accept(admission(otherTenant, "same", "a".repeat(64)))));
    }

    @Test
    void cancellationWinsOnlyOnceAndPreventsLaterStartOrCompletionOrOutput() {
        long id = id(accept(admission(OWNER, "cancel", "a".repeat(64))));
        assertTrue(store.finish(id, CANCELLED, "USER_CANCELLED", null, null, NOW.plusSeconds(1)));
        assertFalse(store.start(id, NOW.plusSeconds(2)));
        assertFalse(store.markOutputStarted(id, NOW.plusSeconds(2)));
        assertFalse(store.finish(id, SUCCEEDED, null, 3L, 2L, NOW.plusSeconds(2)));
        assertFalse(store.finish(id, FAILED, "PROVIDER_ERROR", null, null, NOW.plusSeconds(2)));
        assertEquals(CANCELLED, store.owned(id, OWNER).status());
        assertNull(store.owned(id, OWNER).inputTokens());
    }

    @Test
    void completionCancelRaceCommitsOneTerminalState() throws Exception {
        long id = id(accept(admission(OWNER, "race", "a".repeat(64))));
        assertTrue(store.start(id, NOW));
        var pool = Executors.newFixedThreadPool(2);
        var release = new CountDownLatch(1);
        try {
            var success = pool.submit(() -> {
                release.await();
                return store.finish(id, SUCCEEDED, null, 2L, 1L, NOW.plusSeconds(1));
            });
            var cancel = pool.submit(() -> {
                release.await();
                return store.finish(id, CANCELLED, "USER_CANCELLED", null, null, NOW.plusSeconds(1));
            });
            release.countDown();
            assertNotEquals(success.get(5, TimeUnit.SECONDS), cancel.get(5, TimeUnit.SECONDS));
            var result = store.owned(id, OWNER);
            assertTrue(result.status() == SUCCEEDED || result.status() == CANCELLED);
            assertEquals(result.status() == SUCCEEDED, result.usageKnown());
        } finally {
            pool.shutdownNow();
        }
    }

    @Test
    void expiredRecoveryMarksPartialOutputIncompleteWithoutTouchingLiveOrTerminalRequests() {
        long partial = id(accept(admission(OWNER, "partial", "a".repeat(64))));
        long queued = id(accept(admission(OWNER, "queued", "a".repeat(64))));
        long done = id(accept(admission(OWNER, "done", "a".repeat(64))));
        var liveAdmission = new AiRequestStore.Admission(1, 11L, null, AiRequestStore.Kind.BUSINESS,
                OWNER, "lowcode.record", "form1:record7", "live", "a".repeat(64), NOW.plusSeconds(600));
        long live = id(accept(liveAdmission));
        assertTrue(store.start(partial, NOW));
        assertTrue(store.markOutputStarted(partial, NOW.plusSeconds(1)));
        assertTrue(store.start(done, NOW));
        assertTrue(store.finish(done, SUCCEEDED, null, 0L, 1L, NOW.plusSeconds(2)));
        assertEquals(2, store.expire(NOW.plusSeconds(61)));
        assertEquals(INCOMPLETE, store.owned(partial, OWNER).status());
        assertEquals(TIMED_OUT, store.owned(queued, OWNER).status());
        assertEquals(SUCCEEDED, store.owned(done, OWNER).status());
        assertEquals(QUEUED, store.owned(live, OWNER).status());
        assertEquals(0, store.expire(NOW.plusSeconds(61)));
    }

    @Test
    void expiredRequestCannotSucceedAndUnknownUsageRemainsNull() {
        long id = id(accept(admission(OWNER, "deadline", "a".repeat(64))));
        assertTrue(store.start(id, NOW));
        assertFalse(store.finish(id, SUCCEEDED, null, 1L, 1L, NOW.plusSeconds(60)));
        assertEquals(1, store.expire(NOW.plusSeconds(60)));
        assertNull(store.owned(id, OWNER).inputTokens());
        assertFalse(store.owned(id, OWNER).usageKnown());
    }

    @Test
    void onlySafeMetadataAndTerminalStatesCanBePersisted() {
        long id = id(accept(admission(OWNER, "validation", "a".repeat(64))));
        assertThrows(IllegalArgumentException.class,
                () -> store.finish(id, RUNNING, null, null, null, NOW));
        assertThrows(IllegalArgumentException.class,
                () -> store.finish(id, SUCCEEDED, "PROVIDER_ERROR", 1L, 1L, NOW));
        assertThrows(IllegalArgumentException.class,
                () -> store.finish(id, FAILED, "Provider said secret-key", null, null, NOW));
        assertThrows(IllegalArgumentException.class,
                () -> store.finish(id, FAILED, "PROVIDER_ERROR", -1L, null, NOW));
        assertThrows(IllegalArgumentException.class, () -> new AiRequestStore.Owner("x\ny", "tenant"));
        assertEquals("Admission[redacted]", admission(OWNER, "do-not-print", "a".repeat(64)).toString());
        assertTrue(store.start(id, NOW));
        assertTrue(store.finish(id, SUCCEEDED, null, null, 3L, NOW.plusSeconds(1)));
        assertNull(store.owned(id, OWNER).inputTokens());
        assertEquals(3L, store.owned(id, OWNER).outputTokens());
        assertFalse(store.owned(id, OWNER).usageKnown());
    }

    private AiRequestStore.Admission admission(AiRequestStore.Owner owner, String nonce, String fingerprint) {
        return new AiRequestStore.Admission(1, 11L, null, AiRequestStore.Kind.BUSINESS, owner,
                "lowcode.record", "form1:record7", nonce, fingerprint, NOW.plusSeconds(60));
    }

    private AiRequestStore.Accepted accept(AiRequestStore.Admission admission) {
        return transaction.execute(status -> store.accept(admission, NOW));
    }

    private long id(AiRequestStore.Accepted accepted) {
        return Long.parseLong(accepted.request().requestId());
    }
}
