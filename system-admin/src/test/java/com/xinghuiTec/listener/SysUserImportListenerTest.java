package com.xinghuiTec.listener;

import cn.idev.excel.FastExcel;
import com.xinghuiTec.domain.excel.SysUserImportExcel;
import org.junit.jupiter.api.Test;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class SysUserImportListenerTest {
    @Test
    void validatesTemplateHeadersAndKeepsPhysicalWorksheetRowNumbers() {
        SysUserImportExcel row = new SysUserImportExcel();
        row.setUsername("operator");
        row.setMobile("13800138000");

        SysUserImportListener listener = readWorkbook(List.of(row));

        assertTrue(listener.areHeadersValid());
        assertEquals(1, listener.getRows().size());
        assertEquals(2, listener.getRows().get(0).rowNumber());
        assertFalse(listener.isRowLimitExceeded());
    }

    @Test
    void acceptsTheHeaderOnlyTemplateAsAnEmptyWorkbook() {
        SysUserImportListener listener = readWorkbook(List.of());

        assertTrue(listener.areHeadersValid());
        assertTrue(listener.getRows().isEmpty());
        assertFalse(listener.isRowLimitExceeded());
    }

    @Test
    void stopsAtOneRowBeyondTheConfiguredMaximum() {
        List<SysUserImportExcel> rows = new ArrayList<>();
        for (int i = 0; i <= SysUserImportListener.MAX_IMPORT_ROWS; i++) {
            SysUserImportExcel row = new SysUserImportExcel();
            row.setUsername("user" + i);
            row.setMobile("13800138000");
            rows.add(row);
        }

        SysUserImportListener listener = readWorkbook(rows);

        assertTrue(listener.areHeadersValid());
        assertTrue(listener.isRowLimitExceeded());
        assertEquals(SysUserImportListener.MAX_IMPORT_ROWS, listener.getRows().size());
    }

    private SysUserImportListener readWorkbook(List<SysUserImportExcel> rows) {
        ByteArrayOutputStream output = new ByteArrayOutputStream();
        FastExcel.write(output, SysUserImportExcel.class).sheet("用户导入").doWrite(rows);
        SysUserImportListener listener = new SysUserImportListener();
        FastExcel.read(new ByteArrayInputStream(output.toByteArray()), SysUserImportExcel.class, listener)
                .sheet(0).doRead();
        return listener;
    }
}
