package com.xinghuiTec.ai.query;

import com.fasterxml.jackson.core.JsonFactory;
import com.fasterxml.jackson.core.StreamReadConstraints;
import com.fasterxml.jackson.core.StreamReadFeature;
import com.fasterxml.jackson.databind.DeserializationFeature;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.xinghuiTec.ai.contract.AiRuntimeException;
import com.xinghuiTec.ai.contract.AiSceneQueryRuntime.*;
import com.xinghuiTec.ai.contract.AiTypes.*;
import org.springframework.jdbc.core.ConnectionCallback;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DataSourceTransactionManager;
import org.springframework.stereotype.Component;
import org.springframework.transaction.TransactionDefinition;
import org.springframework.transaction.support.TransactionTemplate;
import java.math.BigDecimal;
import java.nio.charset.StandardCharsets;
import java.sql.*;
import java.time.*;
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.function.Supplier;
import static com.xinghuiTec.ai.contract.AiSceneQueryRuntime.QueryIssue.*;

/** SQL-only REQUIRES_NEW transactions enforce real server read-only mode; global transaction beans are untouched. */
@Component
public final class SqlReadOnlyExecutor {
    static final int MAX_BYTES = 262144;
    private static final ObjectMapper JSON = new ObjectMapper(JsonFactory.builder()
            .streamReadConstraints(StreamReadConstraints.builder().maxNestingDepth(16).maxStringLength(8192).build())
            .enable(StreamReadFeature.STRICT_DUPLICATE_DETECTION).build()).enable(DeserializationFeature.FAIL_ON_TRAILING_TOKENS);
    private static final ScheduledExecutorService CANCEL = Executors.newScheduledThreadPool(2, task -> {
        var thread = new Thread(task, "ai-sql-cancel"); thread.setDaemon(true); return thread;
    });
    private final JdbcTemplate jdbc;
    private final DataSourceTransactionManager manager;
    public SqlReadOnlyExecutor(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
        this.manager = new DataSourceTransactionManager(Objects.requireNonNull(jdbc.getDataSource()));
        manager.setEnforceReadOnly(true);
        manager.setValidateExistingTransaction(true);
    }

    <T> T transaction(InvocationControl control, Supplier<T> body) {
        control.check();
        var transaction = new TransactionTemplate(manager);
        transaction.setPropagationBehavior(TransactionDefinition.PROPAGATION_REQUIRES_NEW);
        transaction.setReadOnly(true);
        transaction.setIsolationLevel(TransactionDefinition.ISOLATION_READ_COMMITTED);
        transaction.setTimeout(seconds(control));
        return transaction.execute(status -> {
            Boolean mysql = jdbc.execute((ConnectionCallback<Boolean>) connection -> "MySQL".equals(connection.getMetaData().getDatabaseProductName()));
            if (!Boolean.TRUE.equals(mysql)) throw new SqlQueryException(READ_ONLY_UNAVAILABLE);
            control.check();
            T result = body.get();
            control.check();
            return result;
        });
    }

