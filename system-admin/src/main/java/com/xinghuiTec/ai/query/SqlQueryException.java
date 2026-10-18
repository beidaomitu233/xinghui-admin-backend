package com.xinghuiTec.ai.query;

import com.xinghuiTec.ai.contract.AiSceneQueryRuntime.QueryIssue;

/** Fixed classifications only: never retain SQL, parameters, database messages or causes. */
public final class SqlQueryException extends RuntimeException {
    private final QueryIssue issue;
    public SqlQueryException(QueryIssue issue) { super("AI_QUERY:" + issue.name()); this.issue = issue; }
    public QueryIssue issue() { return issue; }
    public int status() {
        return switch (issue) {
            case DATASET_FORBIDDEN, FIELD_FORBIDDEN -> 403;
            case SCHEMA_CHANGED -> 409;
            case READ_ONLY_UNAVAILABLE -> 503;
            case QUERY_TIMEOUT -> 504;
            case RESULT_LIMIT -> 413;
            default -> 422;
        };
    }
}
