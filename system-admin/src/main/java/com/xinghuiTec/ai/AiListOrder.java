package com.xinghuiTec.ai;

import com.xinghuiTec.exception.ServiceException;
import java.util.Map;

/** SQL identifiers are supplied exclusively by each AI listing's fixed whitelist. */
public final class AiListOrder {
    private AiListOrder() {}

    public static String clause(String field, String direction, Map<String, String> columns,
                                String defaultOrder, String idColumn) {
        return clause(field, direction, columns, defaultOrder, idColumn,
                () -> new ServiceException("不支持的排序字段或方向"));
    }

    public static String clause(String field, String direction, Map<String, String> columns,
                                String defaultOrder, String idColumn,
                                java.util.function.Supplier<? extends RuntimeException> invalid) {
        boolean noField = field == null || field.isBlank();
        boolean noDirection = direction == null || direction.isBlank();
        if (noField && noDirection) return " ORDER BY " + defaultOrder;
        String column = noField ? null : columns.get(field);
        if (column == null || !("asc".equals(direction) || "desc".equals(direction))) {
            throw invalid.get();
        }
        String order = "asc".equals(direction) ? " ASC" : " DESC";
        return " ORDER BY " + column + order
                + (column.equals(idColumn) ? "" : "," + idColumn + order);
    }
}