    QueryResult execute(SqlAstCompiler.Plan plan, List<Object> values, InvocationControl control) {
        return jdbc.execute((ConnectionCallback<QueryResult>) connection -> {
            var running = new AtomicBoolean(true);
            var timedOut = new AtomicBoolean();
            Object lock = new Object();
            Instant limit = Instant.now().plusSeconds(10);
            Instant deadline = control.deadline().isBefore(limit) ? control.deadline() : limit;
            try (var statement = connection.prepareStatement(plan.executionSql())) {
                statement.setQueryTimeout(seconds(control));
                statement.setMaxRows(plan.maxRows() + 1);
                statement.setFetchSize(Integer.MIN_VALUE);
                for (int index = 0; index < values.size(); index++) statement.setObject(index + 1, values.get(index));
                var watchdog = CANCEL.scheduleWithFixedDelay(() -> {
                    boolean abort;
                    try { abort = control.cancelled() || !Instant.now().isBefore(deadline); }
                    catch (RuntimeException unavailable) { abort = true; }
                    if (abort) synchronized (lock) {
                        if (running.get()) {
                            timedOut.set(true);
                            try { statement.cancel(); } catch (SQLException ignored) { /* Caller receives a fixed classification. */ }
                        }
                    }
                }, 0, 25, TimeUnit.MILLISECONDS);
                try (var result = statement.executeQuery()) {
                    var metadata = result.getMetaData();
                    if (metadata.getColumnCount() != plan.columns().size()) throw new SqlQueryException(SCHEMA_CHANGED);
                    var rows = new ArrayList<Map<String, Object>>();
                    var notices = new ArrayList<QueryDiagnostic>();
                    int bytes = 2;
                    boolean truncated = false;
                    while (result.next()) {
                        control.check();
                        if (!Instant.now().isBefore(deadline)) throw new SqlQueryException(QUERY_TIMEOUT);
                        if (rows.size() >= plan.maxRows()) { truncated = true; break; }
                        var row = new LinkedHashMap<String, Object>();
                        for (int index = 0; index < plan.columns().size(); index++) {
                            var column = plan.columns().get(index);
                            if (!column.name().equalsIgnoreCase(metadata.getColumnLabel(index + 1))) throw new SqlQueryException(SCHEMA_CHANGED);
                            Object value = value(result, index + 1, column, notices);
                            row.put(column.name(), value);
                        }
                        bytes += encoded(row) + 1;
                        if (bytes > MAX_BYTES) throw new SqlQueryException(RESULT_LIMIT);
                        rows.add(row);
                    }
                    var output = new QueryResult(plan.columns(), rows, rows.size(), truncated, notices.stream().distinct().toList());
                    if (encoded(output) > MAX_BYTES) throw new SqlQueryException(RESULT_LIMIT);
                    return output;
                } catch (SQLException failure) {
                    control.check();
                    if (timedOut.get() || failure instanceof SQLTimeoutException || failure.getErrorCode() == 3024 || failure.getErrorCode() == 1317)
                        throw new SqlQueryException(QUERY_TIMEOUT);
                    throw new SqlQueryException(INVALID_SQL);
                } finally {
                    synchronized (lock) { running.set(false); watchdog.cancel(false); }
                }
            }
        });
    }

    private Object value(ResultSet result, int index, QueryColumn column, List<QueryDiagnostic> notices) throws SQLException {
        Object value = result.getObject(index);
        if (value == null) return null;
        if (column.type().equals("JSON")) {
            try {
                String text = value.toString();
                if (text.length() > 8192) throw new SqlQueryException(RESULT_LIMIT);
                Object parsed = JSON.readValue(text, Object.class);
                if (!(parsed instanceof List<?> list) || list.stream().anyMatch(item -> !(item instanceof String)))
                    throw new SqlQueryException(SCHEMA_CHANGED);
                return parsed;
            } catch (SqlQueryException safe) { throw safe; }
            catch (Exception invalid) { throw new SqlQueryException(SCHEMA_CHANGED); }
        }
        if (column.type().equals("BOOLEAN")) {
            if (value instanceof Boolean) return value;
            if (value instanceof Number number) {
                var decimal=new BigDecimal(number.toString());
                if(decimal.compareTo(BigDecimal.ZERO)==0)return false;
                if(decimal.compareTo(BigDecimal.ONE)==0)return true;
            }
            throw new SqlQueryException(SCHEMA_CHANGED);
        }
        if (value instanceof String text) {
            if (text.length() > 8192) { notices.add(new QueryDiagnostic("rows", RESULT_LIMIT)); return text.substring(0, 8192); }
            return text;
        }
        if (value instanceof Number number) {
            if (number instanceof Double d && !Double.isFinite(d) || number instanceof Float f && !Float.isFinite(f)) throw new SqlQueryException(RESULT_LIMIT);
            return value;
        }
        if (value instanceof Boolean) return value;
        if (value instanceof Timestamp timestamp) return timestamp.toLocalDateTime().toString();
        if (value instanceof java.sql.Date date) return date.toLocalDate().toString();
        if (value instanceof java.sql.Time time) return time.toLocalTime().toString();
        if (value instanceof LocalDateTime || value instanceof LocalDate || value instanceof LocalTime
                || value instanceof OffsetDateTime || value instanceof OffsetTime || value instanceof Instant) return value.toString();
        throw new SqlQueryException(SCHEMA_CHANGED);
    }
    private static int seconds(InvocationControl control) {
        long remaining = Duration.between(Instant.now(), control.deadline()).toMillis();
        if (remaining <= 0) throw new SqlQueryException(QUERY_TIMEOUT);
        return (int) Math.max(1, Math.min(10, (remaining + 999) / 1000));
    }
    private static int encoded(Object value) {
        try { return JSON.writeValueAsBytes(value).length; }
        catch (Exception invalid) { throw new SqlQueryException(RESULT_LIMIT); }
    }
}
