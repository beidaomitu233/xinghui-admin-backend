package com.xinghuiTec.ai.query;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.xinghuiTec.ai.contract.*;
import com.xinghuiTec.ai.contract.AiSceneDataRuntime.*;
import com.xinghuiTec.ai.contract.AiSceneQueryRuntime.*;
import com.xinghuiTec.ai.contract.AiTypes.*;
import jakarta.annotation.PreDestroy;
import org.springframework.stereotype.Service;
import java.time.*;
import java.util.*;
import java.util.concurrent.*;
import java.util.function.Function;
import static com.xinghuiTec.ai.contract.AiSceneQueryRuntime.QueryIssue.*;

/** Authorized SQL metadata, immutable compilation and real reads share one current-caller boundary. */
@Service
public final class SceneQueryService implements AiSceneQueryRuntime {
    private static final List<String> SYNTAX = List.of("SELECT explicit columns or *", "INNER JOIN / LEFT JOIN with ON (up to four sources)",
            "WHERE AND OR NOT, comparisons, IN, IS NULL, LIKE / NOT LIKE ESCAPE, BETWEEN", "GROUP BY / HAVING, ORDER BY, DISTINCT, LIMIT (1..200)",
            "COUNT SUM AVG MIN MAX LOWER UPPER LENGTH CHAR_LENGTH COALESCE", "Named business parameters :name; no UNION, subqueries or CTE");
    private final SqlActorVerifier actors;
    private final SqlDatasetRegistry datasets;
    private final SqlAstCompiler compiler;
    private final SqlReadOnlyExecutor executor;
    private final ObjectMapper json = new ObjectMapper();
    private final ExecutorService workers = new ThreadPoolExecutor(2, 4, 30, TimeUnit.SECONDS, new ArrayBlockingQueue<>(8),
            task -> { var thread = new Thread(task, "ai-scene-sql"); thread.setDaemon(true); return thread; }, new ThreadPoolExecutor.AbortPolicy());
    public SceneQueryService(SqlActorVerifier actors, SqlDatasetRegistry datasets, SqlAstCompiler compiler, SqlReadOnlyExecutor executor) {
        this.actors = actors; this.datasets = datasets; this.compiler = compiler; this.executor = executor;
    }
    @PreDestroy public void close() { workers.shutdownNow(); }

