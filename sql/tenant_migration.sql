-- ============================================================
-- 多租户模块数据库迁移脚本
-- 适用于 xinghui-admin 项目
-- ============================================================

-- 1. 创建租户信息表
CREATE TABLE IF NOT EXISTS `sys_tenant` (
    `id`                BIGINT(20)   NOT NULL                   COMMENT '主键ID',
    `tenant_id`         VARCHAR(20)  DEFAULT '000000'           COMMENT '租户编号',
    `contact_user_name` VARCHAR(30)  DEFAULT ''                 COMMENT '联系人',
    `contact_phone`     VARCHAR(20)  DEFAULT ''                 COMMENT '联系电话',
    `company_name`      VARCHAR(100) DEFAULT ''                 COMMENT '企业名称',
    `address`           VARCHAR(255) DEFAULT ''                 COMMENT '企业地址',
    `license_number`    VARCHAR(50)  DEFAULT ''                 COMMENT '统一社会信用代码',
    `domain`            VARCHAR(255) DEFAULT ''                 COMMENT '域名',
    `intro`             VARCHAR(500) DEFAULT ''                 COMMENT '备注',
    `package_id`        BIGINT(20)   DEFAULT NULL               COMMENT '租户套餐ID',
    `expire_time`       DATETIME     DEFAULT NULL               COMMENT '过期时间',
    `account_count`     BIGINT(20)   DEFAULT -1                 COMMENT '用户数量（-1不限制）',
    `status`            CHAR(1)      DEFAULT '0'                COMMENT '租户状态（0正常 1停用）',
    `create_time`       DATETIME     DEFAULT CURRENT_TIMESTAMP  COMMENT '创建时间',
    `update_time`       DATETIME     DEFAULT NULL ON UPDATE CURRENT_TIMESTAMP COMMENT '更新时间',
    `is_deleted`        INT(11)      DEFAULT 0                  COMMENT '逻辑删除(0未删除 1已删除)',
    PRIMARY KEY (`id`),
    UNIQUE KEY `uk_tenant_id` (`tenant_id`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='租户信息表';

-- 2. 创建租户套餐表
CREATE TABLE IF NOT EXISTS `sys_tenant_package` (
    `package_id`   BIGINT(20)   NOT NULL                   COMMENT '套餐ID',
    `package_name` VARCHAR(100) DEFAULT ''                 COMMENT '套餐名称',
    `menu_ids`     TEXT         DEFAULT NULL               COMMENT '关联菜单ID列表（逗号分隔）',
    `remark`       VARCHAR(500) DEFAULT ''                 COMMENT '备注',
    `status`       CHAR(1)      DEFAULT '0'                COMMENT '套餐状态（0正常 1停用）',
    `create_time`  DATETIME     DEFAULT CURRENT_TIMESTAMP  COMMENT '创建时间',
    `update_time`  DATETIME     DEFAULT NULL ON UPDATE CURRENT_TIMESTAMP COMMENT '更新时间',
    `is_deleted`   INT(11)      DEFAULT 0                  COMMENT '逻辑删除(0未删除 1已删除)',
    PRIMARY KEY (`package_id`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='租户套餐表';

-- 3. 为各业务表添加 tenant_id 字段（幂等：检查后添加）
DELIMITER $$
DROP PROCEDURE IF EXISTS AddTenantColumns$$
CREATE PROCEDURE AddTenantColumns()
BEGIN
    IF NOT EXISTS (SELECT 1 FROM information_schema.COLUMNS WHERE TABLE_SCHEMA=DATABASE() AND TABLE_NAME='sys_user'   AND COLUMN_NAME='tenant_id') THEN ALTER TABLE `sys_user`   ADD COLUMN `tenant_id` VARCHAR(20) DEFAULT '000000' COMMENT '租户编号' AFTER `user_id`; END IF;
    IF NOT EXISTS (SELECT 1 FROM information_schema.COLUMNS WHERE TABLE_SCHEMA=DATABASE() AND TABLE_NAME='sys_role'   AND COLUMN_NAME='tenant_id') THEN ALTER TABLE `sys_role`   ADD COLUMN `tenant_id` VARCHAR(20) DEFAULT '000000' COMMENT '租户编号' AFTER `role_id`; END IF;
    IF NOT EXISTS (SELECT 1 FROM information_schema.COLUMNS WHERE TABLE_SCHEMA=DATABASE() AND TABLE_NAME='sys_config' AND COLUMN_NAME='tenant_id') THEN ALTER TABLE `sys_config` ADD COLUMN `tenant_id` VARCHAR(20) DEFAULT '000000' COMMENT '租户编号' AFTER `config_id`; END IF;
    IF NOT EXISTS (SELECT 1 FROM information_schema.COLUMNS WHERE TABLE_SCHEMA=DATABASE() AND TABLE_NAME='sys_file'   AND COLUMN_NAME='tenant_id') THEN ALTER TABLE `sys_file`   ADD COLUMN `tenant_id` VARCHAR(20) DEFAULT '000000' COMMENT '租户编号' AFTER `id`; END IF;
    IF NOT EXISTS (SELECT 1 FROM information_schema.COLUMNS WHERE TABLE_SCHEMA=DATABASE() AND TABLE_NAME='sys_notice' AND COLUMN_NAME='tenant_id') THEN ALTER TABLE `sys_notice` ADD COLUMN `tenant_id` VARCHAR(20) DEFAULT '000000' COMMENT '租户编号' AFTER `notice_id`; END IF;
    IF NOT EXISTS (SELECT 1 FROM information_schema.COLUMNS WHERE TABLE_SCHEMA=DATABASE() AND TABLE_NAME='sys_job'    AND COLUMN_NAME='tenant_id') THEN ALTER TABLE `sys_job`    ADD COLUMN `tenant_id` VARCHAR(20) DEFAULT '000000' COMMENT '租户编号' AFTER `job_id`; END IF;
END$$
DELIMITER ;
CALL AddTenantColumns();
DROP PROCEDURE IF EXISTS AddTenantColumns;

-- 4. 为 tenant_id 添加索引（幂等：检查后添加）
DELIMITER $$
DROP PROCEDURE IF EXISTS AddTenantIndexes$$
CREATE PROCEDURE AddTenantIndexes()
BEGIN
    IF NOT EXISTS (SELECT 1 FROM information_schema.STATISTICS WHERE TABLE_SCHEMA=DATABASE() AND TABLE_NAME='sys_user'   AND INDEX_NAME='idx_tenant_id') THEN ALTER TABLE `sys_user`   ADD INDEX `idx_tenant_id` (`tenant_id`); END IF;
    IF NOT EXISTS (SELECT 1 FROM information_schema.STATISTICS WHERE TABLE_SCHEMA=DATABASE() AND TABLE_NAME='sys_role'   AND INDEX_NAME='idx_tenant_id') THEN ALTER TABLE `sys_role`   ADD INDEX `idx_tenant_id` (`tenant_id`); END IF;
    IF NOT EXISTS (SELECT 1 FROM information_schema.STATISTICS WHERE TABLE_SCHEMA=DATABASE() AND TABLE_NAME='sys_config' AND INDEX_NAME='idx_tenant_id') THEN ALTER TABLE `sys_config` ADD INDEX `idx_tenant_id` (`tenant_id`); END IF;
    IF NOT EXISTS (SELECT 1 FROM information_schema.STATISTICS WHERE TABLE_SCHEMA=DATABASE() AND TABLE_NAME='sys_file'   AND INDEX_NAME='idx_tenant_id') THEN ALTER TABLE `sys_file`   ADD INDEX `idx_tenant_id` (`tenant_id`); END IF;
    IF NOT EXISTS (SELECT 1 FROM information_schema.STATISTICS WHERE TABLE_SCHEMA=DATABASE() AND TABLE_NAME='sys_notice' AND INDEX_NAME='idx_tenant_id') THEN ALTER TABLE `sys_notice` ADD INDEX `idx_tenant_id` (`tenant_id`); END IF;
    IF NOT EXISTS (SELECT 1 FROM information_schema.STATISTICS WHERE TABLE_SCHEMA=DATABASE() AND TABLE_NAME='sys_job'    AND INDEX_NAME='idx_tenant_id') THEN ALTER TABLE `sys_job`    ADD INDEX `idx_tenant_id` (`tenant_id`); END IF;
END$$
DELIMITER ;
CALL AddTenantIndexes();
DROP PROCEDURE IF EXISTS AddTenantIndexes;

-- 5. 插入默认租户数据
INSERT INTO `sys_tenant` (`id`, `tenant_id`, `company_name`, `status`, `account_count`)
VALUES (1, '000000', '默认租户', '0', -1);

-- 6. 插入默认套餐数据
INSERT INTO `sys_tenant_package` (`package_id`, `package_name`, `menu_ids`, `status`)
VALUES (1, '默认套餐', NULL, '0');
