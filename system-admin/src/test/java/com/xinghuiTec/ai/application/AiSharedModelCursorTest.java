package com.xinghuiTec.ai.application;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DataSourceTransactionManager;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import org.springframework.transaction.support.TransactionTemplate;

import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.*;

class AiSharedModelCursorTest {
    private JdbcTemplate jdbc;
    private TransactionTemplate transaction;
    private AiSharedModelCursor instanceA;
    private AiSharedModelCursor instanceB;

    @BeforeEach
    void setup() {
        var source = new DriverManagerDataSource("jdbc:h2:mem:ai_cursor_" + UUID.randomUUID()
                + ";MODE=MySQL;DB_CLOSE_DELAY=-1;LOCK_TIMEOUT=10000", "sa", "");
        jdbc = new JdbcTemplate(source);
        transaction = new TransactionTemplate(new DataSourceTransactionManager(source));
        jdbc.execute("CREATE TABLE sys_ai_application_cursor(app_version_id BIGINT PRIMARY KEY,next_index BIGINT NOT NULL)");
        jdbc.update("INSERT INTO sys_ai_application_cursor VALUES(11,0),(12,0)");
        instanceA = new AiSharedModelCursor(jdbc);
        instanceB = new AiSharedModelCursor(jdbc);
    }

    @Test
    void alternatingInstancesUseSavedOrderAndVersionsHaveIndependentCursors() {
        var all = List.of(true, true, true);
        assertEquals(0, next(instanceA, all));
        assertEquals(1, next(instanceB, all));
        assertEquals(2, next(instanceA, all));
        assertEquals(0, next(instanceB, all));
        assertEquals(0, transaction.execute(status -> instanceB.next(12, all)).orElseThrow());
        assertEquals(1, next(instanceA, all));
    }

    @Test
    void skipsUnavailableModelsAndNoAvailabilityDoesNotResetOrAdvance() {
        assertEquals(0, next(instanceA, List.of(true, false, true)));
        assertTrue(transaction.execute(status -> instanceB.next(11, List.of(false, false, false))).isEmpty());
        assertEquals(2, next(instanceB, List.of(true, false, true)));
        assertEquals(0, next(instanceA, List.of(true, true, true)));
    }

    @Test
    void concurrentInstancesDoNotLoseCursorUpdates() throws Exception {
        var pool = Executors.newFixedThreadPool(4);
        var tasks = new ArrayList<Future<Integer>>();
        try {
            for (int index = 0; index < 30; index++) {
                AiSharedModelCursor instance = index % 2 == 0 ? instanceA : instanceB;
                tasks.add(pool.submit(() -> next(instance, List.of(true, true, true))));
            }
            var counts = new int[3];
            for (Future<Integer> task : tasks) counts[task.get(10, TimeUnit.SECONDS)]++;
            assertArrayEquals(new int[] {10, 10, 10}, counts);
            assertEquals(0L, jdbc.queryForObject("SELECT next_index FROM sys_ai_application_cursor WHERE app_version_id=11", Long.class));
        } finally {
            pool.shutdownNow();
        }
    }

    @Test
    void missingCursorFailsClosedInsteadOfFallingBackToLocalOrder() {
        assertThrows(IllegalStateException.class,
                () -> transaction.execute(status -> instanceA.next(99, List.of(true))));
        assertThrows(IllegalArgumentException.class,
                () -> transaction.execute(status -> instanceA.next(11, List.of())));
    }

    private int next(AiSharedModelCursor instance, List<Boolean> eligible) {
        return transaction.execute(status -> instance.next(11, eligible)).orElseThrow();
    }
}