    @Override public Page<DatasetOption> catalog(OptionQuery request, TrustedActor actor, InvocationControl control) {
        return access(actor, control, current -> datasets.catalog(current, request));
    }
    @Override public AuthorizedSchema authorizedSchema(TrustedActor actor, QueryContext context, InvocationControl control) {
        return access(actor, control, current -> schema(current, datasets.resolve(context, current, control)));
    }
    private AuthorizedSchema schema(TrustedActor current, List<SqlDataset> sources) {
        var visible = sources.stream().map(SqlDataset::schema).toList();
        // Generation/poll drift is caller-sensitive; persisted compiled bindings below deliberately are not.
        String fingerprint = SqlFingerprints.hash(List.of(current.subjectId(), actors.tenant(current), new TreeSet<>(current.authorities()),
                visible.stream().map(DatasetSchema::schemaFingerprint).toList()));
        var schema = new AuthorizedSchema(visible, SYNTAX, List.of("current_user_id", "current_tenant_id", "current_record_id"),
                "每个来源均由服务器独立限制租户、个人归属（如适用）和软删除；JOIN及OR不能扩大授权范围。", fingerprint);
        try { if (json.writeValueAsBytes(schema).length > 65536) throw new SqlQueryException(RESULT_LIMIT); }
        catch (com.fasterxml.jackson.core.JsonProcessingException invalid) { throw new SqlQueryException(RESULT_LIMIT); }
        return schema;
    }
    @Override public SqlValidation validateGeneratedSql(SqlQuery query, TrustedActor actor, QueryContext context, InvocationControl control) {
        return access(actor, control, current -> {
            var allowed = datasets.resolve(context, current, control);
            String fingerprint = schema(current, allowed).fingerprint();
            try {
                var plan = compiler.compile(query, allowed);
                return new SqlValidation(true, plan.normalizedSql(), plan.columns(), List.of(), fingerprint, plan.currentRecordDatasetKey());
            } catch (SqlQueryException invalid) {
                return new SqlValidation(false, null, List.of(), List.of(new QueryDiagnostic("query", invalid.issue())), fingerprint);
            }
        });
    }
    @Override public CompiledQuery compile(SqlQuery query, TrustedActor actor, QueryContext context, InvocationControl control) {
        return access(actor, control, current -> snapshot(query, compiler.compile(query, datasets.resolve(context, current, control))));
    }
    private CompiledQuery snapshot(SqlQuery query, SqlAstCompiler.Plan plan) {
        var pinned = plan.datasets().stream().map(dataset -> {
            var dependencies = plan.dependencies().get(dataset.schema().datasetKey());
            return new DatasetSnapshot(dataset.schema().datasetKey(), dataset.schema().sqlName(), dataset.policyCode(), dependencies,
                    SqlDatasetRegistry.fingerprint(dataset.policyCode(), dependencies));
        }).toList();
        var normalized = new SqlQuery(plan.normalizedSql(), query.parameters(), plan.maxRows());
        String fingerprint = SqlFingerprints.hash(Arrays.asList(COMPILER_VERSION, normalized, pinned, plan.columns(), plan.currentRecordDatasetKey()));
        return new CompiledQuery(COMPILER_VERSION, normalized, pinned, plan.columns(), fingerprint, plan.currentRecordDatasetKey());
    }
    private SqlAstCompiler.Plan restore(CompiledQuery fixed, TrustedActor actor, QueryContext context, InvocationControl control) {
        if (!COMPILER_VERSION.equals(fixed.compilerVersion()) || fixed.datasets().isEmpty() || fixed.datasets().size() > 4)
            throw new SqlQueryException(SCHEMA_CHANGED);
        if (!context.datasetKeys().isEmpty() && !new HashSet<>(context.datasetKeys()).containsAll(fixed.datasets().stream().map(DatasetSnapshot::datasetKey).toList()))
            throw new SqlQueryException(DATASET_FORBIDDEN);
        var allowed = datasets.resolve(new QueryContext(fixed.datasets().stream().map(DatasetSnapshot::datasetKey).toList(), context.businessRef()), actor, control);
        var plan = compiler.compile(fixed.query(), allowed);
        var actual = snapshot(fixed.query(), plan);
        if (!fixed.equals(actual)) throw new SqlQueryException(SCHEMA_CHANGED);
        return plan;
    }
    @Override public QueryResult preview(SqlQuery query, TrustedActor actor, QueryContext context, InvocationControl control) {
        return access(actor, control, current -> execute(compiler.compile(query, datasets.resolve(context, current, control)), current, context, control));
    }
    @Override public QueryResult read(CompiledQuery fixed, TrustedActor actor, QueryContext context, InvocationControl control) {
        return access(actor, control, current -> execute(restore(fixed, current, context, control), current, context, control));
    }
    @Override public void authorize(CompiledQuery fixed, TrustedActor actor, QueryContext context, InvocationControl control) {
        access(actor, control, current -> {
            var plan = restore(fixed, current, context, control);
            if (plan.currentRecordDatasetKey() != null) record(plan, context, control);
            return Boolean.TRUE;
        });
    }
    private Object record(SqlAstCompiler.Plan plan, QueryContext context, InvocationControl control) {
        var dataset = plan.datasets().stream().filter(d -> d.schema().datasetKey().equals(plan.currentRecordDatasetKey())).findFirst()
                .orElseThrow(() -> new SqlQueryException(CONTEXT_REQUIRED));
        return datasets.currentRecord(dataset, context.businessRef(), control);
    }
    private QueryResult execute(SqlAstCompiler.Plan plan, TrustedActor actor, QueryContext context, InvocationControl control) {
        Object record = plan.currentRecordDatasetKey() == null ? null : record(plan, context, control);
        var values = new ArrayList<Object>();
        for (Object parameter : plan.parameters()) {
            if (parameter instanceof SqlAstCompiler.TrustedValue trusted) values.add(switch (trusted.name()) {
                case "current_user_id" -> Long.valueOf(actor.subjectId());
                case "current_tenant_id" -> actors.tenant(actor);
                case "current_record_id" -> record;
                default -> throw new SqlQueryException(PARAMETER_INVALID);
            });
            else values.add(parameter);
        }
        return executor.execute(plan, values, control);
    }
    private <T> T access(TrustedActor actor, InvocationControl control, Function<TrustedActor, T> action) {
        Objects.requireNonNull(control); control.check();
        Future<T> future;
        try { future = workers.submit(() -> executor.transaction(control, () -> action.apply(actors.verify(actor)))); }
        catch (RejectedExecutionException full) { throw new AiRuntimeException(ErrorCode.RATE_LIMITED); }
        try {
            long remaining = Duration.between(Instant.now(), control.deadline()).toMillis();
            if (remaining <= 0) throw new TimeoutException();
            return future.get(Math.min(10000, remaining), TimeUnit.MILLISECONDS);
        } catch (InterruptedException interrupted) {
            future.cancel(true); Thread.currentThread().interrupt(); throw new SqlQueryException(QUERY_TIMEOUT);
        } catch (TimeoutException timeout) {
            future.cancel(true); throw new SqlQueryException(QUERY_TIMEOUT);
        } catch (ExecutionException wrapped) {
            if (wrapped.getCause() instanceof SqlQueryException safe) throw safe;
            if (wrapped.getCause() instanceof AiRuntimeException safe) throw safe;
            if (wrapped.getCause() instanceof org.springframework.transaction.CannotCreateTransactionException)
                throw new SqlQueryException(READ_ONLY_UNAVAILABLE);
            throw new AiRuntimeException(ErrorCode.DEPENDENCY_UNAVAILABLE);
        }
    }
}
