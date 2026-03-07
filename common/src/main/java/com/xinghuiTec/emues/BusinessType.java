package com.xinghuiTec.emues;

/**
 * 业务操作类型枚举
 * 用于标识不同类型的业务操作
 * 
 * @author beidoa23
 * @since 2026-01-23
 */
public enum BusinessType {

    /**
     * 其他操作
     */
    OTHER(0),

    /**
     * 新增操作
     */
    INSERT(1),

    /**
     * 修改操作
     */
    UPDATE(2),

    /**
     * 删除操作
     */
    DELETE(3),

    /**
     * 授权操作
     */
    GRANT(4),

    /**
     * 导出操作
     */
    EXPORT(5),

    /**
     * 导入操作
     */
    IMPORT(6);

    /**
     * 业务类型代码
     */
    private final Integer code;

    BusinessType(Integer code) {
        this.code = code;
    }

    public Integer getCode() {
        return code;
    }
}
