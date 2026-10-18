package com.xinghuiTec.ai.contract;

import java.util.List;
import java.util.Map;
import java.util.Objects;
import static com.xinghuiTec.ai.contract.AiTypes.*;

/** C-owned boundary. No provider calls, scene writes or caller-supplied authority. */
public interface AiSceneQueryRuntime {
    String COMPILER_VERSION = "scene-query-v1";

    /** Metadata only. Dataset selection narrows access; it never grants access. */
    AuthorizedSchema authorizedSchema(TrustedActor actor, QueryContext context, InvocationControl control);

    default AiSceneDataRuntime.Page<DatasetOption> catalog(AiSceneDataRuntime.OptionQuery query, TrustedActor actor, InvocationControl control) {
        throw new AiRuntimeException(ErrorCode.CAPABILITY_UNSUPPORTED);
    }

    /** AST/static metadata review only, including when called after AI generation. Never executes SQL. */
    SqlValidation validateGeneratedSql(SqlQuery query, TrustedActor actor, QueryContext context, InvocationControl control);

    CompiledQuery compile(SqlQuery query, TrustedActor actor, QueryContext context, InvocationControl control);

    QueryResult preview(SqlQuery query, TrustedActor actor, QueryContext context, InvocationControl control);

    QueryResult read(CompiledQuery query, TrustedActor actor, QueryContext context, InvocationControl control);

    void authorize(CompiledQuery query, TrustedActor actor, QueryContext context, InvocationControl control);

    /** Server constructs this after admission; businessRef still undergoes dataset/row checks. */
    record QueryContext(List<String> datasetKeys, BusinessRef businessRef) {
        public QueryContext {
            datasetKeys = datasetKeys == null ? List.of() : List.copyOf(datasetKeys);
            if (datasetKeys.size() > 16) throw new IllegalArgumentException("Too many datasets");
        }
        @Override public String toString() { return "QueryContext[REDACTED]"; }
    }

    enum ScopeKind { PLATFORM, TENANT, PERSONAL }

    record DatasetOption(String datasetKey, String sqlName, String label, ScopeKind scope, String scopeDescription) {}

    record QueryColumn(String name, String label, String type, boolean nullable) {}

    record DatasetSchema(String datasetKey, String sqlName, String label, String description,
                         ScopeKind scope, String scopeDescription, List<QueryColumn> columns,
                         String schemaFingerprint) {
        public DatasetSchema { columns = List.copyOf(columns); }
    }

    record AuthorizedSchema(List<DatasetSchema> datasets, List<String> supportedSyntax,
                            List<String> trustedParameters, String scopeDescription, String fingerprint) {
        public AuthorizedSchema {
            datasets = List.copyOf(datasets);
            supportedSyntax = List.copyOf(supportedSyntax);
            trustedParameters = List.copyOf(trustedParameters);
        }
        @Override public String toString() { return "AuthorizedSchema[REDACTED]"; }
    }

    /** Parameters are business values only. Reserved current_* values come exclusively from the server. */
    record SqlQuery(String sql, @com.fasterxml.jackson.databind.annotation.JsonDeserialize(using=SqlQueryParametersDeserializer.class)
                    Map<String, Object> parameters, int maxRows) {
        public SqlQuery {
            Objects.requireNonNull(sql);
            parameters = parameters == null ? Map.of() : new AiInputs(parameters).values();
            if (sql.isBlank() || sql.length() > 16000 || maxRows < 1 || maxRows > 200 || parameters.size() > 32)
                throw new IllegalArgumentException("Invalid query bounds");
            var canonical=new java.util.LinkedHashMap<String,Object>();
            parameters.forEach((name,value)-> {
                if(value!=null && !(value instanceof String || value instanceof Boolean || value instanceof Number))throw new IllegalArgumentException("Invalid query parameter");
                canonical.put(name,value instanceof Number n?new java.math.BigDecimal(n.toString()).stripTrailingZeros():value);
            });
            parameters=java.util.Collections.unmodifiableMap(canonical);
        }
        @Override public String toString() { return "SqlQuery[REDACTED]"; }
    }

    /** Public scene definition shape; variable is stable identity, label/description are display text. */
    record QueryBinding(String variable, String label, String description, List<String> datasetKeys, SqlQuery query) {
        public QueryBinding {
            Objects.requireNonNull(variable); Objects.requireNonNull(label); Objects.requireNonNull(query);
            description = description == null ? "" : description;
            datasetKeys = datasetKeys == null ? List.of() : List.copyOf(datasetKeys);
            if (!variable.matches("[A-Za-z_][A-Za-z0-9_]{0,63}") || "user_request".equals(variable)
                    || label.isBlank() || label.length() > 100 || label.codePoints().anyMatch(c -> Character.isISOControl(c)
                        || c == 0x2028 || c == 0x2029 || c == '{' || c == '}')
                    || description.length() > 2000 || datasetKeys.size() > 16)
                throw new IllegalArgumentException("Invalid query binding");
        }
        @Override public String toString() { return "QueryBinding[REDACTED]"; }
    }

    record QuerySnapshot(QueryBinding binding, CompiledQuery compiled) {
        public QuerySnapshot { Objects.requireNonNull(binding); Objects.requireNonNull(compiled); }
        @Override public String toString() { return "QuerySnapshot[REDACTED]"; }
    }

    /** Fixed classifications only; diagnostics never copy SQL, parameters, values or vendor errors. */
    record QueryDiagnostic(String path, QueryIssue code) {}
    enum QueryIssue {
        INVALID_SQL, UNSUPPORTED_SYNTAX, DATASET_FORBIDDEN, FIELD_FORBIDDEN,
        PARAMETER_INVALID, CONTEXT_REQUIRED, SCHEMA_CHANGED, READ_ONLY_UNAVAILABLE,
        QUERY_TIMEOUT, RESULT_LIMIT
    }

    record SqlValidation(boolean valid, String normalizedSql, List<QueryColumn> columns,
                         List<QueryDiagnostic> diagnostics, String schemaFingerprint, String currentRecordDatasetKey) {
        public SqlValidation(boolean valid, String normalizedSql, List<QueryColumn> columns,
                             List<QueryDiagnostic> diagnostics, String schemaFingerprint) {
            this(valid, normalizedSql, columns, diagnostics, schemaFingerprint, null);
        }
        public SqlValidation { columns = List.copyOf(columns); diagnostics = List.copyOf(diagnostics); }
        @Override public String toString() { return "SqlValidation[REDACTED]"; }
    }

    /** Internal immutable persistence shape. Not accepted by any public HTTP endpoint. */
    record DatasetSnapshot(String datasetKey, String sqlName, String policyCode,
                           List<QueryColumn> dependencies, String schemaFingerprint) {
        public DatasetSnapshot { dependencies = List.copyOf(dependencies); }
    }

    record CompiledQuery(String compilerVersion, SqlQuery query, List<DatasetSnapshot> datasets,
                         List<QueryColumn> columns, String fingerprint, String currentRecordDatasetKey) {
        public CompiledQuery { datasets = List.copyOf(datasets); columns = List.copyOf(columns); }
        @Override public String toString() { return "CompiledQuery[REDACTED]"; }
    }

    record QueryResult(List<QueryColumn> columns, List<Map<String, Object>> rows,
                       int rowCount, boolean truncated, List<QueryDiagnostic> notices) {
        public QueryResult {
            columns = List.copyOf(columns);
            rows = rows.stream().map(row -> new AiInputs(row).values()).toList();
            notices = List.copyOf(notices);
        }
        @Override public String toString() { return "QueryResult[REDACTED]"; }
    }
}
