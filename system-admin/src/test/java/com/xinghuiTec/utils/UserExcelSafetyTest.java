package com.xinghuiTec.utils;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;

class UserExcelSafetyTest {
    @Test
    void prefixesFormulaMarkersIncludingAfterWhitespaceAndControls() {
        assertEquals("'=1+1", UserExcelSafety.safeCell("=1+1"));
        assertEquals("'\t@SUM(A1:A2)", UserExcelSafety.safeCell("\t@SUM(A1:A2)"));
        assertEquals("' -2+3", UserExcelSafety.safeCell(" -2+3"));
        assertEquals("'\r+cmd", UserExcelSafety.safeCell("\r+cmd"));
        assertEquals("'\u00a0=1", UserExcelSafety.safeCell("\u00a0=1"));
        assertEquals("'\ufeff=1", UserExcelSafety.safeCell("\ufeff=1"));
        assertEquals("'\u200b@cmd", UserExcelSafety.safeCell("\u200b@cmd"));
    }

    @Test
    void leavesOrdinaryCellsAlone() {
        assertEquals("operator", UserExcelSafety.safeCell("operator"));
        assertEquals("", UserExcelSafety.safeCell(""));
        assertNull(UserExcelSafety.safeCell(null));
    }
}
