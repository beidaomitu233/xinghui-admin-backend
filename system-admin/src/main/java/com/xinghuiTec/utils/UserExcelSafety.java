package com.xinghuiTec.utils;

/** Excel output safety helpers for user-controlled cell text. */
public final class UserExcelSafety {
    private UserExcelSafety() {
    }

    public static String safeCell(String value) {
        if (value == null || value.isEmpty()) {
            return value;
        }
        int offset = 0;
        while (offset < value.length()) {
            int codePoint = value.codePointAt(offset);
            if (!Character.isWhitespace(codePoint) && !Character.isSpaceChar(codePoint)
                    && !Character.isISOControl(codePoint)
                    && Character.getType(codePoint) != Character.FORMAT) {
                break;
            }
            offset += Character.charCount(codePoint);
        }
        if (offset < value.length() && "=+-@".indexOf(value.charAt(offset)) >= 0) {
            return "'" + value;
        }
        return value;
    }
}
