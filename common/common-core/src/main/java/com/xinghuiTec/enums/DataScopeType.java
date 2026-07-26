package com.xinghuiTec.enums;

import lombok.AllArgsConstructor;
import lombok.Getter;

/**
 * 数据权限类型枚举
 *
 * @author xinghuiTec
 */
@Getter
@AllArgsConstructor
public enum DataScopeType {

    /** 全部数据权限 */
    ALL("1", "全部数据权限"),

    /** 仅本人数据权限 */
    SELF("5", "仅本人数据权限");

    private final String code;
    private final String description;

    public static DataScopeType findCode(String code) {
        if (code == null || code.isBlank()) {
            return null;
        }
        for (DataScopeType type : values()) {
            if (type.getCode().equals(code)) {
                return type;
            }
        }
        return null;
    }
}
