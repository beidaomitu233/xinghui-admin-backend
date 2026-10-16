package com.xinghuiTec.ai.application;

import com.xinghuiTec.ai.contract.AiProviderRuntime.*;
import com.xinghuiTec.ai.contract.AiTypes.*;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DataSourceTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

import java.sql.Timestamp;
import java.time.Instant;
import java.util.*;

import static org.junit.jupiter.api.Assertions.*;

/** V200+V220 H2 DDL/SQL checks; not a real MySQL upgrade or provider execution claim. */
class AiAttemptRecoveryTest {
    private final AiRequestStore.Owner owner = new AiRequestStore.Owner("101", "platform");
    private JdbcTemplate jdbc;
    private AiRequestStore requests;
    private AiRequestJournal journal;
    private TransactionTemplate transaction;
    private final Target target = new Target(new ModelVersionRef("m1", "cv1"), new KeyRef("k1", "kv1"),
            "SIMULATED", "neutral/model:free", "masked-fixture", new Capabilities(Set.of(OutputFormat.TEXT), true, false));

    @BeforeEach void setup() throws Exception {
        var source = AiApplicationTestDatabase.create(); jdbc = new JdbcTemplate(source);
        var manager = new DataSourceTransactionManager(source); transaction = new TransactionTemplate(manager);
        requests = new AiRequestStore(jdbc); journal = new AiRequestJournal(jdbc, requests, manager);
        jdbc.update("INSERT INTO sys_ai_application(app_id,name,purpose,owner_id,entry_key,draft_json)"
                + " VALUES(1,'fixture','recovery fixture',101,'lowcode.record.summary/1','{}')");
    }

    @Test void actualTargetTraceAndAllocationArePreservedAndCompletedAttemptCannotBeOverwritten() {
        long id = running();
        var attempt = journal.reserve(id, owner, target, 2, AiRequestJournal.SwitchReason.FIRST, 17);
        var success = new ExecutionOutcome(ExecutionStatus.SUCCEEDED, null, false, true, new Usage(null, 1L, null), "gen-neutral-001", 2);
        assertTrue(journal.complete(id, attempt.attemptId(), success));
        assertFalse(journal.complete(id, attempt.attemptId(), new ExecutionOutcome(ExecutionStatus.FAILED,
                ErrorCode.NETWORK_ERROR, false, false, null, "different-id", 1)));
        var row = jdbc.queryForMap("SELECT * FROM sys_ai_request_attempt WHERE attempt_id=?", Long.parseLong(attempt.attemptId()));
        assertEquals("SIMULATED", row.get("provider_code")); assertEquals("neutral/model:free", row.get("model_code"));
        assertEquals("kv1", row.get("key_version")); assertEquals("gen-neutral-001", row.get("provider_request_id"));
        assertEquals(17, row.get("output_token_limit")); assertNull(row.get("input_tokens"));
    }

    @Test void unsafeProviderTraceIsNotStoredAndLegacyMetadataRemainsUnknown() {
        long id = running();
        var attempt = journal.reserve(id, owner, target, 2, AiRequestJournal.SwitchReason.FIRST, 17);
        journal.complete(id, attempt.attemptId(), new ExecutionOutcome(ExecutionStatus.SUCCEEDED, null, false, true,
                null, "unsafe\nprovider-body", 1));
        assertNull(jdbc.queryForObject("SELECT provider_request_id FROM sys_ai_request_attempt WHERE attempt_id=?", String.class,
                Long.parseLong(attempt.attemptId())));
        jdbc.update("INSERT INTO sys_ai_request_attempt(request_id,attempt_number,config_version,model_ref,key_ref,masked_key_label,switch_reason,status,create_time)"
                + " VALUES(?,2,'old-config','old-model','old-key','masked','FIRST','FAILED',?)", id, Timestamp.from(Instant.now()));
        var legacy = jdbc.queryForMap("SELECT * FROM sys_ai_request_attempt WHERE request_id=? AND attempt_number=2", id);
        for (String field : List.of("provider_code", "model_code", "key_version", "provider_request_id", "output_token_limit")) assertNull(legacy.get(field));
    }

    @Test void expiryClosesUnknownAttemptsWithoutRestartOrInventingUsageAndLeavesLiveRowsAlone() {
        Instant now = Instant.now();
        long noOutput = running(), partial = running(), live = running(), cancelled = running(), done = running();
        for (long id : new long[] {noOutput, partial, live, cancelled, done}) {
            journal.reserve(id, owner, target, 2, AiRequestJournal.SwitchReason.FIRST, 17);
        }
        assertTrue(requests.markOutputStarted(partial, now));
        assertTrue(requests.finish(cancelled, AiRequestStatus.CANCELLED, "CANCELLED", null, null, now));
        String doneAttempt = jdbc.queryForObject("SELECT attempt_id FROM sys_ai_request_attempt WHERE request_id=?", Long.class, done).toString();
        journal.complete(done, doneAttempt, new ExecutionOutcome(ExecutionStatus.SUCCEEDED, null, false, true, new Usage(1L, 1L, 2L), "gen-done", 1));
        assertTrue(requests.finish(done, AiRequestStatus.SUCCEEDED, null, 1L, 1L, now));
        for (long id : new long[] {noOutput, partial, cancelled, done}) jdbc.update("UPDATE sys_ai_request SET deadline=? WHERE request_id=?", Timestamp.from(now.minusSeconds(1)), id);
        assertEquals(2, transaction.execute(status -> requests.expire(now)).intValue());
        assertEquals("TIMED_OUT", attemptStatus(noOutput)); assertEquals("INCOMPLETE", attemptStatus(partial));
        assertEquals("INCOMPLETE", attemptStatus(cancelled)); assertEquals(AiRequestStatus.CANCELLED, requests.owned(cancelled, owner).status());
        assertEquals("RUNNING", attemptStatus(live)); assertEquals("SUCCEEDED", attemptStatus(done));
        var expired = jdbc.queryForMap("SELECT * FROM sys_ai_request_attempt WHERE request_id=?", noOutput);
        assertNull(expired.get("input_tokens")); assertNull(expired.get("output_tokens")); assertNotNull(expired.get("finish_time"));
        Object finished = expired.get("finish_time");
        assertEquals(0, transaction.execute(status -> requests.expire(now.plusSeconds(1))).intValue());
        assertEquals(finished, jdbc.queryForObject("SELECT finish_time FROM sys_ai_request_attempt WHERE request_id=?", Timestamp.class, noOutput));
    }

    private String attemptStatus(long id) { return jdbc.queryForObject("SELECT status FROM sys_ai_request_attempt WHERE request_id=?", String.class, id); }
    private long running() {
        Instant now = Instant.now();
        var result = transaction.execute(status -> requests.accept(new AiRequestStore.Admission(1, null, 1, AiRequestStore.Kind.TEST,
                owner, "lowcode.record", "1:7", UUID.randomUUID().toString(), "a".repeat(64), now.plusSeconds(60)), now));
        long id = Long.parseLong(result.request().requestId()); assertTrue(requests.start(id, now)); return id;
    }
}
