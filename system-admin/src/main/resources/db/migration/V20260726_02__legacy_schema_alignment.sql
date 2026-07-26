-- =====================================================================
-- 存量库结构对齐（条件化迁移，全新库自动跳过）
-- 背景: baseline-version=2 会让由旧版全量脚本建立的存量库跳过 V1，
-- 因而缺少 V1 修正过的 3 个列；本迁移用 information_schema 检测后补齐，
-- 同时为热点查询列 sys_oss.url 建索引（FileRecorder 按 url 查/删）。
-- 使用 PREPARE 动态执行以保证幂等（MySQL 5.7 无 ADD COLUMN IF NOT EXISTS）。
-- =====================================================================

-- 1. sys_menu.create_by（BaseEntity 审计填充需要，缺列时新增菜单必报 Unknown column）
SET @stmt = (SELECT IF(COUNT(*) = 0,
    'ALTER TABLE `sys_menu` ADD COLUMN `create_by` varchar(64) NULL DEFAULT NULL COMMENT ''创建者ID'' AFTER `create_time`',
    'SELECT 1')
    FROM information_schema.COLUMNS
    WHERE TABLE_SCHEMA = DATABASE() AND TABLE_NAME = 'sys_menu' AND COLUMN_NAME = 'create_by');
PREPARE mig FROM @stmt; EXECUTE mig; DEALLOCATE PREPARE mig;

-- 2. sys_menu.update_by
SET @stmt = (SELECT IF(COUNT(*) = 0,
    'ALTER TABLE `sys_menu` ADD COLUMN `update_by` varchar(64) NULL DEFAULT NULL COMMENT ''更新者ID'' AFTER `update_time`',
    'SELECT 1')
    FROM information_schema.COLUMNS
    WHERE TABLE_SCHEMA = DATABASE() AND TABLE_NAME = 'sys_menu' AND COLUMN_NAME = 'update_by');
PREPARE mig FROM @stmt; EXECUTE mig; DEALLOCATE PREPARE mig;

-- 3. sys_oss_config.is_deleted（全局逻辑删除字段，缺列时该表所有 MP 查询必报错）
SET @stmt = (SELECT IF(COUNT(*) = 0,
    'ALTER TABLE `sys_oss_config` ADD COLUMN `is_deleted` tinyint(4) NOT NULL DEFAULT 0 COMMENT ''逻辑删除(0存在1删除)''',
    'SELECT 1')
    FROM information_schema.COLUMNS
    WHERE TABLE_SCHEMA = DATABASE() AND TABLE_NAME = 'sys_oss_config' AND COLUMN_NAME = 'is_deleted');
PREPARE mig FROM @stmt; EXECUTE mig; DEALLOCATE PREPARE mig;

-- 4. sys_oss.url 索引（FileRecorderImpl.getByUrl/delete 按 url 过滤，无索引则每次全表扫描）
SET @stmt = (SELECT IF(COUNT(*) = 0,
    'ALTER TABLE `sys_oss` ADD INDEX `idx_oss_url` (`url`(191)) USING BTREE',
    'SELECT 1')
    FROM information_schema.STATISTICS
    WHERE TABLE_SCHEMA = DATABASE() AND TABLE_NAME = 'sys_oss' AND INDEX_NAME = 'idx_oss_url');
PREPARE mig FROM @stmt; EXECUTE mig; DEALLOCATE PREPARE mig;
