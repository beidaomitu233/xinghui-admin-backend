package com.xinghuiTec.ai.application;

import com.xinghuiTec.ai.contract.AiProviderRuntime.ExecutionOutcome;
import com.xinghuiTec.ai.contract.AiProviderRuntime.Target;
import com.xinghuiTec.ai.contract.AiRuntimeException;
import com.xinghuiTec.ai.contract.AiTypes.ErrorCode;
import com.xinghuiTec.ai.contract.AiTypes.Usage;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.support.GeneratedKeyHolder;
import org.springframework.stereotype.Service;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

import java.sql.Timestamp;
import java.time.Instant;
import java.util.List;

/** Safe request/attempt timeline. No arbitrary error text, prompt, input or output fields. */
@Service
public class AiRequestJournal {
    public enum Outcome { STARTED, SUCCEEDED, FAILED, DEFAULT_USED, OPTIONAL_MISSING, TRUNCATED }
    public enum ReferenceKind { TEMPLATE, RESOURCE, KEY, MODEL, VARIABLE }
    public enum SwitchReason { FIRST, NEXT_KEY, FALLBACK_MODEL }
    public record Attempt(String attemptId, int number) {}

    private final JdbcTemplate jdbc;
    private final AiRequestStore requests;
    private final TransactionTemplate transaction;

    public AiRequestJournal(JdbcTemplate jdbc, AiRequestStore requests, PlatformTransactionManager manager) {
        this.jdbc = jdbc;
        this.requests = requests;
        this.transaction = new TransactionTemplate(manager);
    }

    public Attempt reserve(long requestId, AiRequestStore.Owner owner, Target target,
                           int maximumAttempts, SwitchReason reason, int outputTokenLimit) {
        if (maximumAttempts < 1 || maximumAttempts > 8 || reason == null || outputTokenLimit < 1 || outputTokenLimit > 4096) {
            throw new AiRuntimeException(ErrorCode.INVALID_INPUT);
        }
        return transaction.execute(status -> {
            var request = requests.currentOwned(requestId, owner);
            if (request.status() != AiRequestStatus.RUNNING) throw new AiRuntimeException(ErrorCode.CANCELLED);
            if (!Instant.now().isBefore(request.deadline())) throw new AiRuntimeException(ErrorCode.DEADLINE_EXCEEDED);
            if (request.outputStarted() || request.attemptCount() >= maximumAttempts) {
                throw new AiRuntimeException(ErrorCode.OUTPUT_LIMIT);
            }
            int number = request.attemptCount() + 1;
            var keys = new GeneratedKeyHolder();
            Instant now = Instant.now();
            jdbc.update(connection -> {
                var statement = connection.prepareStatement("INSERT INTO sys_ai_request_attempt"
                                + "(request_id,attempt_number,config_version,model_ref,key_ref,masked_key_label,"
                                + "switch_reason,status,create_time,provider_code,model_code,key_version,output_token_limit)"
                                + " VALUES(?,?,?,?,?,?,?,'RUNNING',?,?,?,?,?)",
                        new String[] {"attempt_id"});
                statement.setLong(1, requestId);
                statement.setInt(2, number);
                statement.setString(3, reference(target.model().configVersion(), 64));
                statement.setString(4, reference(target.model().modelRef(), 128));
                statement.setString(5, reference(target.key().keyRef(), 128));
                statement.setString(6, reference(target.maskedKeyLabel(), 128));
                statement.setString(7, reason.name());
                statement.setTimestamp(8, Timestamp.from(now));
                statement.setString(9, reference(target.providerCode(), 64));
                statement.setString(10, reference(target.modelCode(), 256));
                statement.setString(11, reference(target.key().keyVersion(), 64));
                statement.setInt(12, outputTokenLimit);
                return statement;
            }, keys);
            if (keys.getKey() == null) throw new AiRuntimeException(ErrorCode.INTERNAL_FAILURE);
            jdbc.update("UPDATE sys_ai_request SET attempt_count=?,stage='PROVIDER',update_time=? WHERE request_id=?",
                    number, Timestamp.from(now), requestId);
            stage(requestId, AiRequestStore.Stage.PROVIDER, Outcome.STARTED, null, ReferenceKind.KEY,
                    target.key().keyRef(), target.key().keyVersion());
            return new Attempt(keys.getKey().toString(), number);
        });
    }

    public boolean complete(long requestId, String attemptId, ExecutionOutcome outcome) {
        return jdbc.update("UPDATE sys_ai_request_attempt SET status=?,error_code=?,input_tokens=?,output_tokens=?,"
                        + "provider_request_id=?,finish_time=? WHERE attempt_id=? AND request_id=? AND status='RUNNING'",
                outcome.status().name(), outcome.errorCode() == null ? null : outcome.errorCode().name(),
                outcome.usage() == null ? null : outcome.usage().inputTokens(),
                outcome.usage() == null ? null : outcome.usage().outputTokens(),
                safeProviderRequestId(outcome.providerRequestId()), Timestamp.from(Instant.now()), AiApplicationService.positiveId(attemptId), requestId) == 1;
    }

    public Usage usage(long requestId) {
        List<Long[]> rows = jdbc.query("SELECT input_tokens,output_tokens FROM sys_ai_request_attempt"
                + " WHERE request_id=? ORDER BY attempt_number", (rs, index) -> new Long[] {
                rs.getObject(1, Long.class), rs.getObject(2, Long.class)}, requestId);
        if (rows.isEmpty()) return null;
        long input = 0, output = 0;
        boolean inputKnown = true, outputKnown = true;
        for (Long[] row : rows) {
            if (row[0] == null) inputKnown = false; else input = Math.addExact(input, row[0]);
            if (row[1] == null) outputKnown = false; else output = Math.addExact(output, row[1]);
        }
        return new Usage(inputKnown ? input : null, outputKnown ? output : null,
                inputKnown && outputKnown ? Math.addExact(input, output) : null);
    }

    public void stage(long requestId, AiRequestStore.Stage stage, Outcome outcome, ErrorCode code,
                      ReferenceKind kind, String referenceId, String version) {
        if (stage == null || outcome == null || (kind == null && (referenceId != null || version != null))) {
            throw new AiRuntimeException(ErrorCode.INVALID_INPUT);
        }
        jdbc.update("INSERT INTO sys_ai_request_stage(request_id,stage,outcome,error_code,reference_kind,"
                        + "reference_id,reference_version,create_time) VALUES(?,?,?,?,?,?,?,?)",
                requestId, stage.name(), outcome.name(), code == null ? null : code.name(), kind == null ? null : kind.name(),
                referenceId == null ? null : reference(referenceId, 128), version == null ? null : reference(version, 64),
                Timestamp.from(Instant.now()));
    }

    private String reference(String value, int max) {
        if (value == null || value.isBlank() || value.length() > max
                || value.codePoints().anyMatch(Character::isISOControl)) throw new AiRuntimeException(ErrorCode.INVALID_INPUT);
        return value;
    }

    private String safeProviderRequestId(String value) {
        return value != null && value.matches("[A-Za-z0-9][A-Za-z0-9_.:/-]{0,127}") ? value : null;
    }
}
