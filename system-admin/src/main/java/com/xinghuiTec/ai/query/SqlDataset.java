package com.xinghuiTec.ai.query;

import com.xinghuiTec.ai.contract.AiSceneQueryRuntime.*;
import java.util.List;
import java.util.ArrayList;
import java.util.Collections;

/** Internal, server-produced source. Client SQL cannot supply physical projections or policy predicates. */
record SqlDataset(DatasetSchema schema, String policyCode, String sourceSql,
                  List<Object> sourceParameters, List<String> primaryKey) {
    SqlDataset { sourceParameters = Collections.unmodifiableList(new ArrayList<>(sourceParameters)); primaryKey = List.copyOf(primaryKey); }
    QueryColumn field(String name) {
        return schema.columns().stream().filter(c -> c.name().equals(name)).findFirst()
                .orElseThrow(() -> new SqlQueryException(QueryIssue.FIELD_FORBIDDEN));
    }
    @Override public String toString() { return "SqlDataset[REDACTED]"; }
}
