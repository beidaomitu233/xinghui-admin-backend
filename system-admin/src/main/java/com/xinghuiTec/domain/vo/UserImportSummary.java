package com.xinghuiTec.domain.vo;

import java.util.List;

public record UserImportSummary(
        int totalRows,
        int successCount,
        int failureCount,
        int skippedCount,
        List<UserImportRowResult> rows) {
}
