-- ============================================================
-- 审计字段迁移脚本
-- 为业务表添加 create_by、update_by 字段
-- ============================================================

-- 用户表
ALTER TABLE `sys_user` ADD COLUMN `create_by` VARCHAR(64) DEFAULT NULL COMMENT '创建者ID' AFTER `create_time`;
ALTER TABLE `sys_user` ADD COLUMN `update_by` VARCHAR(64) DEFAULT NULL COMMENT '更新者ID' AFTER `update_time`;

-- 角色表
ALTER TABLE `sys_role` ADD COLUMN `create_by` VARCHAR(64) DEFAULT NULL COMMENT '创建者ID' AFTER `create_time`;
ALTER TABLE `sys_role` ADD COLUMN `update_by` VARCHAR(64) DEFAULT NULL COMMENT '更新者ID' AFTER `update_time`;

-- 参数配置表
ALTER TABLE `sys_config` ADD COLUMN `create_by` VARCHAR(64) DEFAULT NULL COMMENT '创建者ID' AFTER `create_time`;
ALTER TABLE `sys_config` ADD COLUMN `update_by` VARCHAR(64) DEFAULT NULL COMMENT '更新者ID' AFTER `update_time`;

-- 文件表
ALTER TABLE `sys_file` ADD COLUMN `create_by` VARCHAR(64) DEFAULT NULL COMMENT '创建者ID' AFTER `create_time`;
ALTER TABLE `sys_file` ADD COLUMN `update_by` VARCHAR(64) DEFAULT NULL COMMENT '更新者ID' AFTER `update_time`;

-- 通知公告表
ALTER TABLE `sys_notice` ADD COLUMN `create_by` VARCHAR(64) DEFAULT NULL COMMENT '创建者ID' AFTER `create_time`;
ALTER TABLE `sys_notice` ADD COLUMN `update_by` VARCHAR(64) DEFAULT NULL COMMENT '更新者ID' AFTER `update_time`;

-- 定时任务表
ALTER TABLE `sys_job` ADD COLUMN `create_by` VARCHAR(64) DEFAULT NULL COMMENT '创建者ID' AFTER `create_time`;
ALTER TABLE `sys_job` ADD COLUMN `update_by` VARCHAR(64) DEFAULT NULL COMMENT '更新者ID' AFTER `update_time`;

-- 租户表
ALTER TABLE `sys_tenant` ADD COLUMN `create_by` VARCHAR(64) DEFAULT NULL COMMENT '创建者ID' AFTER `create_time`;
ALTER TABLE `sys_tenant` ADD COLUMN `update_by` VARCHAR(64) DEFAULT NULL COMMENT '更新者ID' AFTER `update_time`;

-- 租户套餐表
ALTER TABLE `sys_tenant_package` ADD COLUMN `create_by` VARCHAR(64) DEFAULT NULL COMMENT '创建者ID' AFTER `create_time`;
ALTER TABLE `sys_tenant_package` ADD COLUMN `update_by` VARCHAR(64) DEFAULT NULL COMMENT '更新者ID' AFTER `update_time`;
