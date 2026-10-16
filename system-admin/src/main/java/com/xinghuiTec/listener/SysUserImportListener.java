package com.xinghuiTec.listener;

import cn.idev.excel.context.AnalysisContext;
import cn.idev.excel.event.AnalysisEventListener;
import com.xinghuiTec.domain.excel.SysUserImportExcel;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/** Reads a bounded number of rows before any database writes are attempted. */
public class SysUserImportListener extends AnalysisEventListener<SysUserImportExcel> {
    public static final int MAX_IMPORT_ROWS = 1_000;
    public static final List<String> REQUIRED_HEADERS = List.of(
            "用户账号", "用户昵称", "邮箱", "手机号码", "角色标识");

    private final List<ParsedRow> rows = new ArrayList<>();
    private boolean headersValid;
    private boolean rowLimitExceeded;
    private int seenRows;

    @Override
    public void invokeHeadMap(Map<Integer, String> headMap, AnalysisContext context) {
        headersValid = headMap != null
                && headMap.size() == REQUIRED_HEADERS.size()
                && REQUIRED_HEADERS.stream().allMatch(header -> header.equals(headMap.get(
                        REQUIRED_HEADERS.indexOf(header))));
    }

    @Override
    public void invoke(SysUserImportExcel data, AnalysisContext context) {
        seenRows++;
        if (seenRows > MAX_IMPORT_ROWS) {
            rowLimitExceeded = true;
            return;
        }
        int rowNumber = context.readRowHolder().getRowIndex() + 1;
        rows.add(new ParsedRow(rowNumber, data));
    }

    @Override
    public boolean hasNext(AnalysisContext context) {
        return !rowLimitExceeded;
    }

    @Override
    public void doAfterAllAnalysed(AnalysisContext context) {
        // Parsing is intentionally side-effect free; the service validates and saves afterward.
    }

    public List<ParsedRow> getRows() {
        return rows;
    }

    public boolean areHeadersValid() {
        return headersValid;
    }

    public boolean isRowLimitExceeded() {
        return rowLimitExceeded;
    }

    public record ParsedRow(int rowNumber, SysUserImportExcel data) {
    }
}
