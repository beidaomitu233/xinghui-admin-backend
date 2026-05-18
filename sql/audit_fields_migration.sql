-- ============================================================
-- 审计字段迁移脚本（幂等：可重复执行不报错）
-- ============================================================

DELIMITER $$

-- 创建辅助存储过程：仅在列不存在时添加
DROP PROCEDURE IF EXISTS AddColumnIfNotExists$$
CREATE PROCEDURE AddColumnIfNotExists(
    IN tbl VARCHAR(128),
    IN col VARCHAR(128),
    IN colDef VARCHAR(1024)
)
BEGIN
    IF NOT EXISTS (
        SELECT 1 FROM information_schema.COLUMNS
        WHERE TABLE_SCHEMA = DATABASE()
          AND TABLE_NAME = tbl
          AND COLUMN_NAME = col
    ) THEN
        SET @sql = CONCAT('ALTER TABLE `', tbl, '` ADD COLUMN `', col, '` ', colDef);
        PREPARE stmt FROM @sql;
        EXECUTE stmt;
        DEALLOCATE PREPARE stmt;
    END IF;
END$$

DELIMITER ;

-- 批量执行
CALL AddColumnIfNotExists('sys_user',           'create_by', "VARCHAR(64) DEFAULT NULL COMMENT '创建者ID' AFTER `create_time`");
CALL AddColumnIfNotExists('sys_user',           'update_by', "VARCHAR(64) DEFAULT NULL COMMENT '更新者ID' AFTER `update_time`");
CALL AddColumnIfNotExists('sys_role',           'create_by', "VARCHAR(64) DEFAULT NULL COMMENT '创建者ID' AFTER `create_time`");
CALL AddColumnIfNotExists('sys_role',           'update_by', "VARCHAR(64) DEFAULT NULL COMMENT '更新者ID' AFTER `update_time`");
CALL AddColumnIfNotExists('sys_config',         'create_by', "VARCHAR(64) DEFAULT NULL COMMENT '创建者ID' AFTER `create_time`");
CALL AddColumnIfNotExists('sys_config',         'update_by', "VARCHAR(64) DEFAULT NULL COMMENT '更新者ID' AFTER `update_time`");
CALL AddColumnIfNotExists('sys_file',           'create_by', "VARCHAR(64) DEFAULT NULL COMMENT '创建者ID' AFTER `create_time`");
CALL AddColumnIfNotExists('sys_file',           'update_by', "VARCHAR(64) DEFAULT NULL COMMENT '更新者ID' AFTER `update_time`");
CALL AddColumnIfNotExists('sys_notice',         'create_by', "VARCHAR(64) DEFAULT NULL COMMENT '创建者ID' AFTER `create_time`");
CALL AddColumnIfNotExists('sys_notice',         'update_by', "VARCHAR(64) DEFAULT NULL COMMENT '更新者ID' AFTER `update_time`");
CALL AddColumnIfNotExists('sys_job',            'create_by', "VARCHAR(64) DEFAULT NULL COMMENT '创建者ID' AFTER `create_time`");
CALL AddColumnIfNotExists('sys_job',            'update_by', "VARCHAR(64) DEFAULT NULL COMMENT '更新者ID' AFTER `update_time`");
CALL AddColumnIfNotExists('sys_tenant',         'create_by', "VARCHAR(64) DEFAULT NULL COMMENT '创建者ID' AFTER `create_time`");
CALL AddColumnIfNotExists('sys_tenant',         'update_by', "VARCHAR(64) DEFAULT NULL COMMENT '更新者ID' AFTER `update_time`");
CALL AddColumnIfNotExists('sys_tenant_package', 'create_by', "VARCHAR(64) DEFAULT NULL COMMENT '创建者ID' AFTER `create_time`");
CALL AddColumnIfNotExists('sys_tenant_package', 'update_by', "VARCHAR(64) DEFAULT NULL COMMENT '更新者ID' AFTER `update_time`");

-- 清理
DROP PROCEDURE IF EXISTS AddColumnIfNotExists;
