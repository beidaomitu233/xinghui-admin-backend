-- ============================================================
-- 多租户模块 SQL 脚本
-- 创建时间: 2026-05-17
-- ============================================================

-- ----------------------------
-- 1. 租户表
-- ----------------------------
DROP TABLE IF EXISTS `sys_tenant`;
CREATE TABLE `sys_tenant` (
  `id` bigint(20) NOT NULL AUTO_INCREMENT COMMENT 'id',
  `tenant_id` varchar(20) NOT NULL COMMENT '租户编号',
  `contact_user_name` varchar(20) DEFAULT NULL COMMENT '联系人',
  `contact_phone` varchar(20) DEFAULT NULL COMMENT '联系电话',
  `company_name` varchar(30) DEFAULT NULL COMMENT '企业名称',
  `license_number` varchar(30) DEFAULT NULL COMMENT '统一社会信用代码',
  `address` varchar(200) DEFAULT NULL COMMENT '地址',
  `domain` varchar(200) DEFAULT NULL COMMENT '域名',
  `intro` varchar(200) DEFAULT NULL COMMENT '企业简介',
  `remark` varchar(200) DEFAULT NULL COMMENT '备注',
  `package_id` bigint(20) DEFAULT NULL COMMENT '租户套餐编号',
  `expire_time` datetime DEFAULT NULL COMMENT '过期时间',
  `account_count` int(11) DEFAULT -1 COMMENT '用户数量（-1不限制）',
  `status` char(1) DEFAULT '0' COMMENT '租户状态（0正常 1停用）',
  `del_flag` char(1) DEFAULT '0' COMMENT '删除标志（0代表存在 1代表删除）',
  `create_time` datetime DEFAULT CURRENT_TIMESTAMP COMMENT '创建时间',
  `update_time` datetime DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP COMMENT '更新时间',
  `is_deleted` tinyint(4) DEFAULT 0 COMMENT '逻辑删除(0存在 1删除)',
  PRIMARY KEY (`id`) USING BTREE,
  UNIQUE KEY `idx_tenant_id` (`tenant_id`) USING BTREE
) ENGINE=InnoDB AUTO_INCREMENT=1 DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci COMMENT='租户表';

-- ----------------------------
-- 初始化-租户表数据（默认租户）
-- ----------------------------
INSERT INTO `sys_tenant` VALUES (1, '000000', '管理组', '15888888888', '星辉科技', NULL, NULL, NULL, '多租户通用后台管理系统', NULL, NULL, NULL, -1, '0', '0', NOW(), NOW(), 0);

-- ----------------------------
-- 2. 租户套餐表
-- ----------------------------
DROP TABLE IF EXISTS `sys_tenant_package`;
CREATE TABLE `sys_tenant_package` (
  `package_id` bigint(20) NOT NULL AUTO_INCREMENT COMMENT '租户套餐id',
  `package_name` varchar(20) DEFAULT NULL COMMENT '套餐名称',
  `menu_ids` varchar(3000) DEFAULT NULL COMMENT '关联菜单id',
  `remark` varchar(200) DEFAULT NULL COMMENT '备注',
  `menu_check_strictly` tinyint(1) DEFAULT 1 COMMENT '菜单树选择项是否关联显示',
  `status` char(1) DEFAULT '0' COMMENT '状态（0正常 1停用）',
  `del_flag` char(1) DEFAULT '0' COMMENT '删除标志（0代表存在 1代表删除）',
  `create_time` datetime DEFAULT CURRENT_TIMESTAMP COMMENT '创建时间',
  `update_time` datetime DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP COMMENT '更新时间',
  `is_deleted` tinyint(4) DEFAULT 0 COMMENT '逻辑删除(0存在 1删除)',
  PRIMARY KEY (`package_id`) USING BTREE
) ENGINE=InnoDB AUTO_INCREMENT=1 DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci COMMENT='租户套餐表';

-- ----------------------------
-- 3. 为现有业务表添加 tenant_id 字段
-- ----------------------------

-- 用户表
ALTER TABLE `sys_user` ADD COLUMN `tenant_id` varchar(20) DEFAULT '000000' COMMENT '租户编号' AFTER `user_id`;
UPDATE `sys_user` SET `tenant_id` = '000000' WHERE `tenant_id` IS NULL;

-- 角色表
ALTER TABLE `sys_role` ADD COLUMN `tenant_id` varchar(20) DEFAULT '000000' COMMENT '租户编号' AFTER `role_id`;
UPDATE `sys_role` SET `tenant_id` = '000000' WHERE `tenant_id` IS NULL;

-- 参数配置表
ALTER TABLE `sys_config` ADD COLUMN `tenant_id` varchar(20) DEFAULT '000000' COMMENT '租户编号' AFTER `config_id`;
UPDATE `sys_config` SET `tenant_id` = '000000' WHERE `tenant_id` IS NULL;

-- 通知公告表
ALTER TABLE `sys_notice` ADD COLUMN `tenant_id` varchar(20) DEFAULT '000000' COMMENT '租户编号' AFTER `notice_id`;
UPDATE `sys_notice` SET `tenant_id` = '000000' WHERE `tenant_id` IS NULL;

-- 文件记录表
ALTER TABLE `sys_file` ADD COLUMN `tenant_id` varchar(20) DEFAULT '000000' COMMENT '租户编号' AFTER `id`;
UPDATE `sys_file` SET `tenant_id` = '000000' WHERE `tenant_id` IS NULL;

-- 定时任务表
ALTER TABLE `sys_job` ADD COLUMN `tenant_id` varchar(20) DEFAULT '000000' COMMENT '租户编号' AFTER `job_id`;
UPDATE `sys_job` SET `tenant_id` = '000000' WHERE `tenant_id` IS NULL;

-- 资讯表（如果存在）
ALTER TABLE `sys_news` ADD COLUMN `tenant_id` varchar(20) DEFAULT '000000' COMMENT '租户编号' AFTER `news_id`;
UPDATE `sys_news` SET `tenant_id` = '000000' WHERE `tenant_id` IS NULL;
