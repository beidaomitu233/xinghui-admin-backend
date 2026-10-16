package com.xinghuiTec.ai.application;

import com.xinghuiTec.ai.contract.AiTypes.ErrorCode;
import com.xinghuiTec.ai.contract.AiTypes.Usage;
import com.xinghuiTec.ai.contract.AiRuntimeException;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DataSourceTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

import java.sql.Timestamp;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.*;

class AiRequestLifecycleTest {
    private final AiRequestStore.Owner owner = new AiRequestStore.Owner("101", "platform");
    private AiRequestStore store;
    private AiRequestLifecycle lifecycle;
    private JdbcTemplate jdbc;
    private TestEvents events;
    private long requestId;
    private TransactionTemplate transaction;

    @BeforeEach
    void setup() throws Exception {
        var source = AiApplicationTestDatabase.create();
        jdbc = new JdbcTemplate(source);
        var manager = new DataSourceTransactionManager(source);
        transaction = new TransactionTemplate(manager);
        store = new AiRequestStore(jdbc);
        events = new TestEvents();
        lifecycle = new AiRequestLifecycle(store, events, manager);
        jdbc.update("INSERT INTO sys_ai_application(app_id,name,purpose,owner_id,entry_key,draft_json)"
                + " VALUES(1,'summary','fixture',101,'lowcode.record.summary/1','{}')");
        Instant now = Instant.now();
        var admission = new AiRequestStore.Admission(1, null, 1, AiRequestStore.Kind.TEST, owner,
                "lowcode.record", "1:7", UUID.randomUUID().toString(), "a".repeat(64), now.plusSeconds(30));
        requestId = Long.parseLong(new TransactionTemplate(manager).execute(status -> store.accept(admission, now))
                .request().requestId());
        assertTrue(store.start(requestId, now));
    }

    @Test
    void cancellationFencesDelayedDeltaAfterItsOutputMarkerAlreadyCommitted() throws Exception {
        events.deltaEntered = new CountDownLatch(1);
        events.releaseDelta = new CountDownLatch(1);
        var cancelStarted = new CountDownLatch(1);
        var pool = Executors.newFixedThreadPool(2);
        try {
            var delta = pool.submit(() -> {
                try { return lifecycle.delta(requestId, owner, "sensitive fragment"); }
                catch (AiRuntimeException fenced) { assertEquals(ErrorCode.CANCELLED, fenced.code()); return false; }
            });
            assertTrue(events.deltaEntered.await(5, TimeUnit.SECONDS));
            var cancel = pool.submit(() -> {
                cancelStarted.countDown();
                return lifecycle.finish(requestId, owner, AiRequestStatus.CANCELLED, ErrorCode.CANCELLED, null);
            });
            assertTrue(cancelStarted.await(5, TimeUnit.SECONDS));
            assertTrue(cancel.get(5, TimeUnit.SECONDS));
            events.releaseDelta.countDown();
            assertFalse(delta.get(5, TimeUnit.SECONDS));
            assertEquals(List.of(AiRuntimeEvents.Name.CANCELLED),
                    events.values.stream().map(AiRuntimeEvents.Event::name).toList());
            assertTrue(store.owned(requestId, owner).outputStarted());
            assertFalse(lifecycle.delta(requestId, owner, "must not follow terminal"));
            assertFalse(lifecycle.finish(requestId, owner, AiRequestStatus.SUCCEEDED, null, null));
            assertEquals(AiRequestStatus.CANCELLED, store.owned(requestId, owner).status());
        } finally {
            events.releaseDelta.countDown();
            pool.shutdownNow();
        }
    }

    @Test
    void outerRollbackPublishesNeitherTerminalUsageNorUncommittedDelta() {
        transaction.executeWithoutResult(status -> {
            assertTrue(lifecycle.delta(requestId, owner, "must not escape rollback"));
            assertTrue(lifecycle.finish(requestId, owner, AiRequestStatus.SUCCEEDED, null, new Usage(1L, 1L, 2L)));
            assertTrue(events.values.isEmpty());
            status.setRollbackOnly();
        });
        assertTrue(events.values.isEmpty());
        assertEquals(AiRequestStatus.RUNNING, store.owned(requestId, owner).status());
        assertFalse(store.owned(requestId, owner).outputStarted());
    }

    @Test
    void terminalAndUsageOnlyAppearAfterRealOuterCommit() {
        transaction.executeWithoutResult(status -> {
            assertTrue(lifecycle.finish(requestId, owner, AiRequestStatus.SUCCEEDED, null, new Usage(1L, 2L, 3L)));
            assertTrue(events.values.isEmpty());
        });
        assertEquals(AiRequestStatus.SUCCEEDED, store.owned(requestId, owner).status());
        assertEquals(List.of(AiRuntimeEvents.Name.USAGE, AiRuntimeEvents.Name.COMPLETED),
                events.values.stream().map(AiRuntimeEvents.Event::name).toList());
    }

    @Test
    void missingEphemeralBufferCannotRollBackDurableTerminalResult() {
        events.fail = true;
        assertTrue(lifecycle.finish(requestId, owner, AiRequestStatus.SUCCEEDED, null, new Usage(null, 2L, null)));
        var result = store.owned(requestId, owner);
        assertEquals(AiRequestStatus.SUCCEEDED, result.status());
        assertNull(result.inputTokens());
        assertEquals(2L, result.outputTokens());
        assertFalse(result.usageKnown());
        assertTrue(events.values.isEmpty());
    }

    @Test
    void lateSuccessIsIncompleteAfterOutputAndNeverEmitsCompleted() {
        assertTrue(lifecycle.delta(requestId, owner, "partial"));
        jdbc.update("UPDATE sys_ai_request SET deadline=? WHERE request_id=?",
                Timestamp.from(Instant.now().minusSeconds(1)), requestId);
        assertTrue(lifecycle.finish(requestId, owner, AiRequestStatus.SUCCEEDED, null, null));
        assertEquals(AiRequestStatus.INCOMPLETE, store.owned(requestId, owner).status());
        assertEquals(ErrorCode.DEADLINE_EXCEEDED.name(), store.owned(requestId, owner).errorCode());
        assertFalse(events.values.stream().anyMatch(event -> event.name() == AiRuntimeEvents.Name.COMPLETED));
    }

    private static final class TestEvents implements AiRuntimeEvents {
        final List<Event> values = new ArrayList<>();
        CountDownLatch deltaEntered;
        CountDownLatch releaseDelta;
        boolean fail;
        @Override public Event append(Event event) {
            if (fail) throw new IllegalStateException("test buffer unavailable");
            if (event.name() == Name.DELTA && deltaEntered != null) {
                deltaEntered.countDown();
                try {
                    if (!releaseDelta.await(5, TimeUnit.SECONDS)) throw new AssertionError("test gate timeout");
                } catch (InterruptedException interrupted) {
                    Thread.currentThread().interrupt();
                    throw new AssertionError("test interrupted");
                }
            }
            synchronized (values) {
                if (values.stream().anyMatch(value -> value.name() == Name.CANCELLED
                        || value.name() == Name.COMPLETED || value.name() == Name.FAILED)) {
                    throw new AiRuntimeException(ErrorCode.CANCELLED);
                }
                Event result = event.sequenced(values.size() + 1L);
                values.add(result);
                return result;
            }
        }
        @Override public List<Event> after(String id, long sequence) {
            return values.stream().filter(event -> event.requestId().equals(id) && event.sequence() > sequence).toList();
        }
    }
}
