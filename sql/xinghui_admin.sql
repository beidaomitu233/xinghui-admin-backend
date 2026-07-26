/*
 Navicat Premium Dump SQL

 Source Server         : xinghui_admin
 Source Server Type    : MySQL
 Source Server Version : 80012 (8.0.12)
 Source Host           : localhost:3306
 Source Schema         : xinghui_admin

 Target Server Type    : MySQL
 Target Server Version : 80012 (8.0.12)
 File Encoding         : 65001

 Date: 21/05/2026 01:20:29
*/

SET NAMES utf8mb4;
SET FOREIGN_KEY_CHECKS = 0;

-- ----------------------------
-- Table structure for sys_config
-- ----------------------------
DROP TABLE IF EXISTS `sys_config`;
CREATE TABLE `sys_config`  (
  `config_id` int(11) NOT NULL AUTO_INCREMENT COMMENT '参数主键',
  `tenant_id` varchar(20) CHARACTER SET utf8mb4 COLLATE utf8mb4_0900_ai_ci NULL DEFAULT '000000' COMMENT '租户编号',
  `config_name` varchar(100) CHARACTER SET utf8mb4 COLLATE utf8mb4_0900_ai_ci NULL DEFAULT '' COMMENT '参数名称',
  `config_key` varchar(100) CHARACTER SET utf8mb4 COLLATE utf8mb4_0900_ai_ci NULL DEFAULT '' COMMENT '参数键名(sys.account.captchaOn)',
  `config_value` varchar(500) CHARACTER SET utf8mb4 COLLATE utf8mb4_0900_ai_ci NULL DEFAULT '' COMMENT '参数键值(true/false)',
  `config_type` char(1) CHARACTER SET utf8mb4 COLLATE utf8mb4_0900_ai_ci NULL DEFAULT 'N' COMMENT '系统内置（Y是 N否）',
  `remark` varchar(500) CHARACTER SET utf8mb4 COLLATE utf8mb4_0900_ai_ci NULL DEFAULT NULL COMMENT '备注',
  `create_time` datetime NULL DEFAULT CURRENT_TIMESTAMP,
  `create_by` varchar(64) CHARACTER SET utf8mb4 COLLATE utf8mb4_0900_ai_ci NULL DEFAULT NULL COMMENT '创建者ID',
  `update_time` datetime NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
  `update_by` varchar(64) CHARACTER SET utf8mb4 COLLATE utf8mb4_0900_ai_ci NULL DEFAULT NULL COMMENT '更新者ID',
  `is_deleted` tinyint(4) NULL DEFAULT 0 COMMENT '逻辑删除(0存在 1删除)',
  PRIMARY KEY (`config_id`) USING BTREE,
  UNIQUE INDEX `idx_config_key`(`config_key` ASC) USING BTREE,
  INDEX `idx_tenant_id`(`tenant_id` ASC) USING BTREE
) ENGINE = InnoDB AUTO_INCREMENT = 6 CHARACTER SET = utf8mb4 COLLATE = utf8mb4_0900_ai_ci COMMENT = '参数配置表' ROW_FORMAT = Dynamic;

-- ----------------------------
-- Records of sys_config
-- ----------------------------
INSERT INTO `sys_config` VALUES (1, '000000', '主框架页-默认皮肤样式名称', 'sys.index.skinName', 'skin-blue', 'Y', '蓝色 skin-blue、绿色 skin-green、紫色 skin-purple、红色 skin-red、黄色 skin-yellow', '2025-12-25 22:36:47', NULL, '2025-12-25 22:36:47', NULL, 0);
INSERT INTO `sys_config` VALUES (2, '000000', '用户管理-账号初始密码', 'sys.user.initPassword', '123456', 'Y', '初始化密码 123456', '2025-12-25 22:36:47', NULL, '2025-12-25 22:36:47', NULL, 0);
INSERT INTO `sys_config` VALUES (3, '000000', '主框架页-侧边栏主题', 'sys.index.sideTheme', 'theme-dark', 'Y', '深色主题theme-dark，浅色主题theme-light', '2025-12-25 22:36:47', NULL, '2025-12-25 22:36:47', NULL, 0);
INSERT INTO `sys_config` VALUES (4, '000000', '账号自助-验证码开关', 'sys.account.captchaOnOff', 'true', 'Y', '是否开启验证码功能（true开启，false关闭）', '2025-12-25 22:36:47', NULL, '2025-12-25 22:36:47', NULL, 0);
INSERT INTO `sys_config` VALUES (5, '000000', '账号自助-是否开启用户注册功能', 'sys.account.registerUser', 'false', 'Y', '是否开启注册用户功能（true开启，false关闭）', '2025-12-25 22:36:47', NULL, '2025-12-25 22:36:47', NULL, 0);

-- ----------------------------
-- Table structure for sys_download
-- ----------------------------
DROP TABLE IF EXISTS `sys_download`;
CREATE TABLE `sys_download`  (
  `id` bigint(20) NOT NULL AUTO_INCREMENT COMMENT 'ID',
  `name` varchar(255) CHARACTER SET utf8mb4 COLLATE utf8mb4_0900_ai_ci NOT NULL COMMENT '软件名称',
  `version` varchar(50) CHARACTER SET utf8mb4 COLLATE utf8mb4_0900_ai_ci NOT NULL COMMENT '版本号',
  `platform` varchar(50) CHARACTER SET utf8mb4 COLLATE utf8mb4_0900_ai_ci NOT NULL COMMENT '平台(windows/macos/linux)',
  `file_url` varchar(500) CHARACTER SET utf8mb4 COLLATE utf8mb4_0900_ai_ci NOT NULL COMMENT '下载地址',
  `file_size` bigint(20) NULL DEFAULT NULL COMMENT '文件大小(字节)',
  `description` varchar(1000) CHARACTER SET utf8mb4 COLLATE utf8mb4_0900_ai_ci NULL DEFAULT NULL COMMENT '描述',
  `changelog` text CHARACTER SET utf8mb4 COLLATE utf8mb4_0900_ai_ci NULL COMMENT '更新日志',
  `status` char(1) CHARACTER SET utf8mb4 COLLATE utf8mb4_0900_ai_ci NULL DEFAULT '0' COMMENT '状态(0启用 1禁用)',
  `download_count` int(11) NULL DEFAULT 0 COMMENT '下载次数',
  `create_time` datetime NULL DEFAULT CURRENT_TIMESTAMP COMMENT '创建时间',
  `update_time` datetime NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP COMMENT '更新时间',
  PRIMARY KEY (`id`) USING BTREE,
  INDEX `idx_platform`(`platform` ASC) USING BTREE,
  INDEX `idx_status`(`status` ASC) USING BTREE
) ENGINE = InnoDB AUTO_INCREMENT = 2052733576420126722 CHARACTER SET = utf8mb4 COLLATE = utf8mb4_0900_ai_ci COMMENT = '下载管理表' ROW_FORMAT = Dynamic;

-- ----------------------------
-- Records of sys_download
-- ----------------------------
INSERT INTO `sys_download` VALUES (1, 'MinerU Desktop', 'v2.5.0', 'windows', 'https://github.com/opendatalab/MinerU/releases/download/v2.5.0/MinerU-Setup-2.5.0.exe', 268435456, '支持 Windows 10/11 x64', '新增化学分子式识别支持\n优化大文档处理性能，速度提升 40%\n支持 MCP 协议，适配主流 Agent 框架', '0', 2, '2026-05-08 20:11:37', '2026-05-08 20:11:37');
INSERT INTO `sys_download` VALUES (2, 'MinerU Desktop', 'v2.5.0', 'macos', 'https://github.com/opendatalab/MinerU/releases/download/v2.5.0/MinerU-2.5.0.dmg', 293601280, '支持 macOS 12+ (Intel/Apple Silicon)', '新增化学分子式识别支持\n优化大文档处理性能，速度提升 40%\n支持 MCP 协议，适配主流 Agent 框架', '0', 0, '2026-05-08 20:11:37', '2026-05-08 20:11:37');
INSERT INTO `sys_download` VALUES (3, 'MinerU Desktop', 'v2.5.0', 'linux', 'https://github.com/opendatalab/MinerU/releases/download/v2.5.0/MinerU-2.5.0.AppImage', 251658240, '支持 Ubuntu 20.04+ / CentOS 7+', '新增化学分子式识别支持\n优化大文档处理性能，速度提升 40%\n支持 MCP 协议，适配主流 Agent 框架', '0', 0, '2026-05-08 20:11:37', '2026-05-08 20:11:37');

-- ----------------------------
-- Table structure for sys_file
-- ----------------------------
DROP TABLE IF EXISTS `sys_file`;
CREATE TABLE `sys_file`  (
  `id` bigint(20) NOT NULL AUTO_INCREMENT COMMENT '文件id',
  `tenant_id` varchar(20) CHARACTER SET utf8mb4 COLLATE utf8mb4_0900_ai_ci NULL DEFAULT '000000' COMMENT '租户编号',
  `url` varchar(500) CHARACTER SET utf8mb4 COLLATE utf8mb4_0900_ai_ci NOT NULL COMMENT '文件访问地址',
  `size` bigint(20) NULL DEFAULT 0 COMMENT '文件大小(字节)',
  `filename` varchar(255) CHARACTER SET utf8mb4 COLLATE utf8mb4_0900_ai_ci NULL DEFAULT '' COMMENT '原始文件名',
  `original_name` varchar(255) CHARACTER SET utf8mb4 COLLATE utf8mb4_0900_ai_ci NULL DEFAULT '' COMMENT '存储文件名',
  `base_path` varchar(255) CHARACTER SET utf8mb4 COLLATE utf8mb4_0900_ai_ci NULL DEFAULT '' COMMENT '基础存储路径',
  `path` varchar(255) CHARACTER SET utf8mb4 COLLATE utf8mb4_0900_ai_ci NULL DEFAULT '' COMMENT '存储路径',
  `ext` varchar(32) CHARACTER SET utf8mb4 COLLATE utf8mb4_0900_ai_ci NULL DEFAULT '' COMMENT '文件扩展名',
  `platform` varchar(32) CHARACTER SET utf8mb4 COLLATE utf8mb4_0900_ai_ci NOT NULL COMMENT '存储平台(minio-local, aliyun-oss)',
  `th_url` varchar(500) CHARACTER SET utf8mb4 COLLATE utf8mb4_0900_ai_ci NULL DEFAULT '' COMMENT '缩略图访问路径',
  `th_filename` varchar(255) CHARACTER SET utf8mb4 COLLATE utf8mb4_0900_ai_ci NULL DEFAULT '' COMMENT '缩略图名称',
  `th_size` bigint(20) NULL DEFAULT 0 COMMENT '缩略图大小',
  `object_id` varchar(32) CHARACTER SET utf8mb4 COLLATE utf8mb4_0900_ai_ci NULL DEFAULT '' COMMENT '文件对象ID(可选)',
  `create_time` datetime NULL DEFAULT CURRENT_TIMESTAMP,
  `create_by` varchar(64) CHARACTER SET utf8mb4 COLLATE utf8mb4_0900_ai_ci NULL DEFAULT NULL COMMENT '创建者ID',
  `update_time` datetime NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP COMMENT '更新时间',
  `update_by` varchar(64) CHARACTER SET utf8mb4 COLLATE utf8mb4_0900_ai_ci NULL DEFAULT NULL COMMENT '更新者ID',
  `is_deleted` tinyint(4) NULL DEFAULT 0 COMMENT '逻辑删除(0存在 1删除)',
  PRIMARY KEY (`id`) USING BTREE,
  INDEX `idx_tenant_id`(`tenant_id` ASC) USING BTREE
) ENGINE = InnoDB AUTO_INCREMENT = 1779132000598 CHARACTER SET = utf8mb4 COLLATE = utf8mb4_0900_ai_ci COMMENT = '文件记录表' ROW_FORMAT = Dynamic;

-- ----------------------------
-- Records of sys_file
-- ----------------------------
INSERT INTO `sys_file` VALUES (1769720133586, '000000', 'http://127.0.0.1:7799/file/upload/upload/697bc945db74e777a8d235c7.pdf', 28320434, '697bc945db74e777a8d235c7.pdf', '《2025年“人工智能+”行业标杆案例荟萃》.pdf', 'upload/', 'upload/', 'pdf', 'local-plus-1', '', '', 0, '', '2026-01-30 04:55:34', NULL, '2026-01-30 04:55:33', NULL, 0);
INSERT INTO `sys_file` VALUES (1779129513293, '000000', 'http://192.168.2.101:9000/xinghui-admin/upload/upload/2026/05/19/fcb94b14ed0f4dffbadcf986b3c51b50.txt', 43, 'fcb94b14ed0f4dffbadcf986b3c51b50.txt', '', 'upload/', 'upload/2026/05/19/', '', 'minio-1', '', '', 0, '', '2026-05-19 02:38:33', '-1', '2026-05-19 02:38:33', '-1', 0);
INSERT INTO `sys_file` VALUES (1779129791200, '000000', 'http://192.168.2.101:9000/xinghui-admin/upload/upload/2026/05/19/ef6b3defd0dc48df81de407e17915968.txt', 43, 'ef6b3defd0dc48df81de407e17915968.txt', '', 'upload/', 'upload/2026/05/19/', '', 'minio-1', '', '', 0, '', '2026-05-19 02:43:11', '-1', '2026-05-19 02:43:11', '-1', 0);
INSERT INTO `sys_file` VALUES (1779129812363, '000000', 'http://192.168.2.101:9000/xinghui-admin/upload/upload/2026/05/19/f26f6c43f1b746f4a3afa2fd53e56848.txt', 43, 'f26f6c43f1b746f4a3afa2fd53e56848.txt', '', 'upload/', 'upload/2026/05/19/', '', 'minio-1', '', '', 0, '', '2026-05-19 02:43:32', '-1', '2026-05-19 02:43:32', '-1', 0);
INSERT INTO `sys_file` VALUES (1779129813111, '000000', 'http://192.168.2.101:9000/xinghui-admin/upload/upload/2026/05/19/1c5a793f94484cb5a7cf3af5f14339c5.txt', 43, '1c5a793f94484cb5a7cf3af5f14339c5.txt', '', 'upload/', 'upload/2026/05/19/', '', 'minio-1', '', '', 0, '', '2026-05-19 02:43:33', '-1', '2026-05-19 02:43:33', '-1', 0);
INSERT INTO `sys_file` VALUES (1779129895935, '000000', 'http://192.168.2.101:9000/xinghui-admin/upload/upload/2026/05/19/bd38a70fe5a7444ea4063d96cabba42d.txt', 43, 'bd38a70fe5a7444ea4063d96cabba42d.txt', '', 'upload/', 'upload/2026/05/19/', '', 'minio-1', '', '', 0, '', '2026-05-19 02:44:56', '-1', '2026-05-19 02:44:56', '-1', 0);
INSERT INTO `sys_file` VALUES (1779129896428, '000000', 'http://192.168.2.101:9000/xinghui-admin/upload/upload/2026/05/19/e5ac697437d2477e96e7d99597aa81df.txt', 43, 'e5ac697437d2477e96e7d99597aa81df.txt', '', 'upload/', 'upload/2026/05/19/', '', 'minio-1', '', '', 0, '', '2026-05-19 02:44:56', '-1', '2026-05-19 02:44:56', '-1', 0);
INSERT INTO `sys_file` VALUES (1779130178667, '000000', 'http://192.168.2.101:9000upload/2026/05/19/ed161960a93a4a0c8c493afb48a7a578.txt', 43, 'ed161960a93a4a0c8c493afb48a7a578.txt', '', '', 'upload/2026/05/19/', '', 'minio-1', '', '', 0, '', '2026-05-19 02:49:38', '-1', '2026-05-19 02:49:39', '-1', 0);
INSERT INTO `sys_file` VALUES (1779130179155, '000000', 'http://192.168.2.101:9000upload/2026/05/19/36d9d553807b4d3ebdd5154796b717b1.txt', 43, '36d9d553807b4d3ebdd5154796b717b1.txt', '', '', 'upload/2026/05/19/', '', 'minio-1', '', '', 0, '', '2026-05-19 02:49:39', '-1', '2026-05-19 02:49:39', '-1', 0);
INSERT INTO `sys_file` VALUES (1779130212150, '000000', 'http://192.168.2.101:9000upload/2026/05/19/d5841df6fc604b8d954fcda3a68af1f8.txt', 43, 'd5841df6fc604b8d954fcda3a68af1f8.txt', '', '', 'upload/2026/05/19/', '', 'minio-1', '', '', 0, '', '2026-05-19 02:50:12', '-1', '2026-05-19 02:50:12', '-1', 0);
INSERT INTO `sys_file` VALUES (1779130490145, '000000', 'upload/2026/05/19/ce4abf29c3c34f26884ba6b017d5cdd1.txt', 43, 'ce4abf29c3c34f26884ba6b017d5cdd1.txt', '', '', 'upload/2026/05/19/', '', 'minio-1', '', '', 0, '', '2026-05-19 02:54:50', '-1', '2026-05-19 02:54:50', '-1', 0);
INSERT INTO `sys_file` VALUES (1779130490647, '000000', 'upload/2026/05/19/c0769578a2304f4e95899f60647f5420.txt', 43, 'c0769578a2304f4e95899f60647f5420.txt', '', '', 'upload/2026/05/19/', '', 'minio-1', '', '', 0, '', '2026-05-19 02:54:51', '-1', '2026-05-19 02:54:51', '-1', 0);
INSERT INTO `sys_file` VALUES (1779130808596, '000000', 'upload/2026/05/19/6e9cc5d01b3d40a2901061a7b6f76acc.txt', 43, '6e9cc5d01b3d40a2901061a7b6f76acc.txt', '', 'upload/', '2026/05/19/', '', 'minio-1', '', '', 0, '', '2026-05-19 03:00:08', '-1', '2026-05-19 03:00:09', '-1', 0);
INSERT INTO `sys_file` VALUES (1779130809143, '000000', 'upload/2026/05/19/179c9cedffec439e92229f75f4aa57b4.txt', 43, '179c9cedffec439e92229f75f4aa57b4.txt', '', 'upload/', '2026/05/19/', '', 'minio-1', '', '', 0, '', '2026-05-19 03:00:09', '-1', '2026-05-19 03:00:09', '-1', 0);
INSERT INTO `sys_file` VALUES (1779131268443, '000000', 'upload/2026/05/19/bff96744028947188797d6762d6777e8.txt', 43, 'bff96744028947188797d6762d6777e8.txt', '', 'upload/', '2026/05/19/', '', 'minio-1', '', '', 0, '', '2026-05-19 03:07:48', '-1', '2026-05-19 03:07:48', '-1', 0);
INSERT INTO `sys_file` VALUES (1779131268574, '000000', 'upload/2026/05/19/6d4e94fc2ab545949823e5e0f2f07051.txt', 43, '6d4e94fc2ab545949823e5e0f2f07051.txt', '', 'upload/', '2026/05/19/', '', 'minio-1', '', '', 0, '', '2026-05-19 03:07:49', '-1', '2026-05-19 03:07:49', '-1', 0);
INSERT INTO `sys_file` VALUES (1779131461821, '000000', 'upload/2026/05/19/c8b4f665aab848408f5978c9a367d4d7.txt', 43, 'c8b4f665aab848408f5978c9a367d4d7.txt', '', 'upload/', '2026/05/19/', '', 'minio-1', '', '', 0, '', '2026-05-19 03:11:02', '-1', '2026-05-19 03:11:02', '-1', 0);
INSERT INTO `sys_file` VALUES (1779131461955, '000000', 'upload/2026/05/19/e028603ecce34c24b090215de324bd29.txt', 43, 'e028603ecce34c24b090215de324bd29.txt', '', 'upload/', '2026/05/19/', '', 'minio-1', '', '', 0, '', '2026-05-19 03:11:02', '-1', '2026-05-19 03:11:02', '-1', 0);
INSERT INTO `sys_file` VALUES (1779131527794, '000000', 'upload/2026/05/19/3e40a7d620094f2ba4306f511b03130b.txt', 43, '3e40a7d620094f2ba4306f511b03130b.txt', '', 'upload/', '2026/05/19/', '', 'minio-1', '', '', 0, '', '2026-05-19 03:12:07', '-1', '2026-05-19 03:12:08', '-1', 0);
INSERT INTO `sys_file` VALUES (1779131527935, '000000', 'upload/2026/05/19/b7ad2c8d9a1c468289338d30627a25e6.txt', 43, 'b7ad2c8d9a1c468289338d30627a25e6.txt', '', 'upload/', '2026/05/19/', '', 'minio-1', '', '', 0, '', '2026-05-19 03:12:08', '-1', '2026-05-19 03:12:08', '-1', 0);
INSERT INTO `sys_file` VALUES (1779131886842, '000000', 'upload/2026/05/19/dde0c11c47914bb1bb5f7977526396bb.txt', 43, 'dde0c11c47914bb1bb5f7977526396bb.txt', '', 'upload/', '2026/05/19/', '', 'minio-1', '', '', 0, '', '2026-05-19 03:18:06', '-1', '2026-05-19 03:18:07', '-1', 0);
INSERT INTO `sys_file` VALUES (1779131886962, '000000', 'upload/2026/05/19/422ecc10d9544b52a55e53be98b4ff55.txt', 43, '422ecc10d9544b52a55e53be98b4ff55.txt', '', 'upload/', '2026/05/19/', '', 'minio-1', '', '', 0, '', '2026-05-19 03:18:07', '-1', '2026-05-19 03:18:07', '-1', 0);
INSERT INTO `sys_file` VALUES (1779132000501, '000000', 'upload/2026/05/19/b6f4293651fd404c93370dd1d04333ac.txt', 43, 'b6f4293651fd404c93370dd1d04333ac.txt', '', 'upload/', '2026/05/19/', '', 'minio-1', '', '', 0, '', '2026-05-19 03:20:00', '-1', '2026-05-19 03:20:01', '-1', 0);
INSERT INTO `sys_file` VALUES (1779132000597, '000000', 'upload/2026/05/19/61a720f8e4d94e4cb0fd5d09433eb967.txt', 43, '61a720f8e4d94e4cb0fd5d09433eb967.txt', '', 'upload/', '2026/05/19/', '', 'minio-1', '', '', 0, '', '2026-05-19 03:20:01', '-1', '2026-05-19 03:20:01', '-1', 0);

-- ----------------------------
-- Table structure for sys_file_part
-- ----------------------------
DROP TABLE IF EXISTS `sys_file_part`;
CREATE TABLE `sys_file_part`  (
  `id` varchar(64) CHARACTER SET utf8mb4 COLLATE utf8mb4_0900_ai_ci NOT NULL COMMENT '分片ID',
  `platform` varchar(50) CHARACTER SET utf8mb4 COLLATE utf8mb4_0900_ai_ci NOT NULL COMMENT '存储平台',
  `upload_id` varchar(255) CHARACTER SET utf8mb4 COLLATE utf8mb4_0900_ai_ci NOT NULL COMMENT '上传ID',
  `e_tag` varchar(255) CHARACTER SET utf8mb4 COLLATE utf8mb4_0900_ai_ci NULL DEFAULT NULL COMMENT 'ETag',
  `part_number` int(11) NOT NULL COMMENT '分片号',
  `part_size` bigint(20) NULL DEFAULT 0 COMMENT '分片大小',
  `hash` varchar(255) CHARACTER SET utf8mb4 COLLATE utf8mb4_0900_ai_ci NULL DEFAULT NULL COMMENT 'Hash值',
  `create_time` datetime NULL DEFAULT CURRENT_TIMESTAMP COMMENT '创建时间',
  PRIMARY KEY (`id`) USING BTREE
) ENGINE = InnoDB CHARACTER SET = utf8mb4 COLLATE = utf8mb4_0900_ai_ci COMMENT = '文件分片信息表' ROW_FORMAT = Dynamic;

-- ----------------------------
-- Records of sys_file_part
-- ----------------------------

-- ----------------------------
-- Table structure for sys_job
-- ----------------------------
DROP TABLE IF EXISTS `sys_job`;
CREATE TABLE `sys_job`  (
  `job_id` bigint(20) NOT NULL AUTO_INCREMENT COMMENT '任务ID',
  `tenant_id` varchar(20) CHARACTER SET utf8mb4 COLLATE utf8mb4_0900_ai_ci NULL DEFAULT '000000' COMMENT '租户编号',
  `job_name` varchar(64) CHARACTER SET utf8mb4 COLLATE utf8mb4_0900_ai_ci NOT NULL COMMENT '任务名称',
  `job_group` varchar(64) CHARACTER SET utf8mb4 COLLATE utf8mb4_0900_ai_ci NOT NULL DEFAULT 'DEFAULT' COMMENT '任务组名',
  `invoke_target` varchar(500) CHARACTER SET utf8mb4 COLLATE utf8mb4_0900_ai_ci NOT NULL COMMENT '调用目标字符串',
  `cron_expression` varchar(255) CHARACTER SET utf8mb4 COLLATE utf8mb4_0900_ai_ci NULL DEFAULT '' COMMENT 'cron执行表达式',
  `misfire_policy` varchar(20) CHARACTER SET utf8mb4 COLLATE utf8mb4_0900_ai_ci NULL DEFAULT '3' COMMENT '计划执行错误策略（1立即执行 2执行一次 3放弃执行）',
  `concurrent` char(1) CHARACTER SET utf8mb4 COLLATE utf8mb4_0900_ai_ci NULL DEFAULT '1' COMMENT '是否并发执行（0允许 1禁止）',
  `status` char(1) CHARACTER SET utf8mb4 COLLATE utf8mb4_0900_ai_ci NULL DEFAULT '0' COMMENT '状态（0正常 1暂停）',
  `create_time` datetime NULL DEFAULT CURRENT_TIMESTAMP,
  `create_by` varchar(64) CHARACTER SET utf8mb4 COLLATE utf8mb4_0900_ai_ci NULL DEFAULT NULL COMMENT '创建者ID',
  `update_time` datetime NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
  `update_by` varchar(64) CHARACTER SET utf8mb4 COLLATE utf8mb4_0900_ai_ci NULL DEFAULT NULL COMMENT '更新者ID',
  `is_deleted` tinyint(4) NULL DEFAULT 0 COMMENT '逻辑删除(0存在 1删除)',
  `remark` varchar(500) CHARACTER SET utf8mb4 COLLATE utf8mb4_0900_ai_ci NULL DEFAULT '' COMMENT '备注信息',
  PRIMARY KEY (`job_id`, `job_name`, `job_group`) USING BTREE,
  INDEX `idx_tenant_id`(`tenant_id` ASC) USING BTREE
) ENGINE = InnoDB AUTO_INCREMENT = 3 CHARACTER SET = utf8mb4 COLLATE = utf8mb4_0900_ai_ci COMMENT = '定时任务调度表' ROW_FORMAT = Dynamic;

-- ----------------------------
-- Records of sys_job
-- ----------------------------
INSERT INTO `sys_job` VALUES (1, '000000', '数据库备份', 'DEFAULT', 'dbBackupTask.execute', '0 0 2 * * ?', '3', '1', '1', '2025-12-25 22:36:47', NULL, '2025-12-25 22:36:47', NULL, 0, '每天凌晨2点执行数据库备份');
INSERT INTO `sys_job` VALUES (2, '000000', 'AI健康检测', 'DEFAULT', 'aiHealthTask.check', '0 */10 * * * ?', '3', '1', '0', '2025-12-25 22:36:47', NULL, '2025-12-25 22:36:47', NULL, 0, '每10分钟检测AI服务健康状态');

-- ----------------------------
-- Table structure for sys_job_log
-- ----------------------------
DROP TABLE IF EXISTS `sys_job_log`;
CREATE TABLE `sys_job_log`  (
  `job_log_id` bigint(20) NOT NULL AUTO_INCREMENT COMMENT '任务日志ID',
  `job_name` varchar(64) CHARACTER SET utf8mb4 COLLATE utf8mb4_0900_ai_ci NOT NULL COMMENT '任务名称',
  `job_group` varchar(64) CHARACTER SET utf8mb4 COLLATE utf8mb4_0900_ai_ci NOT NULL COMMENT '任务组名',
  `invoke_target` varchar(500) CHARACTER SET utf8mb4 COLLATE utf8mb4_0900_ai_ci NOT NULL COMMENT '调用目标字符串',
  `status` char(1) CHARACTER SET utf8mb4 COLLATE utf8mb4_0900_ai_ci NULL DEFAULT '0' COMMENT '执行状态（0正常 1失败）',
  `exception_info` varchar(2000) CHARACTER SET utf8mb4 COLLATE utf8mb4_0900_ai_ci NULL DEFAULT '' COMMENT '异常信息',
  `create_time` datetime NULL DEFAULT CURRENT_TIMESTAMP COMMENT '执行时间',
  `cost_time` bigint(20) NULL DEFAULT 0 COMMENT '耗时(毫秒)',
  PRIMARY KEY (`job_log_id`) USING BTREE
) ENGINE = InnoDB CHARACTER SET = utf8mb4 COLLATE = utf8mb4_0900_ai_ci COMMENT = '定时任务调度日志表' ROW_FORMAT = Dynamic;

-- ----------------------------
-- Records of sys_job_log
-- ----------------------------

-- ----------------------------
-- Table structure for sys_menu
-- ----------------------------
DROP TABLE IF EXISTS `sys_menu`;
CREATE TABLE `sys_menu`  (
  `menu_id` bigint(20) NOT NULL AUTO_INCREMENT COMMENT '菜单ID',
  `menu_name` varchar(50) CHARACTER SET utf8mb4 COLLATE utf8mb4_0900_ai_ci NOT NULL COMMENT '菜单名称',
  `parent_id` bigint(20) NULL DEFAULT 0 COMMENT '父菜单ID',
  `order_num` int(11) NULL DEFAULT 0 COMMENT '显示顺序',
  `path` varchar(200) CHARACTER SET utf8mb4 COLLATE utf8mb4_0900_ai_ci NULL DEFAULT '' COMMENT '路由地址',
  `component` varchar(255) CHARACTER SET utf8mb4 COLLATE utf8mb4_0900_ai_ci NULL DEFAULT NULL COMMENT '组件路径(views/system/user/index)',
  `is_frame` tinyint(4) NULL DEFAULT 0 COMMENT '是否为外链（0是 1否）',
  `menu_type` char(1) CHARACTER SET utf8mb4 COLLATE utf8mb4_0900_ai_ci NULL DEFAULT '' COMMENT '菜单类型（M目录 C菜单 F按钮）',
  `visible` char(1) CHARACTER SET utf8mb4 COLLATE utf8mb4_0900_ai_ci NULL DEFAULT '0' COMMENT '菜单状态（0显示 1隐藏）',
  `status` char(1) CHARACTER SET utf8mb4 COLLATE utf8mb4_0900_ai_ci NULL DEFAULT '0' COMMENT '菜单状态（0正常 1停用）',
  `perms` varchar(100) CHARACTER SET utf8mb4 COLLATE utf8mb4_0900_ai_ci NULL DEFAULT NULL COMMENT '权限标识(system:user:list)',
  `icon` varchar(100) CHARACTER SET utf8mb4 COLLATE utf8mb4_0900_ai_ci NULL DEFAULT '#' COMMENT '菜单图标',
  `create_time` datetime NULL DEFAULT CURRENT_TIMESTAMP,
  `update_time` datetime NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
  `is_deleted` tinyint(4) NULL DEFAULT 0,
  PRIMARY KEY (`menu_id`) USING BTREE
) ENGINE = InnoDB AUTO_INCREMENT = 2015147980830539826 CHARACTER SET = utf8mb4 COLLATE = utf8mb4_0900_ai_ci COMMENT = '菜单权限表' ROW_FORMAT = Dynamic;

-- ----------------------------
-- Records of sys_menu
-- ----------------------------
INSERT INTO `sys_menu` VALUES (1, '系统管理', 0, 1, 'system', NULL, 1, 'M', '0', '0', '', 'carbon:settings', '2025-12-25 22:36:47', '2026-01-31 02:56:07', 0);
INSERT INTO `sys_menu` VALUES (2, '系统监控', 0, 2, 'monitor', NULL, 1, 'M', '0', '0', '', 'carbon:activity', '2025-12-25 22:36:47', '2026-01-31 02:56:07', 0);
INSERT INTO `sys_menu` VALUES (3, '基础设施', 0, 3, 'infra', NULL, 1, 'M', '0', '0', '', 'carbon:cube', '2025-12-25 22:36:47', '2026-01-31 03:16:33', 0);
INSERT INTO `sys_menu` VALUES (4, 'AI助手', 0, 4, 'ai', NULL, 1, 'M', '1', '1', '', 'carbon:bot', '2025-12-25 22:36:47', '2026-01-31 03:35:15', 0);
INSERT INTO `sys_menu` VALUES (101, '角色管理', 1, 2, 'role', 'system/role/index', 1, 'C', '0', '0', 'system:role:list', 'carbon:user-role', '2025-12-25 22:36:47', '2026-01-31 02:56:07', 0);
INSERT INTO `sys_menu` VALUES (102, '菜单管理', 1, 3, 'menu', 'system/menu/index', 1, 'C', '0', '0', 'system:menu:list', 'carbon:menu', '2025-12-25 22:36:47', '2026-01-31 02:56:07', 0);
INSERT INTO `sys_menu` VALUES (103, '参数设置', 1, 4, 'config', 'system/config/index', 1, 'C', '0', '0', 'system:config:list', 'carbon:document-configuration', '2025-12-25 22:36:47', '2026-01-31 02:56:07', 0);
INSERT INTO `sys_menu` VALUES (200, '定时任务', 2, 1, 'job', 'monitor/job/index', 1, 'C', '0', '0', 'monitor:job:list', 'carbon:time', '2025-12-25 22:36:47', '2026-01-31 02:56:07', 0);
INSERT INTO `sys_menu` VALUES (201, '调度日志', 2, 2, 'jobLog', 'monitor/jobLog/index', 1, 'C', '0', '0', 'monitor:jobLog:list', 'carbon:notebook', '2025-12-25 22:36:47', '2026-01-31 02:56:07', 0);
INSERT INTO `sys_menu` VALUES (202, '操作日志', 2, 3, 'operlog', 'monitor/operlog/index', 1, 'C', '0', '0', 'monitor:operlog:list', 'carbon:catalog', '2025-12-25 22:36:47', '2026-01-31 02:56:07', 0);
INSERT INTO `sys_menu` VALUES (300, '文件管理', 3, 1, 'file', 'system/file/index', 1, 'C', '0', '0', 'infra:file:list', 'carbon:folder', '2025-12-25 22:36:47', '2026-01-31 02:56:07', 0);
INSERT INTO `sys_menu` VALUES (301, '通知公告', 3, 2, 'notice', 'system/notice/index', 1, 'C', '0', '0', 'system:notice:list', 'carbon:notification', '2025-12-25 22:36:47', '2026-01-31 02:56:07', 0);
INSERT INTO `sys_menu` VALUES (1000, '用户查询', 100, 1, '', '', 1, 'F', '0', '0', 'system:user:query', '#', '2025-12-25 22:36:47', '2025-12-25 22:36:47', 0);
INSERT INTO `sys_menu` VALUES (1001, '用户新增', 100, 2, '', '', 1, 'F', '0', '0', 'system:user:add', '#', '2025-12-25 22:36:47', '2025-12-25 22:36:47', 0);
INSERT INTO `sys_menu` VALUES (1003, '用户删除', 100, 4, '', '', 1, 'F', '0', '0', 'system:user:remove', '#', '2025-12-25 22:36:47', '2025-12-25 22:36:47', 0);
INSERT INTO `sys_menu` VALUES (1004, '用户导出', 100, 5, '', '', 1, 'F', '0', '0', 'system:user:export', '#', '2025-12-25 22:36:47', '2025-12-25 22:36:47', 0);
INSERT INTO `sys_menu` VALUES (1005, '用户导入', 100, 6, '', '', 1, 'F', '0', '0', 'system:user:import', '#', '2025-12-25 22:36:47', '2025-12-25 22:36:47', 0);
INSERT INTO `sys_menu` VALUES (1006, '重置密码', 100, 7, '', '', 1, 'F', '0', '0', 'system:user:resetPwd', '#', '2025-12-25 22:36:47', '2025-12-25 22:36:47', 0);
INSERT INTO `sys_menu` VALUES (1007, '角色查询', 101, 1, '', '', 1, 'F', '0', '0', 'system:role:query', '#', '2025-12-25 22:36:47', '2025-12-25 22:36:47', 0);
INSERT INTO `sys_menu` VALUES (1008, '角色新增', 101, 2, '', '', 1, 'F', '0', '0', 'system:role:add', '#', '2025-12-25 22:36:47', '2025-12-25 22:36:47', 0);
INSERT INTO `sys_menu` VALUES (1009, '角色修改', 101, 3, '', '', 1, 'F', '0', '0', 'system:role:edit', '#', '2025-12-25 22:36:47', '2025-12-25 22:36:47', 0);
INSERT INTO `sys_menu` VALUES (1010, '角色删除', 101, 4, '', '', 1, 'F', '0', '0', 'system:role:remove', '#', '2025-12-25 22:36:47', '2025-12-25 22:36:47', 0);
INSERT INTO `sys_menu` VALUES (1011, '角色导出', 101, 5, '', '', 1, 'F', '0', '0', 'system:role:export', '#', '2025-12-25 22:36:47', '2025-12-25 22:36:47', 0);
INSERT INTO `sys_menu` VALUES (1012, '数据权限', 101, 6, '', '', 1, 'F', '0', '0', 'system:role:dataScope', '#', '2025-12-25 22:36:47', '2025-12-25 22:36:47', 0);
INSERT INTO `sys_menu` VALUES (1013, '菜单查询', 102, 1, '', '', 1, 'F', '0', '0', 'system:menu:query', '#', '2025-12-25 22:36:47', '2025-12-25 22:36:47', 0);
INSERT INTO `sys_menu` VALUES (1014, '菜单新增', 102, 2, '', '', 1, 'F', '0', '0', 'system:menu:add', '#', '2025-12-25 22:36:47', '2025-12-25 22:36:47', 0);
INSERT INTO `sys_menu` VALUES (1015, '菜单修改', 102, 3, '', '', 1, 'F', '0', '0', 'system:menu:edit', '#', '2025-12-25 22:36:47', '2025-12-25 22:36:47', 0);
INSERT INTO `sys_menu` VALUES (1016, '菜单删除', 102, 4, '', '', 1, 'F', '0', '0', 'system:menu:remove', '#', '2025-12-25 22:36:47', '2025-12-25 22:36:47', 0);
INSERT INTO `sys_menu` VALUES (1017, '参数查询', 103, 1, '', '', 1, 'F', '0', '0', 'system:config:query', '#', '2025-12-25 22:36:47', '2025-12-25 22:36:47', 0);
INSERT INTO `sys_menu` VALUES (1018, '参数新增', 103, 2, '', '', 1, 'F', '0', '0', 'system:config:add', '#', '2025-12-25 22:36:47', '2025-12-25 22:36:47', 0);
INSERT INTO `sys_menu` VALUES (1019, '参数修改', 103, 3, '', '', 1, 'F', '0', '0', 'system:config:edit', '#', '2025-12-25 22:36:47', '2025-12-25 22:36:47', 0);
INSERT INTO `sys_menu` VALUES (1020, '参数删除', 103, 4, '', '', 1, 'F', '0', '0', 'system:config:remove', '#', '2025-12-25 22:36:47', '2025-12-25 22:36:47', 0);
INSERT INTO `sys_menu` VALUES (1021, '参数导出', 103, 5, '', '', 1, 'F', '0', '0', 'system:config:export', '#', '2025-12-25 22:36:47', '2025-12-25 22:36:47', 0);
INSERT INTO `sys_menu` VALUES (2000, '任务查询', 200, 1, '', '', 1, 'F', '0', '0', 'monitor:job:query', '#', '2025-12-25 22:36:47', '2025-12-25 22:36:47', 0);
INSERT INTO `sys_menu` VALUES (2001, '任务新增', 200, 2, '', '', 1, 'F', '0', '0', 'monitor:job:add', '#', '2025-12-25 22:36:47', '2025-12-25 22:36:47', 0);
INSERT INTO `sys_menu` VALUES (2002, '任务修改', 200, 3, '', '', 1, 'F', '0', '0', 'monitor:job:edit', '#', '2025-12-25 22:36:47', '2025-12-25 22:36:47', 0);
INSERT INTO `sys_menu` VALUES (2003, '任务删除', 200, 4, '', '', 1, 'F', '0', '0', 'monitor:job:remove', '#', '2025-12-25 22:36:47', '2025-12-25 22:36:47', 0);
INSERT INTO `sys_menu` VALUES (2004, '状态修改', 200, 5, '', '', 1, 'F', '0', '0', 'monitor:job:changeStatus', '#', '2025-12-25 22:36:47', '2025-12-25 22:36:47', 0);
INSERT INTO `sys_menu` VALUES (2005, '任务导出', 200, 6, '', '', 1, 'F', '0', '0', 'monitor:job:export', '#', '2025-12-25 22:36:47', '2025-12-25 22:36:47', 0);
INSERT INTO `sys_menu` VALUES (2006, '任务执行', 200, 7, '', '', 1, 'F', '0', '0', 'monitor:job:run', '#', '2025-12-25 22:36:47', '2025-12-25 22:36:47', 0);
INSERT INTO `sys_menu` VALUES (2007, '日志查询', 201, 1, '', '', 1, 'F', '0', '0', 'monitor:jobLog:query', '#', '2025-12-25 22:36:47', '2025-12-25 22:36:47', 0);
INSERT INTO `sys_menu` VALUES (2008, '日志删除', 201, 2, '', '', 1, 'F', '0', '0', 'monitor:jobLog:remove', '#', '2025-12-25 22:36:47', '2025-12-25 22:36:47', 0);
INSERT INTO `sys_menu` VALUES (2009, '日志清空', 201, 3, '', '', 1, 'F', '0', '0', 'monitor:jobLog:clean', '#', '2025-12-25 22:36:47', '2025-12-25 22:36:47', 0);
INSERT INTO `sys_menu` VALUES (2010, '日志导出', 201, 4, '', '', 1, 'F', '0', '0', 'monitor:jobLog:export', '#', '2025-12-25 22:36:47', '2025-12-25 22:36:47', 0);
INSERT INTO `sys_menu` VALUES (2011, '日志查询', 202, 1, '', '', 1, 'F', '0', '0', 'monitor:operlog:query', '#', '2025-12-25 22:36:47', '2025-12-25 22:36:47', 0);
INSERT INTO `sys_menu` VALUES (2012, '日志删除', 202, 2, '', '', 1, 'F', '0', '0', 'monitor:operlog:remove', '#', '2025-12-25 22:36:47', '2025-12-25 22:36:47', 0);
INSERT INTO `sys_menu` VALUES (2013, '日志清空', 202, 3, '', '', 1, 'F', '0', '0', 'monitor:operlog:clean', '#', '2025-12-25 22:36:47', '2025-12-25 22:36:47', 0);
INSERT INTO `sys_menu` VALUES (2014, '日志导出', 202, 4, '', '', 1, 'F', '0', '0', 'monitor:operlog:export', '#', '2025-12-25 22:36:47', '2025-12-25 22:36:47', 0);
INSERT INTO `sys_menu` VALUES (3000, '文件查询', 300, 1, '', '', 1, 'F', '0', '0', 'system:file:query', '#', '2025-12-25 22:36:47', '2026-01-30 04:15:53', 0);
INSERT INTO `sys_menu` VALUES (3001, '文件上传', 300, 2, '', '', 1, 'F', '0', '0', 'system:file:upload', '#', '2025-12-25 22:36:47', '2026-01-30 04:15:24', 0);
INSERT INTO `sys_menu` VALUES (3002, '文件删除', 300, 3, '', '', 1, 'F', '0', '0', 'system:file:remove', '#', '2025-12-25 22:36:47', '2026-01-30 04:15:40', 0);
INSERT INTO `sys_menu` VALUES (3003, '公告查询', 301, 1, '', '', 1, 'F', '0', '0', 'system:notice:query', '#', '2025-12-25 22:36:47', '2025-12-25 22:36:47', 0);
INSERT INTO `sys_menu` VALUES (3004, '公告新增', 301, 2, '', '', 1, 'F', '0', '0', 'system:notice:add', '#', '2025-12-25 22:36:47', '2025-12-25 22:36:47', 0);
INSERT INTO `sys_menu` VALUES (3005, '公告修改', 301, 3, '', '', 1, 'F', '0', '0', 'system:notice:edit', '#', '2025-12-25 22:36:47', '2025-12-25 22:36:47', 0);
INSERT INTO `sys_menu` VALUES (3006, '公告删除', 301, 4, '', '', 1, 'F', '0', '0', 'system:notice:remove', '#', '2025-12-25 22:36:47', '2025-12-25 22:36:47', 0);
INSERT INTO `sys_menu` VALUES (3007, '文件列表', 300, 4, '', '', 1, 'F', '0', '0', 'system:file:list', '#', '2025-12-25 22:36:47', '2026-01-30 04:15:33', 0);
INSERT INTO `sys_menu` VALUES (3008, '文件下载', 300, 4, '', '', 1, 'F', '0', '0', 'system:file:download', '#', '2025-12-25 22:36:47', '2026-01-30 04:15:24', 0);
INSERT INTO `sys_menu` VALUES (2015147980830539780, '工作台', 0, 0, 'workspace', 'dashboard/workspace/index', 1, 'C', '0', '0', NULL, 'carbon:home', '2026-01-30 04:08:42', '2026-01-31 02:56:07', 0);
INSERT INTO `sys_menu` VALUES (2015147980830539781, '看板', 0, 0, 'analytics', 'dashboard/analytics/index', 0, 'C', '0', '0', '', 'carbon:dashboard', '2026-01-30 17:55:35', '2026-01-31 02:56:43', 0);
INSERT INTO `sys_menu` VALUES (2015147980830539782, '????', 0, 5, 'system/news', 'system/news/index', 1, 'C', '0', '0', 'system:news:list', 'documentation', '2026-05-08 19:28:45', '2026-05-08 21:10:45', 0);
INSERT INTO `sys_menu` VALUES (2015147980830539783, '资讯查询', 2015147980830539782, 1, '', '', 1, 'F', '0', '0', 'system:news:query', '#', '2026-05-08 20:24:10', '2026-05-08 20:24:10', 0);
INSERT INTO `sys_menu` VALUES (2015147980830539784, '资讯新增', 2015147980830539782, 2, '', '', 1, 'F', '0', '0', 'system:news:add', '#', '2026-05-08 20:24:10', '2026-05-08 20:24:10', 0);
INSERT INTO `sys_menu` VALUES (2015147980830539785, '资讯修改', 2015147980830539782, 3, '', '', 1, 'F', '0', '0', 'system:news:edit', '#', '2026-05-08 20:24:10', '2026-05-08 20:24:10', 0);
INSERT INTO `sys_menu` VALUES (2015147980830539786, '资讯删除', 2015147980830539782, 4, '', '', 1, 'F', '0', '0', 'system:news:remove', '#', '2026-05-08 20:24:10', '2026-05-08 20:24:10', 0);
INSERT INTO `sys_menu` VALUES (2015147980830539787, '下载管理', 0, 6, 'system/download', 'system/download/index', 1, 'C', '0', '0', 'system:download:list', 'carbon:download', '2026-05-08 20:24:10', '2026-05-08 21:58:03', 0);
INSERT INTO `sys_menu` VALUES (2015147980830539788, '下载查询', 2015147980830539787, 1, '', '', 1, 'F', '0', '0', 'system:download:query', '#', '2026-05-08 20:24:10', '2026-05-08 20:24:10', 0);
INSERT INTO `sys_menu` VALUES (2015147980830539789, '下载新增', 2015147980830539787, 2, '', '', 1, 'F', '0', '0', 'system:download:add', '#', '2026-05-08 20:24:10', '2026-05-08 20:24:10', 0);
INSERT INTO `sys_menu` VALUES (2015147980830539790, '下载修改', 2015147980830539787, 3, '', '', 1, 'F', '0', '0', 'system:download:edit', '#', '2026-05-08 20:24:10', '2026-05-08 20:24:10', 0);
INSERT INTO `sys_menu` VALUES (2015147980830539791, '下载删除', 2015147980830539787, 4, '', '', 1, 'F', '0', '0', 'system:download:remove', '#', '2026-05-08 20:24:10', '2026-05-08 20:24:10', 0);
INSERT INTO `sys_menu` VALUES (2015147980830539792, '资讯管理', 0, 5, 'system/news', 'system/news/index', 1, 'C', '0', '0', 'system:news:list', 'documentation', '2026-05-08 21:39:43', '2026-05-08 21:39:43', 0);
INSERT INTO `sys_menu` VALUES (2015147980830539793, '资讯查询', 2015147980830539782, 1, '', '', 1, 'F', '0', '0', 'system:news:query', '#', '2026-05-08 21:39:53', '2026-05-08 21:39:53', 0);
INSERT INTO `sys_menu` VALUES (2015147980830539794, '资讯新增', 2015147980830539782, 2, '', '', 1, 'F', '0', '0', 'system:news:add', '#', '2026-05-08 21:39:53', '2026-05-08 21:39:53', 0);
INSERT INTO `sys_menu` VALUES (2015147980830539795, '资讯修改', 2015147980830539782, 3, '', '', 1, 'F', '0', '0', 'system:news:edit', '#', '2026-05-08 21:39:53', '2026-05-08 21:39:53', 0);
INSERT INTO `sys_menu` VALUES (2015147980830539796, '资讯删除', 2015147980830539782, 4, '', '', 1, 'F', '0', '0', 'system:news:remove', '#', '2026-05-08 21:39:53', '2026-05-08 21:39:53', 0);
INSERT INTO `sys_menu` VALUES (2015147980830539801, '页面管理', 0, 7, 'system/page', 'system/page/index', 1, 'C', '0', '0', 'system:page:list', 'carbon:data-viewer', '2026-05-08 21:58:03', '2026-05-08 21:58:03', 0);
INSERT INTO `sys_menu` VALUES (2015147980830539802, '页面查询', 2015147980830539801, 1, '', '', 1, 'F', '0', '0', 'system:page:query', '#', '2026-05-08 21:58:03', '2026-05-08 21:58:03', 0);
INSERT INTO `sys_menu` VALUES (2015147980830539803, '页面编辑', 2015147980830539801, 2, '', '', 1, 'F', '0', '0', 'system:page:edit', '#', '2026-05-08 21:58:03', '2026-05-08 21:58:03', 0);
INSERT INTO `sys_menu` VALUES (2015147980830539804, '网站管理', 0, 8, '/site', NULL, 1, 'M', '0', '0', NULL, 'carbon:cics-sit', '2026-05-09 00:21:13', '2026-05-09 00:21:13', 0);
INSERT INTO `sys_menu` VALUES (2015147980830539805, '网站设置', 2015147980830539804, 1, 'config', 'system/site/config/index', 1, 'C', '0', '0', 'system:siteConfig:list', 'carbon:settings', '2026-05-09 00:21:13', '2026-05-09 00:21:13', 0);
INSERT INTO `sys_menu` VALUES (2015147980830539806, '导航管理', 2015147980830539804, 2, 'navigation', 'system/site/navigation/index', 1, 'C', '0', '0', 'system:navigation:list', 'carbon:collapse-categories', '2026-05-09 00:21:13', '2026-05-09 00:21:13', 0);
INSERT INTO `sys_menu` VALUES (2015147980830539807, 'Banner管理', 2015147980830539804, 3, 'banner', 'system/site/banner/index', 1, 'C', '0', '0', 'system:banner:list', 'carbon:flag', '2026-05-09 00:21:13', '2026-05-09 00:21:13', 0);
INSERT INTO `sys_menu` VALUES (2015147980830539808, '产品管理', 2015147980830539804, 4, 'ecosystem', 'system/site/ecosystem/index', 1, 'C', '0', '0', 'system:ecosystem:list', 'carbon:application-web', '2026-05-09 00:21:13', '2026-05-09 00:21:13', 0);
INSERT INTO `sys_menu` VALUES (2015147980830539809, 'API文档', 2015147980830539804, 5, 'apiDoc', 'system/site/apiDoc/index', 1, 'C', '0', '0', 'system:apiDoc:list', 'carbon:api', '2026-05-09 00:21:13', '2026-05-09 00:21:13', 0);
INSERT INTO `sys_menu` VALUES (2015147980830539810, '网站设置查询', 2015147980830539805, 1, '', '', 1, 'F', '0', '0', 'system:siteConfig:query', '#', '2026-05-09 00:21:13', '2026-05-09 00:21:13', 0);
INSERT INTO `sys_menu` VALUES (2015147980830539811, '网站设置编辑', 2015147980830539805, 2, '', '', 1, 'F', '0', '0', 'system:siteConfig:edit', '#', '2026-05-09 00:21:13', '2026-05-09 00:21:13', 0);
INSERT INTO `sys_menu` VALUES (2015147980830539812, '导航查询', 2015147980830539806, 1, '', '', 1, 'F', '0', '0', 'system:navigation:query', '#', '2026-05-09 00:21:13', '2026-05-09 00:21:13', 0);
INSERT INTO `sys_menu` VALUES (2015147980830539813, '导航新增', 2015147980830539806, 2, '', '', 1, 'F', '0', '0', 'system:navigation:add', '#', '2026-05-09 00:21:13', '2026-05-09 00:21:13', 0);
INSERT INTO `sys_menu` VALUES (2015147980830539814, '导航编辑', 2015147980830539806, 3, '', '', 1, 'F', '0', '0', 'system:navigation:edit', '#', '2026-05-09 00:21:13', '2026-05-09 00:21:13', 0);
INSERT INTO `sys_menu` VALUES (2015147980830539815, '导航删除', 2015147980830539806, 4, '', '', 1, 'F', '0', '0', 'system:navigation:remove', '#', '2026-05-09 00:21:13', '2026-05-09 00:21:13', 0);
INSERT INTO `sys_menu` VALUES (2015147980830539816, 'Banner查询', 2015147980830539807, 1, '', '', 1, 'F', '0', '0', 'system:banner:query', '#', '2026-05-09 00:21:13', '2026-05-09 00:21:13', 0);
INSERT INTO `sys_menu` VALUES (2015147980830539817, 'Banner新增', 2015147980830539807, 2, '', '', 1, 'F', '0', '0', 'system:banner:add', '#', '2026-05-09 00:21:13', '2026-05-09 00:21:13', 0);
INSERT INTO `sys_menu` VALUES (2015147980830539818, 'Banner编辑', 2015147980830539807, 3, '', '', 1, 'F', '0', '0', 'system:banner:edit', '#', '2026-05-09 00:21:13', '2026-05-09 00:21:13', 0);
INSERT INTO `sys_menu` VALUES (2015147980830539819, 'Banner删除', 2015147980830539807, 4, '', '', 1, 'F', '0', '0', 'system:banner:remove', '#', '2026-05-09 00:21:13', '2026-05-09 00:21:13', 0);
INSERT INTO `sys_menu` VALUES (2015147980830539820, '产品查询', 2015147980830539808, 1, '', '', 1, 'F', '0', '0', 'system:ecosystem:query', '#', '2026-05-09 00:21:13', '2026-05-09 00:21:13', 0);
INSERT INTO `sys_menu` VALUES (2015147980830539821, '产品新增', 2015147980830539808, 2, '', '', 1, 'F', '0', '0', 'system:ecosystem:add', '#', '2026-05-09 00:21:13', '2026-05-09 00:21:13', 0);
INSERT INTO `sys_menu` VALUES (2015147980830539822, '产品编辑', 2015147980830539808, 3, '', '', 1, 'F', '0', '0', 'system:ecosystem:edit', '#', '2026-05-09 00:21:13', '2026-05-09 00:21:13', 0);
INSERT INTO `sys_menu` VALUES (2015147980830539823, '产品删除', 2015147980830539808, 4, '', '', 1, 'F', '0', '0', 'system:ecosystem:remove', '#', '2026-05-09 00:21:13', '2026-05-09 00:21:13', 0);
INSERT INTO `sys_menu` VALUES (2015147980830539824, 'API文档查询', 2015147980830539809, 1, '', '', 1, 'F', '0', '0', 'system:apiDoc:query', '#', '2026-05-09 00:21:13', '2026-05-09 00:21:13', 0);
INSERT INTO `sys_menu` VALUES (2015147980830539825, 'API文档编辑', 2015147980830539809, 2, '', '', 1, 'F', '0', '0', 'system:apiDoc:edit', '#', '2026-05-09 00:21:13', '2026-05-09 00:21:13', 0);

-- ----------------------------
-- Table structure for sys_news
-- ----------------------------
DROP TABLE IF EXISTS `sys_news`;
CREATE TABLE `sys_news`  (
  `news_id` bigint(20) NOT NULL AUTO_INCREMENT COMMENT '资讯ID',
  `title` varchar(255) CHARACTER SET utf8mb4 COLLATE utf8mb4_0900_ai_ci NOT NULL COMMENT '标题',
  `summary` varchar(500) CHARACTER SET utf8mb4 COLLATE utf8mb4_0900_ai_ci NULL DEFAULT NULL COMMENT '摘要',
  `content` longtext CHARACTER SET utf8mb4 COLLATE utf8mb4_0900_ai_ci NULL COMMENT '内容(Markdown/HTML)',
  `cover_image` varchar(500) CHARACTER SET utf8mb4 COLLATE utf8mb4_0900_ai_ci NULL DEFAULT NULL COMMENT '封面图',
  `category` varchar(50) CHARACTER SET utf8mb4 COLLATE utf8mb4_0900_ai_ci NULL DEFAULT 'general' COMMENT '分类',
  `tags` varchar(500) CHARACTER SET utf8mb4 COLLATE utf8mb4_0900_ai_ci NULL DEFAULT NULL COMMENT '标签(JSON数组)',
  `status` char(1) CHARACTER SET utf8mb4 COLLATE utf8mb4_0900_ai_ci NULL DEFAULT '0' COMMENT '状态(0发布 1草稿 2下架)',
  `view_count` int(11) NULL DEFAULT 0 COMMENT '浏览次数',
  `create_by` varchar(64) CHARACTER SET utf8mb4 COLLATE utf8mb4_0900_ai_ci NULL DEFAULT NULL COMMENT '创建者',
  `create_time` datetime NULL DEFAULT CURRENT_TIMESTAMP COMMENT '创建时间',
  `update_time` datetime NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP COMMENT '更新时间',
  `publish_time` datetime NULL DEFAULT NULL COMMENT '发布时间',
  PRIMARY KEY (`news_id`) USING BTREE,
  INDEX `idx_category`(`category` ASC) USING BTREE,
  INDEX `idx_status`(`status` ASC) USING BTREE,
  INDEX `idx_publish_time`(`publish_time` ASC) USING BTREE
) ENGINE = InnoDB AUTO_INCREMENT = 2052731472993370115 CHARACTER SET = utf8mb4 COLLATE = utf8mb4_0900_ai_ci COMMENT = '资讯表' ROW_FORMAT = Dynamic;

-- ----------------------------
-- Records of sys_news
-- ----------------------------
INSERT INTO `sys_news` VALUES (1, 'MinerU v2.5 正式发布：支持化学分子式识别与 MCP 协议', '本次更新带来了全新的化学分子式识别能力，并原生支持 MCP 协议，让 AI Agent 可以直接调用文档解析能力。', '## 概述\n\nMinerU v2.5 是一次重大版本更新，带来了多项突破性功能。\n\n### 化学分子式识别\n\n全新的化学分子式识别模块，能够精确检测和识别文档中的分子结构图：\n\n- **分子检测**：精确的分子检测与定位\n- **结构识别**：SOTA 性能的分子结构图识别能力\n- **原子与键**：原子和键的识别与原始图像严格对应\n\n### MCP 协议支持\n\n原生支持 Model Context Protocol (MCP)，让 AI Agent 可以直接调用 MinerU 的文档解析能力：\n\n- Claude Desktop 可通过 MCP 直接调用\n- 支持 Dify、Coze 等平台的 Agent 集成\n- 免登录 Flash 模式，适合高频自动化工作流\n\n### 性能优化\n\n- 大文档处理速度提升 40%\n- 内存占用降低 25%\n- 支持批量并发处理\n\n### 更多格式支持\n\n- 新增 PPT 文档解析\n- 改进图片 OCR 识别\n- 优化网页 URL 内容提取', NULL, '版本发布', '[\"MinerU\",\"v2.5\",\"化学识别\",\"MCP\"]', '0', 12581, 'admin', '2026-05-08 20:11:37', '2026-05-08 20:44:56', '2026-04-15 10:00:00');
INSERT INTO `sys_news` VALUES (2, '如何用 MinerU 构建 RAG 知识库的文档预处理管道', '本文详细介绍如何使用 MinerU 将 PDF 文档转换为结构化 Markdown，为 RAG 系统提供高质量的数据输入。', '## 背景\n\nRAG（检索增强生成）系统对文档质量要求极高。传统的 PDF 解析工具往往丢失排版结构，导致文本块破碎、表格数据混乱。MinerU 专为 AI 应用设计，可以输出高度结构化的 Markdown/JSON 格式。\n\n## 核心步骤\n\n### 1. 文档解析\n\n使用 MinerU 将 PDF 转换为结构化 Markdown：\n\n```bash\ncurl -X POST https://api.xinghuitec.com/v1/parse \\\n  -H \"Authorization: Bearer YOUR_API_KEY\" \\\n  -F \"file=@document.pdf\" \\\n  -F \"output_format=markdown\"\n```\n\n### 2. 文本分块\n\n解析得到的 Markdown 保留了完整的标题层级，可以按标题自然分块。\n\n### 3. 向量化与存储\n\n将分块后的文本进行 Embedding 并存入向量数据库。\n\n## 总结\n\nMinerU 的结构化输出使得 RAG 系统的文档预处理变得简单高效。', NULL, '技术分享', '[\"RAG\",\"知识库\",\"文档解析\",\"Embedding\"]', '0', 8920, 'admin', '2026-05-08 20:11:37', '2026-05-08 20:11:37', '2026-04-10 14:30:00');
INSERT INTO `sys_news` VALUES (3, 'MinerU 与 Dify 深度集成指南', '通过 MinerU 插件，你可以在 Dify 工作流中直接解析 PDF 文档，提取结构化信息用于后续处理。', '## 集成概述\n\nMinerU 已上架 Dify 插件市场，安装后即可在工作流中使用。\n\n## 安装与配置\n\n1. 在 Dify 插件市场中搜索 \"MinerU\"\n2. 点击安装并配置 API Key\n3. 在工作流中拖入 MinerU 节点\n\n## 使用示例\n\n将 MinerU 节点放置在知识库导入流程之前，可以实现：\n\n- 自动将上传的 PDF 转换为结构化 Markdown\n- 表格自动提取为 CSV 格式\n- 公式转换为 LaTeX\n\n这大大提升了 Dify 知识库的文档处理能力。', NULL, '产品动态', '[\"Dify\",\"集成\",\"工作流\",\"插件\"]', '0', 6540, 'admin', '2026-05-08 20:11:37', '2026-05-08 20:11:37', '2026-04-05 09:15:00');
INSERT INTO `sys_news` VALUES (4, '文档解析技术综述：从传统 OCR 到多模态大模型', '回顾文档解析技术的发展历程，探讨多模态大模型在文档理解领域的最新进展和未来方向。', '## 技术演进\n\n### 第一代：规则引擎\n\n基于模板匹配和规则的方法，适用于固定格式文档。\n\n### 第二代：OCR + NLP\n\n结合光学字符识别和自然语言处理，可以处理扫描件和图片。\n\n### 第三代：深度学习\n\n基于 CNN/RNN 的文档布局分析，显著提升了复杂排版的识别能力。\n\n### 第四代：多模态大模型\n\nMinerU 采用最新的多模态大模型技术，能够同时理解文档中的文字、表格、公式、图片等多种元素，并输出高度结构化的机器可读数据。\n\n## 未来展望\n\n随着 Agent 技术的兴起，文档解析正在从\"人工辅助\"向\"全自动 Agent 处理\"转变。MinerU 原生支持 MCP 协议，正是为了满足这一趋势。', NULL, '行业资讯', '[\"OCR\",\"多模态\",\"大模型\",\"技术综述\"]', '0', 4320, 'admin', '2026-05-08 20:11:37', '2026-05-08 20:11:37', '2026-03-28 16:00:00');
INSERT INTO `sys_news` VALUES (5, 'MinerU 在学术论文解析中的应用实践', '多家高校和研究机构采用 MinerU 进行学术论文批量解析，显著提升了文献综述和研究数据提取的效率。', '## 应用场景\n\n### 文献综述\n\n研究人员使用 MinerU 批量解析 arXiv 上的论文 PDF，将公式和表格提取为结构化数据，大大加快了文献综述的效率。\n\n### 实验数据提取\n\n化学和生物学研究者利用 MinerU 的分子式识别功能，从大量论文中自动提取化合物结构和实验参数。\n\n### 知识图谱构建\n\n基于 MinerU 的结构化输出，可以自动构建学术知识图谱，发现跨学科的研究关联。\n\n## 用户反馈\n\n> \"MinerU 让我们的文献处理效率提升了 10 倍以上。\" —— 某高校 AI Lab 研究员', NULL, '产品动态', '[\"学术\",\"论文解析\",\"知识图谱\",\"文献综述\"]', '0', 3560, 'admin', '2026-05-08 20:11:37', '2026-05-08 20:11:37', '2026-03-20 11:30:00');

-- ----------------------------
-- Table structure for sys_notice
-- ----------------------------
DROP TABLE IF EXISTS `sys_notice`;
CREATE TABLE `sys_notice`  (
  `notice_id` int(11) NOT NULL AUTO_INCREMENT COMMENT '公告ID',
  `tenant_id` varchar(20) CHARACTER SET utf8mb4 COLLATE utf8mb4_0900_ai_ci NULL DEFAULT '000000' COMMENT '租户编号',
  `notice_title` varchar(50) CHARACTER SET utf8mb4 COLLATE utf8mb4_0900_ai_ci NOT NULL COMMENT '公告标题',
  `notice_type` char(1) CHARACTER SET utf8mb4 COLLATE utf8mb4_0900_ai_ci NOT NULL COMMENT '公告类型（1通知卡片 2强弹窗）',
  `notice_content` longblob NULL COMMENT '公告内容(HTML/Text)',
  `status` char(1) CHARACTER SET utf8mb4 COLLATE utf8mb4_0900_ai_ci NULL DEFAULT '0' COMMENT '公告状态（0正常 1关闭）',
  `start_time` datetime NULL DEFAULT NULL COMMENT '生效开始时间',
  `end_time` datetime NULL DEFAULT NULL COMMENT '生效结束时间',
  `create_by` varchar(64) CHARACTER SET utf8mb4 COLLATE utf8mb4_0900_ai_ci NULL DEFAULT '' COMMENT '创建者',
  `create_time` datetime NULL DEFAULT CURRENT_TIMESTAMP,
  `update_time` datetime NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
  `update_by` varchar(64) CHARACTER SET utf8mb4 COLLATE utf8mb4_0900_ai_ci NULL DEFAULT NULL COMMENT '更新者ID',
  `is_deleted` tinyint(4) NULL DEFAULT 0,
  PRIMARY KEY (`notice_id`) USING BTREE,
  INDEX `idx_tenant_id`(`tenant_id` ASC) USING BTREE
) ENGINE = InnoDB AUTO_INCREMENT = 2009116675 CHARACTER SET = utf8mb4 COLLATE = utf8mb4_0900_ai_ci COMMENT = '通知公告表' ROW_FORMAT = Dynamic;

-- ----------------------------
-- Records of sys_notice
-- ----------------------------
INSERT INTO `sys_notice` VALUES (-960479230, '000000', '测试通知', '2', 0xE890A8E8BEBE, '0', '2026-01-31 00:00:00', '2026-01-31 00:00:00', 'beidaomitu', '2026-01-31 02:43:46', '2026-01-31 03:40:01', NULL, 0);

-- ----------------------------
-- Table structure for sys_oper_log
-- ----------------------------
DROP TABLE IF EXISTS `sys_oper_log`;
CREATE TABLE `sys_oper_log`  (
  `oper_id` bigint(20) NOT NULL AUTO_INCREMENT COMMENT '日志主键',
  `title` varchar(50) CHARACTER SET utf8mb4 COLLATE utf8mb4_0900_ai_ci NULL DEFAULT '' COMMENT '模块标题(如: 用户管理)',
  `business_type` int(11) NULL DEFAULT 0 COMMENT '业务类型(0其它 1新增 2修改 3删除 4授权 5导出 6导入)',
  `method` varchar(100) CHARACTER SET utf8mb4 COLLATE utf8mb4_0900_ai_ci NULL DEFAULT '' COMMENT '方法名称',
  `request_method` varchar(10) CHARACTER SET utf8mb4 COLLATE utf8mb4_0900_ai_ci NULL DEFAULT '' COMMENT '请求方式(GET/POST)',
  `oper_name` varchar(50) CHARACTER SET utf8mb4 COLLATE utf8mb4_0900_ai_ci NULL DEFAULT '' COMMENT '操作人员',
  `oper_url` varchar(255) CHARACTER SET utf8mb4 COLLATE utf8mb4_0900_ai_ci NULL DEFAULT '' COMMENT '请求URL',
  `oper_ip` varchar(128) CHARACTER SET utf8mb4 COLLATE utf8mb4_0900_ai_ci NULL DEFAULT '' COMMENT '主机地址',
  `oper_param` varchar(2000) CHARACTER SET utf8mb4 COLLATE utf8mb4_0900_ai_ci NULL DEFAULT '' COMMENT '请求参数',
  `json_result` varchar(2000) CHARACTER SET utf8mb4 COLLATE utf8mb4_0900_ai_ci NULL DEFAULT '' COMMENT '返回参数',
  `status` int(11) NULL DEFAULT 0 COMMENT '操作状态(0正常 1异常)',
  `error_msg` varchar(2000) CHARACTER SET utf8mb4 COLLATE utf8mb4_0900_ai_ci NULL DEFAULT '' COMMENT '错误消息',
  `cost_time` bigint(20) NULL DEFAULT 0 COMMENT '消耗时间(毫秒)',
  `oper_time` datetime NULL DEFAULT CURRENT_TIMESTAMP COMMENT '操作时间',
  PRIMARY KEY (`oper_id`) USING BTREE,
  INDEX `idx_oper_time`(`oper_time` ASC) USING BTREE
) ENGINE = InnoDB AUTO_INCREMENT = 2056454752513933314 CHARACTER SET = utf8mb4 COLLATE = utf8mb4_0900_ai_ci COMMENT = '操作日志记录' ROW_FORMAT = Dynamic;

-- ----------------------------
-- Records of sys_oper_log
-- ----------------------------
INSERT INTO `sys_oper_log` VALUES (2015152802669621250, '角色管理', 1, 'com.xinghuiTec.controller.SysRoleController.addRole', 'POST', 'testuser2', '/system/role/add', '127.0.0.1', '[{\"menuIds\":[1,2,3],\"roleKey\":\"test_role_1769284842211\",\"roleName\":\"测试角色_1769284842211\",\"status\":1}]', '{\"code\":200,\"data\":2015152801788817410,\"message\":\"成功\"}', 0, '', 277, '2026-01-25 04:00:42');
INSERT INTO `sys_oper_log` VALUES (2015152803185520642, '角色管理', 1, 'com.xinghuiTec.controller.SysRoleController.addRole', 'POST', 'testuser2', '/system/role/add', '127.0.0.1', '[{\"roleKey\":\"admin\",\"roleName\":\"测试角色\",\"status\":1}]', '', 1, '角色权限字符串已存在', 6, '2026-01-25 04:00:43');
INSERT INTO `sys_oper_log` VALUES (2015152803399430145, '角色管理', 2, 'com.xinghuiTec.controller.SysRoleController.updateRole', 'POST', 'testuser2', '/system/role/edit', '127.0.0.1', '[{\"menuIds\":[1,2,3,4],\"roleId\":1,\"roleKey\":\"test_role\",\"roleName\":\"测试角色(已修改)\",\"status\":1}]', '{\"code\":200,\"message\":\"成功\"}', 0, '', 17, '2026-01-25 04:00:43');
INSERT INTO `sys_oper_log` VALUES (2015152803709808642, '角色管理', 2, 'com.xinghuiTec.controller.SysRoleController.updateRoleStatus', 'POST', 'testuser2', '/system/role/changeStatus', '127.0.0.1', '[1,0]', '{\"code\":200,\"message\":\"成功\"}', 0, '', 15, '2026-01-25 04:00:43');
INSERT INTO `sys_oper_log` VALUES (2015153411443433473, '测试模块', 1, 'com.xinghuiTec.controller.TestController.testMethod', 'POST', 'admin', '/test/add', '127.0.0.1', '{\"name\":\"test\"}', '{\"code\":200,\"msg\":\"success\"}', 0, '', 100, '2026-01-25 04:03:08');
INSERT INTO `sys_oper_log` VALUES (2015153415646126081, '用户管理', 1, '', '', 'testUser', '/system/user/add', '127.0.0.1', '', '', 0, '', 50, '2026-01-25 04:03:09');
INSERT INTO `sys_oper_log` VALUES (2016956170333364225, '角色管理', 1, 'com.xinghuiTec.controller.SysRoleController.addRole', 'POST', 'beidaomitu', '/system/role/add', '127.0.0.1', '[{\"roleKey\":\"user\",\"roleName\":\"用户\",\"status\":1}]', '{\"code\":200,\"data\":2016956169284788226,\"message\":\"成功\"}', 0, '', 279, '2026-01-30 03:26:39');
INSERT INTO `sys_oper_log` VALUES (2016959761647525889, '通知公告', 1, 'com.xinghuiTec.controller.SysNoticeController.addNotice', 'POST', 'beidaomitu', '/system/notice/add', '127.0.0.1', '[{\"noticeContent\":\"v01.完成啦\",\"noticeTitle\":\"网站更新公告\",\"noticeType\":1,\"remark\":\"\",\"status\":1}]', '{\"code\":200,\"data\":1409409026,\"message\":\"成功\"}', 0, '', 214, '2026-01-30 03:40:55');
INSERT INTO `sys_oper_log` VALUES (2016959777552326657, '通知公告', 3, 'com.xinghuiTec.controller.SysNoticeController.deleteNotice', 'POST', 'beidaomitu', '/system/notice/remove/79740929', '127.0.0.1', '[79740929]', '{\"code\":200,\"message\":\"成功\"}', 0, '', 34, '2026-01-30 03:40:59');
INSERT INTO `sys_oper_log` VALUES (2016968262390132738, '通知公告', 3, 'com.xinghuiTec.controller.SysNoticeController.deleteNotices', 'POST', 'beidaomitu', '/system/notice/removeBatch', '127.0.0.1', '[[1409409026,654360579,1329639425,2009116674,637657089,1682038785,1082159105,171995137]]', '{\"code\":200,\"message\":\"成功\"}', 0, '', 32, '2026-01-30 04:14:42');
INSERT INTO `sys_oper_log` VALUES (2016975830516772865, '文件管理', 1, 'com.xinghuiTec.controller.SysFileController.uploadFile', 'POST', 'beidaomitu', '/file/upload', '127.0.0.1', '[]', '', 1, '文件上传失败: 生成缩略图失败！', 141, '2026-01-30 04:44:46');
INSERT INTO `sys_oper_log` VALUES (2016978546701979650, '文件管理', 1, 'com.xinghuiTec.controller.SysFileController.uploadFile', 'POST', 'beidaomitu', '/file/upload', '127.0.0.1', '[]', '{\"code\":200,\"data\":{\"basePath\":\"upload/\",\"ext\":\"pdf\",\"filename\":\"697bc945db74e777a8d235c7.pdf\",\"id\":1769720133718,\"originalName\":\"《2025年“人工智能+”行业标杆案例荟萃》.pdf\",\"path\":\"upload/\",\"platform\":\"local-plus-1\",\"size\":28320434,\"url\":\"http://127.0.0.1:7799/file/upload/upload/697bc945db74e777a8d235c7.pdf\"},\"message\":\"成功\"}', 0, '', 310, '2026-01-30 04:55:34');
INSERT INTO `sys_oper_log` VALUES (2017307767596924930, '通知公告', 1, 'com.xinghuiTec.controller.SysNoticeController.addNotice', 'POST', 'beidaomitu', '/system/notice/add', '127.0.0.1', '[{\"noticeContent\":\"测试\",\"noticeTitle\":\"测试通知\",\"noticeType\":1,\"remark\":\"\",\"status\":0}]', '{\"code\":200,\"data\":-960479230,\"message\":\"成功\"}', 0, '', 212, '2026-01-31 02:43:46');
INSERT INTO `sys_oper_log` VALUES (2017307792758554626, '通知公告', 2, 'com.xinghuiTec.controller.SysNoticeController.updateNotice', 'POST', 'beidaomitu', '/system/notice/edit', '127.0.0.1', '[{\"noticeContent\":\"5rWL6K+V\",\"noticeId\":-960479230,\"noticeTitle\":\"测试通知\",\"noticeType\":1,\"status\":0}]', '{\"code\":200,\"message\":\"成功\"}', 0, '', 43, '2026-01-31 02:43:52');
INSERT INTO `sys_oper_log` VALUES (2017313145986568194, '通知公告', 2, 'com.xinghuiTec.controller.SysNoticeController.updateNotice', 'POST', 'beidaomitu', '/system/notice/edit', '127.0.0.1', '[{\"noticeContent\":\"NXJXTDZLK1Y=\",\"noticeId\":-960479230,\"noticeTitle\":\"测试通知\",\"noticeType\":1,\"status\":0}]', '{\"code\":200,\"message\":\"成功\"}', 0, '', 63, '2026-01-31 03:05:08');
INSERT INTO `sys_oper_log` VALUES (2017313874658807809, '角色管理', 2, 'com.xinghuiTec.controller.SysRoleController.updateRole', 'POST', 'beidaomitu', '/system/role/edit', '127.0.0.1', '[{\"menuIds\":[],\"roleId\":2016956169284788226,\"roleKey\":\"user\",\"roleName\":\"用户\",\"status\":0}]', '{\"code\":200,\"message\":\"成功\"}', 0, '', 123, '2026-01-31 03:08:02');
INSERT INTO `sys_oper_log` VALUES (2017313874721722369, '角色管理', 4, 'com.xinghuiTec.controller.SysRoleController.assignMenus', 'POST', 'beidaomitu', '/system/role/assignMenus', '127.0.0.1', '[2016956169284788226,[]]', '{\"code\":200,\"message\":\"成功\"}', 0, '', 9, '2026-01-31 03:08:02');
INSERT INTO `sys_oper_log` VALUES (2017318106447302658, '通知公告', 2, 'com.xinghuiTec.controller.SysNoticeController.updateNotice', 'POST', 'beidaomitu', '/system/notice/edit', '127.0.0.1', '[{\"noticeContent\":\"TlhKWFREWkxLMVk9\",\"noticeId\":-960479230,\"noticeTitle\":\"测试通知\",\"noticeType\":1,\"status\":0}]', '{\"code\":200,\"message\":\"成功\"}', 0, '', 52, '2026-01-31 03:24:51');
INSERT INTO `sys_oper_log` VALUES (2017318823581011970, '菜单管理', 2, 'com.xinghuiTec.controller.SysMenuController.updateMenu', 'POST', 'beidaomitu', '/system/menu/edit', '127.0.0.1', '[{\"icon\":\"carbon:bot\",\"isFrame\":1,\"menuId\":4,\"menuName\":\"AI助手\",\"menuType\":\"M\",\"orderNum\":4,\"parentId\":0,\"path\":\"ai\",\"perms\":\"\",\"status\":\"1\",\"visible\":\"1\"}]', '{\"code\":200,\"message\":\"成功\"}', 0, '', 59, '2026-01-31 03:27:42');
INSERT INTO `sys_oper_log` VALUES (2017320272108990466, '菜单管理', 2, 'com.xinghuiTec.controller.SysMenuController.updateMenu', 'POST', 'beidaomitu', '/system/menu/edit', '127.0.0.1', '[{\"icon\":\"carbon:bot\",\"isFrame\":1,\"menuId\":4,\"menuName\":\"AI助手\",\"menuType\":\"M\",\"orderNum\":4,\"parentId\":0,\"path\":\"ai\",\"perms\":\"\",\"status\":\"0\",\"visible\":\"0\"}]', '{\"code\":200,\"message\":\"成功\"}', 0, '', 106, '2026-01-31 03:33:27');
INSERT INTO `sys_oper_log` VALUES (2017320724540174337, '菜单管理', 2, 'com.xinghuiTec.controller.SysMenuController.updateMenu', 'POST', 'beidaomitu', '/system/menu/edit', '127.0.0.1', '[{\"icon\":\"carbon:bot\",\"isFrame\":1,\"menuId\":4,\"menuName\":\"AI助手\",\"menuType\":\"M\",\"orderNum\":4,\"parentId\":0,\"path\":\"ai\",\"perms\":\"\",\"status\":\"1\",\"visible\":\"1\"}]', '{\"code\":200,\"message\":\"成功\"}', 0, '', 112, '2026-01-31 03:35:15');
INSERT INTO `sys_oper_log` VALUES (2017321387735134209, '通知公告', 2, 'com.xinghuiTec.controller.SysNoticeController.updateNotice', 'POST', 'beidaomitu', '/system/notice/edit', '127.0.0.1', '[{\"endTime\":\"2026-01-31 00:00:00\",\"noticeContent\":\"测试\",\"noticeId\":-960479230,\"noticeTitle\":\"测试通知\",\"noticeType\":2,\"startTime\":\"2026-01-31 00:00:00\",\"status\":0}]', '{\"code\":200,\"message\":\"成功\"}', 0, '', 127, '2026-01-31 03:37:53');
INSERT INTO `sys_oper_log` VALUES (2017321924153061377, '通知公告', 2, 'com.xinghuiTec.controller.SysNoticeController.updateNotice', 'POST', 'beidaomitu', '/system/notice/edit', '127.0.0.1', '[{\"endTime\":\"2026-01-30T16:00:00.000Z\",\"noticeContent\":\"萨达\",\"noticeId\":-960479230,\"noticeTitle\":\"测试通知\",\"noticeType\":2,\"startTime\":\"2026-01-30T16:00:00.000Z\",\"status\":0}]', '{\"code\":200,\"message\":\"成功\"}', 0, '', 45, '2026-01-31 03:40:01');
INSERT INTO `sys_oper_log` VALUES (2052731473861591042, '资讯管理', 1, 'com.xinghuiTec.controller.SysNewsController.addNews', 'POST', 'beidaomitu', '/news/add', '127.0.0.1', '[{\"category\":\"????\",\"content\":\"## ????\",\"status\":\"0\",\"summary\":\"?????\",\"tags\":\"[\\\"??\\\"]\",\"title\":\"PowerShell????_20260508204455\"}]', '{\"code\":200,\"data\":2052731472993370114,\"message\":\"成功\"}', 0, '', 211, '2026-05-08 20:44:56');
INSERT INTO `sys_oper_log` VALUES (2052731473979031554, '资讯管理', 2, 'com.xinghuiTec.controller.SysNewsController.updateNews', 'POST', 'beidaomitu', '/news/edit', '127.0.0.1', '[{\"category\":\"????\",\"content\":\"## ??????\",\"newsId\":2052731472993370114,\"status\":\"0\",\"summary\":\"??????\",\"title\":\"PowerShell????(???)_20260508204455\"}]', '{\"code\":200,\"message\":\"成功\"}', 0, '', 19, '2026-05-08 20:44:56');
INSERT INTO `sys_oper_log` VALUES (2052731474109054978, '资讯管理', 3, 'com.xinghuiTec.controller.SysNewsController.deleteNews', 'POST', 'beidaomitu', '/news/remove/2052731472993370114', '127.0.0.1', '[2052731472993370114]', '{\"code\":200,\"message\":\"成功\"}', 0, '', 26, '2026-05-08 20:44:56');
INSERT INTO `sys_oper_log` VALUES (2052733330596163585, '下载管理', 1, 'com.xinghuiTec.controller.SysDownloadController.addDownload', 'POST', 'beidaomitu', '/download/add', '127.0.0.1', '[{\"changelog\":\"????1\\n????2\",\"description\":\"PS?????\",\"fileSize\":99999999,\"fileUrl\":\"https://example.com/test.exe\",\"name\":\"MinerU Desktop\",\"platform\":\"windows\",\"status\":\"0\",\"version\":\"v9.9.9-test\"}]', '{\"code\":200,\"data\":2052733330088652802,\"message\":\"成功\"}', 0, '', 134, '2026-05-08 20:52:18');
INSERT INTO `sys_oper_log` VALUES (2052733330986233858, '下载管理', 3, 'com.xinghuiTec.controller.SysDownloadController.deleteDownloadBatch', 'POST', 'beidaomitu', '/download/removeBatch', '127.0.0.1', '[[9999999,9999998]]', '{\"code\":200,\"message\":\"成功\"}', 0, '', 9, '2026-05-08 20:52:19');
INSERT INTO `sys_oper_log` VALUES (2052733576684367873, '下载管理', 1, 'com.xinghuiTec.controller.SysDownloadController.addDownload', 'POST', 'beidaomitu', '/download/add', '127.0.0.1', '[{\"changelog\":\"test\",\"description\":\"??????\",\"fileSize\":77777777,\"fileUrl\":\"https://example.com/test2.appimage\",\"name\":\"MinerU Desktop\",\"platform\":\"linux\",\"status\":\"0\",\"version\":\"v9.9.9-test2\"}]', '{\"code\":200,\"data\":2052733576420126721,\"message\":\"成功\"}', 0, '', 59, '2026-05-08 20:53:17');
INSERT INTO `sys_oper_log` VALUES (2052733576818585601, '下载管理', 2, 'com.xinghuiTec.controller.SysDownloadController.updateDownload', 'POST', 'beidaomitu', '/download/edit', '127.0.0.1', '[{\"changelog\":\"??????\",\"description\":\"??????(???)\",\"fileSize\":66666666,\"fileUrl\":\"https://example.com/test2-edited.appimage\",\"id\":2052733576420126721,\"name\":\"MinerU Desktop\",\"platform\":\"linux\",\"status\":\"0\",\"version\":\"v9.9.9-test2-edited\"}]', '{\"code\":200,\"message\":\"成功\"}', 0, '', 21, '2026-05-08 20:53:17');
INSERT INTO `sys_oper_log` VALUES (2052733576952803330, '下载管理', 3, 'com.xinghuiTec.controller.SysDownloadController.deleteDownload', 'POST', 'beidaomitu', '/download/remove/2052733576420126721', '127.0.0.1', '[2052733576420126721]', '{\"code\":200,\"message\":\"成功\"}', 0, '', 17, '2026-05-08 20:53:17');
INSERT INTO `sys_oper_log` VALUES (2052733723069771777, '下载管理', 3, 'com.xinghuiTec.controller.SysDownloadController.deleteDownload', 'POST', 'beidaomitu', '/download/remove/2052733330088652802', '127.0.0.1', '[2052733330088652802]', '{\"code\":200,\"message\":\"成功\"}', 0, '', 20, '2026-05-08 20:53:52');
INSERT INTO `sys_oper_log` VALUES (2052737970226196481, '菜单管理', 2, 'com.xinghuiTec.controller.SysMenuController.updateMenu', 'POST', 'beidaomitu', '/system/menu/edit', '127.0.0.1', '[{\"component\":\"system/news/index\",\"icon\":\"documentation\",\"isFrame\":1,\"menuId\":2015147980830539782,\"menuName\":\"????\",\"menuType\":\"C\",\"orderNum\":5,\"parentId\":0,\"path\":\"system/news\",\"perms\":\"system:news:list\",\"status\":\"0\",\"visible\":\"0\"}]', '{\"code\":200,\"message\":\"成功\"}', 0, '', 142, '2026-05-08 21:10:45');
INSERT INTO `sys_oper_log` VALUES (2052738563699240961, '菜单管理', 2, 'com.xinghuiTec.controller.SysMenuController.updateMenu', 'POST', 'beidaomitu', '/system/menu/edit', '127.0.0.1', '[{\"component\":\"system/download/index\",\"icon\":\"carbon:download\",\"isFrame\":1,\"menuId\":2015147980830539787,\"menuName\":\"????\",\"menuType\":\"C\",\"orderNum\":6,\"parentId\":0,\"path\":\"system/download\",\"perms\":\"system:download:list\",\"status\":\"0\",\"visible\":\"0\"}]', '', 1, '同一父菜单下已存在相同名称的菜单', 13, '2026-05-08 21:13:06');
INSERT INTO `sys_oper_log` VALUES (2052739314420936706, '菜单管理', 2, 'com.xinghuiTec.controller.SysMenuController.updateMenu', 'POST', 'beidaomitu', '/system/menu/edit', '127.0.0.1', '[{\"component\":\"system/download/index\",\"icon\":\"carbon:download\",\"isFrame\":1,\"menuId\":2015147980830539787,\"menuName\":\"????\",\"menuType\":\"C\",\"orderNum\":6,\"parentId\":0,\"path\":\"system/download\",\"perms\":\"system:download:list\",\"status\":\"0\",\"visible\":\"0\"}]', '', 1, '同一父菜单下已存在相同名称的菜单', 5, '2026-05-08 21:16:05');
INSERT INTO `sys_oper_log` VALUES (2052746009725497345, '菜单管理', 2, 'com.xinghuiTec.controller.SysMenuController.updateMenu', 'POST', 'beidaomitu', '/system/menu/edit', '127.0.0.1', '[{\"component\":\"system/download/index\",\"icon\":\"carbon:download\",\"isFrame\":1,\"menuId\":2015147980830539787,\"menuName\":\"????\",\"menuType\":\"C\",\"orderNum\":6,\"parentId\":0,\"path\":\"system/download\",\"perms\":\"system:download:list\",\"status\":\"0\",\"visible\":\"0\"}]', '', 1, '同一父菜单下已存在相同名称的菜单', 7, '2026-05-08 21:42:42');
INSERT INTO `sys_oper_log` VALUES (2056436596647489537, '用户管理', 1, 'com.xinghuiTec.controller.SysUserController.addUser', 'POST', '', '/system/user/add', '127.0.0.1', '[{\"email\":\"test@test.com\",\"mobile\":\"13900000001\",\"nickname\":\"测试用户\",\"password\":\"123456\",\"status\":1,\"username\":\"test_junit_1779127665752\"}]', '{\"code\":200,\"data\":\"1ccf8711ba3c405393a8ce10d25c6849\",\"message\":\"成功\"}', 0, '', 237, '2026-05-19 02:07:46');
INSERT INTO `sys_oper_log` VALUES (2056451676776181761, '用户管理', 1, 'com.xinghuiTec.controller.SysUserController.addUser', 'POST', '', '/system/user/add', '127.0.0.1', '[{\"email\":\"test@test.com\",\"mobile\":\"13900000001\",\"nickname\":\"测试用户\",\"password\":\"123456\",\"status\":1,\"username\":\"test_junit_1779131261018\"}]', '{\"code\":200,\"data\":\"fbff8c26b42c4ad6ab31c0f4c4eee853\",\"message\":\"成功\"}', 0, '', 343, '2026-05-19 03:07:41');
INSERT INTO `sys_oper_log` VALUES (2056452494539747329, '用户管理', 1, 'com.xinghuiTec.controller.SysUserController.addUser', 'POST', '', '/system/user/add', '127.0.0.1', '[{\"email\":\"test@test.com\",\"mobile\":\"13900000001\",\"nickname\":\"测试用户\",\"password\":\"123456\",\"status\":1,\"username\":\"test_junit_1779131456019\"}]', '{\"code\":200,\"data\":\"555976a8c9184586bffb9ac30662a3ac\",\"message\":\"成功\"}', 0, '', 299, '2026-05-19 03:10:56');
INSERT INTO `sys_oper_log` VALUES (2056452768041914369, '用户管理', 1, 'com.xinghuiTec.controller.SysUserController.addUser', 'POST', '', '/system/user/add', '127.0.0.1', '[{\"email\":\"test@test.com\",\"mobile\":\"13900000001\",\"nickname\":\"测试用户\",\"password\":\"123456\",\"status\":1,\"username\":\"test_junit_1779131521280\"}]', '{\"code\":200,\"data\":\"0a53a57ec0814e72a50df3717980947b\",\"message\":\"成功\"}', 0, '', 267, '2026-05-19 03:12:01');
INSERT INTO `sys_oper_log` VALUES (2056454273079742466, '用户管理', 1, 'com.xinghuiTec.controller.SysUserController.addUser', 'POST', '', '/system/user/add', '127.0.0.1', '[{\"email\":\"test@test.com\",\"mobile\":\"13900000001\",\"nickname\":\"测试用户\",\"password\":\"123456\",\"status\":1,\"username\":\"test_junit_1779131880027\"}]', '{\"code\":200,\"data\":\"74a011bd38e042228f92be4b94d65b25\",\"message\":\"成功\"}', 0, '', 337, '2026-05-19 03:18:00');
INSERT INTO `sys_oper_log` VALUES (2056454752513933313, '用户管理', 1, 'com.xinghuiTec.controller.SysUserController.addUser', 'POST', '', '/system/user/add', '127.0.0.1', '[{\"email\":\"test@test.com\",\"mobile\":\"13900000001\",\"nickname\":\"测试用户\",\"password\":\"123456\",\"status\":1,\"username\":\"test_junit_1779131994465\"}]', '{\"code\":200,\"data\":\"b4cac3e822664b889e1770375e21de63\",\"message\":\"成功\"}', 0, '', 226, '2026-05-19 03:19:55');

-- ----------------------------
-- Table structure for sys_oss
-- ----------------------------
DROP TABLE IF EXISTS `sys_oss`;
CREATE TABLE `sys_oss`  (
  `oss_id` bigint(20) NOT NULL AUTO_INCREMENT COMMENT '主键',
  `original_name` varchar(255) CHARACTER SET utf8mb4 COLLATE utf8mb4_0900_ai_ci NOT NULL COMMENT '原始文件名',
  `file_name` varchar(255) CHARACTER SET utf8mb4 COLLATE utf8mb4_0900_ai_ci NOT NULL COMMENT '存储文件名',
  `file_suffix` varchar(20) CHARACTER SET utf8mb4 COLLATE utf8mb4_0900_ai_ci NULL DEFAULT '' COMMENT '文件后缀',
  `url` varchar(500) CHARACTER SET utf8mb4 COLLATE utf8mb4_0900_ai_ci NOT NULL COMMENT '访问URL',
  `size` bigint(20) NULL DEFAULT 0 COMMENT '文件大小(字节)',
  `platform` varchar(50) CHARACTER SET utf8mb4 COLLATE utf8mb4_0900_ai_ci NULL DEFAULT 'minio-1' COMMENT '存储平台',
  `create_time` datetime NULL DEFAULT CURRENT_TIMESTAMP COMMENT '创建时间',
  `update_time` datetime NULL DEFAULT NULL ON UPDATE CURRENT_TIMESTAMP COMMENT '更新时间',
  `create_by` varchar(64) CHARACTER SET utf8mb4 COLLATE utf8mb4_0900_ai_ci NULL DEFAULT NULL COMMENT '创建者',
  `update_by` varchar(64) CHARACTER SET utf8mb4 COLLATE utf8mb4_0900_ai_ci NULL DEFAULT NULL COMMENT '更新者',
  `tenant_id` varchar(20) CHARACTER SET utf8mb4 COLLATE utf8mb4_0900_ai_ci NULL DEFAULT '000000' COMMENT '租户编号',
  `is_deleted` int(11) NULL DEFAULT 0 COMMENT '逻辑删除',
  PRIMARY KEY (`oss_id`) USING BTREE
) ENGINE = InnoDB AUTO_INCREMENT = 23 CHARACTER SET = utf8mb4 COLLATE = utf8mb4_0900_ai_ci COMMENT = 'OSS文件记录表' ROW_FORMAT = Dynamic;

-- ----------------------------
-- Records of sys_oss
-- ----------------------------
INSERT INTO `sys_oss` VALUES (9, 'test-oss.txt', 'ce4abf29c3c34f26884ba6b017d5cdd1.txt', 'txt', 'upload/2026/05/19/ce4abf29c3c34f26884ba6b017d5cdd1.txt', 43, 'minio-1', '2026-05-19 02:54:50', '2026-05-19 02:54:50', '-1', '-1', '000000', 0);
INSERT INTO `sys_oss` VALUES (10, 'test-stream.txt', 'c0769578a2304f4e95899f60647f5420.txt', 'txt', 'upload/2026/05/19/c0769578a2304f4e95899f60647f5420.txt', 43, 'minio-1', '2026-05-19 02:54:51', '2026-05-19 02:54:51', '-1', '-1', '000000', 0);
INSERT INTO `sys_oss` VALUES (11, 'test-oss.txt', '6e9cc5d01b3d40a2901061a7b6f76acc.txt', 'txt', 'upload/2026/05/19/6e9cc5d01b3d40a2901061a7b6f76acc.txt', 43, 'minio-1', '2026-05-19 03:00:09', '2026-05-19 03:00:09', '-1', '-1', '000000', 0);
INSERT INTO `sys_oss` VALUES (12, 'test-stream.txt', '179c9cedffec439e92229f75f4aa57b4.txt', 'txt', 'upload/2026/05/19/179c9cedffec439e92229f75f4aa57b4.txt', 43, 'minio-1', '2026-05-19 03:00:09', '2026-05-19 03:00:09', '-1', '-1', '000000', 0);
INSERT INTO `sys_oss` VALUES (13, 'test-oss.txt', 'bff96744028947188797d6762d6777e8.txt', 'txt', 'upload/2026/05/19/bff96744028947188797d6762d6777e8.txt', 43, 'minio-1', '2026-05-19 03:07:48', '2026-05-19 03:07:48', '-1', '-1', '000000', 0);
INSERT INTO `sys_oss` VALUES (14, 'test-stream.txt', '6d4e94fc2ab545949823e5e0f2f07051.txt', 'txt', 'upload/2026/05/19/6d4e94fc2ab545949823e5e0f2f07051.txt', 43, 'minio-1', '2026-05-19 03:07:49', '2026-05-19 03:07:49', '-1', '-1', '000000', 0);
INSERT INTO `sys_oss` VALUES (15, 'test-oss.txt', 'c8b4f665aab848408f5978c9a367d4d7.txt', 'txt', 'upload/2026/05/19/c8b4f665aab848408f5978c9a367d4d7.txt', 43, 'minio-1', '2026-05-19 03:11:02', '2026-05-19 03:11:02', '-1', '-1', '000000', 1);
INSERT INTO `sys_oss` VALUES (16, 'test-stream.txt', 'e028603ecce34c24b090215de324bd29.txt', 'txt', 'upload/2026/05/19/e028603ecce34c24b090215de324bd29.txt', 43, 'minio-1', '2026-05-19 03:11:02', '2026-05-19 03:11:02', '-1', '-1', '000000', 1);
INSERT INTO `sys_oss` VALUES (17, 'test-oss.txt', '3e40a7d620094f2ba4306f511b03130b.txt', 'txt', 'upload/2026/05/19/3e40a7d620094f2ba4306f511b03130b.txt', 43, 'minio-1', '2026-05-19 03:12:08', '2026-05-19 03:12:08', '-1', '-1', '000000', 1);
INSERT INTO `sys_oss` VALUES (18, 'test-stream.txt', 'b7ad2c8d9a1c468289338d30627a25e6.txt', 'txt', 'upload/2026/05/19/b7ad2c8d9a1c468289338d30627a25e6.txt', 43, 'minio-1', '2026-05-19 03:12:08', '2026-05-19 03:12:08', '-1', '-1', '000000', 1);
INSERT INTO `sys_oss` VALUES (19, 'test-oss.txt', 'dde0c11c47914bb1bb5f7977526396bb.txt', 'txt', 'upload/2026/05/19/dde0c11c47914bb1bb5f7977526396bb.txt', 43, 'minio-1', '2026-05-19 03:18:07', '2026-05-19 03:18:07', '-1', '-1', '000000', 1);
INSERT INTO `sys_oss` VALUES (20, 'test-stream.txt', '422ecc10d9544b52a55e53be98b4ff55.txt', 'txt', 'upload/2026/05/19/422ecc10d9544b52a55e53be98b4ff55.txt', 43, 'minio-1', '2026-05-19 03:18:07', '2026-05-19 03:18:07', '-1', '-1', '000000', 1);
INSERT INTO `sys_oss` VALUES (21, 'test-oss.txt', 'b6f4293651fd404c93370dd1d04333ac.txt', 'txt', 'upload/2026/05/19/b6f4293651fd404c93370dd1d04333ac.txt', 43, 'minio-1', '2026-05-19 03:20:01', '2026-05-19 03:20:01', '-1', '-1', '000000', 1);
INSERT INTO `sys_oss` VALUES (22, 'test-stream.txt', '61a720f8e4d94e4cb0fd5d09433eb967.txt', 'txt', 'upload/2026/05/19/61a720f8e4d94e4cb0fd5d09433eb967.txt', 43, 'minio-1', '2026-05-19 03:20:01', '2026-05-19 03:20:01', '-1', '-1', '000000', 1);

-- ----------------------------
-- Table structure for sys_oss_config
-- ----------------------------
DROP TABLE IF EXISTS `sys_oss_config`;
CREATE TABLE `sys_oss_config`  (
  `oss_config_id` bigint(20) NOT NULL AUTO_INCREMENT COMMENT '主键',
  `config_key` varchar(50) CHARACTER SET utf8mb4 COLLATE utf8mb4_0900_ai_ci NOT NULL COMMENT '配置标识(minio-1/aliyun-oss-1/tencent-cos-1)',
  `access_key` varchar(255) CHARACTER SET utf8mb4 COLLATE utf8mb4_0900_ai_ci NULL DEFAULT '' COMMENT '访问密钥',
  `secret_key` varchar(255) CHARACTER SET utf8mb4 COLLATE utf8mb4_0900_ai_ci NULL DEFAULT '' COMMENT '密钥',
  `bucket_name` varchar(255) CHARACTER SET utf8mb4 COLLATE utf8mb4_0900_ai_ci NULL DEFAULT '' COMMENT '存储桶名称',
  `endpoint` varchar(255) CHARACTER SET utf8mb4 COLLATE utf8mb4_0900_ai_ci NULL DEFAULT '' COMMENT '访问端点',
  `domain` varchar(255) CHARACTER SET utf8mb4 COLLATE utf8mb4_0900_ai_ci NULL DEFAULT '' COMMENT '自定义域名',
  `is_https` char(1) CHARACTER SET utf8mb4 COLLATE utf8mb4_0900_ai_ci NULL DEFAULT 'Y' COMMENT '是否HTTPS(Y/N)',
  `access_policy` char(1) CHARACTER SET utf8mb4 COLLATE utf8mb4_0900_ai_ci NULL DEFAULT '1' COMMENT '桶权限(0=私有 1=公开)',
  `remark` varchar(500) CHARACTER SET utf8mb4 COLLATE utf8mb4_0900_ai_ci NULL DEFAULT '' COMMENT '备注',
  `status` char(1) CHARACTER SET utf8mb4 COLLATE utf8mb4_0900_ai_ci NULL DEFAULT '0' COMMENT '状态(0正常 1停用)',
  `create_time` datetime NULL DEFAULT CURRENT_TIMESTAMP COMMENT '创建时间',
  `update_time` datetime NULL DEFAULT NULL ON UPDATE CURRENT_TIMESTAMP COMMENT '更新时间',
  `create_by` varchar(64) CHARACTER SET utf8mb4 COLLATE utf8mb4_0900_ai_ci NULL DEFAULT NULL COMMENT '创建者',
  `update_by` varchar(64) CHARACTER SET utf8mb4 COLLATE utf8mb4_0900_ai_ci NULL DEFAULT NULL COMMENT '更新者',
  `tenant_id` varchar(20) CHARACTER SET utf8mb4 COLLATE utf8mb4_0900_ai_ci NULL DEFAULT '000000' COMMENT '租户编号',
  PRIMARY KEY (`oss_config_id`) USING BTREE,
  UNIQUE INDEX `uk_config_key`(`config_key` ASC) USING BTREE
) ENGINE = InnoDB CHARACTER SET = utf8mb4 COLLATE = utf8mb4_0900_ai_ci COMMENT = 'OSS配置表' ROW_FORMAT = Dynamic;

-- ----------------------------
-- Records of sys_oss_config
-- ----------------------------

-- ----------------------------
-- Table structure for sys_page
-- ----------------------------
DROP TABLE IF EXISTS `sys_page`;
CREATE TABLE `sys_page`  (
  `id` bigint(20) NOT NULL AUTO_INCREMENT COMMENT 'ID',
  `page_key` varchar(100) CHARACTER SET utf8mb4 COLLATE utf8mb4_0900_ai_ci NOT NULL COMMENT '页面标识(home/api/client/ecosystem/footer)',
  `section_key` varchar(100) CHARACTER SET utf8mb4 COLLATE utf8mb4_0900_ai_ci NOT NULL COMMENT '区块标识(hero/features/extraction/community/intro)',
  `item_key` varchar(100) CHARACTER SET utf8mb4 COLLATE utf8mb4_0900_ai_ci NOT NULL COMMENT '内容项标识(title/subtitle/button_text/...)',
  `content_type` varchar(30) CHARACTER SET utf8mb4 COLLATE utf8mb4_0900_ai_ci NULL DEFAULT 'text' COMMENT '内容类型(text/image/html/json)',
  `content_value` text CHARACTER SET utf8mb4 COLLATE utf8mb4_0900_ai_ci NULL COMMENT '内容值',
  `sort_order` int(11) NULL DEFAULT 0 COMMENT '排序',
  `status` char(1) CHARACTER SET utf8mb4 COLLATE utf8mb4_0900_ai_ci NULL DEFAULT '0' COMMENT '状态(0启用 1禁用)',
  `remark` varchar(500) CHARACTER SET utf8mb4 COLLATE utf8mb4_0900_ai_ci NULL DEFAULT NULL COMMENT '备注说明',
  `create_time` datetime NULL DEFAULT CURRENT_TIMESTAMP COMMENT '创建时间',
  `update_time` datetime NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP COMMENT '更新时间',
  PRIMARY KEY (`id`) USING BTREE,
  UNIQUE INDEX `uk_page_section_item`(`page_key` ASC, `section_key` ASC, `item_key` ASC) USING BTREE
) ENGINE = InnoDB AUTO_INCREMENT = 26 CHARACTER SET = utf8mb4 COLLATE = utf8mb4_0900_ai_ci COMMENT = '页面内容管理表' ROW_FORMAT = Dynamic;

-- ----------------------------
-- Records of sys_page
-- ----------------------------
INSERT INTO `sys_page` VALUES (1, 'home', 'hero', 'title', 'text', '智能文档解析', 1, '0', '首页Hero标题', '2026-05-08 21:58:03', '2026-05-08 21:58:03');
INSERT INTO `sys_page` VALUES (2, 'home', 'hero', 'subtitle', 'text', '基于多模态大模型的新一代文档解析引擎，支持PDF/图片/PPT等多种格式，输出结构化Markdown/JSON', 2, '0', '首页Hero副标题', '2026-05-08 21:58:03', '2026-05-08 21:58:03');
INSERT INTO `sys_page` VALUES (3, 'home', 'hero', 'cta_download', 'text', '下载客户端', 3, '0', '下载按钮文字', '2026-05-08 21:58:03', '2026-05-08 21:58:03');
INSERT INTO `sys_page` VALUES (4, 'home', 'hero', 'cta_online', 'text', '在线使用', 4, '0', '在线使用按钮文字', '2026-05-08 21:58:03', '2026-05-08 21:58:03');
INSERT INTO `sys_page` VALUES (5, 'home', 'features', 'title', 'text', '核心能力', 1, '0', '特性区标题', '2026-05-08 21:58:03', '2026-05-08 21:58:03');
INSERT INTO `sys_page` VALUES (6, 'home', 'features', 'description', 'text', '六大核心能力，全面覆盖文档解析场景', 2, '0', '特性区描述', '2026-05-08 21:58:03', '2026-05-08 21:58:03');
INSERT INTO `sys_page` VALUES (7, 'home', 'extraction', 'title', 'text', '复杂内容提取', 1, '0', '提取区标题', '2026-05-08 21:58:03', '2026-05-08 21:58:03');
INSERT INTO `sys_page` VALUES (8, 'home', 'extraction', 'description', 'text', '表格还原、公式转换、化学分子式识别', 2, '0', '提取区描述', '2026-05-08 21:58:03', '2026-05-08 21:58:03');
INSERT INTO `sys_page` VALUES (9, 'home', 'community', 'title', 'text', '加入社区', 1, '0', '社区区标题', '2026-05-08 21:58:03', '2026-05-08 21:58:03');
INSERT INTO `sys_page` VALUES (10, 'home', 'community', 'description', 'text', 'MinerU 已在 GitHub 开源，获得 60k+ Stars，欢迎加入社区', 2, '0', '社区区描述', '2026-05-08 21:58:03', '2026-05-08 21:58:03');
INSERT INTO `sys_page` VALUES (11, 'home', 'community', 'cta_text', 'text', '访问 GitHub', 3, '0', '社区按钮文字', '2026-05-08 21:58:03', '2026-05-08 21:58:03');
INSERT INTO `sys_page` VALUES (12, 'home', 'community', 'cta_url', 'text', 'https://github.com/opendatalab/MinerU', 4, '0', '社区按钮链接', '2026-05-08 21:58:03', '2026-05-08 21:58:03');
INSERT INTO `sys_page` VALUES (13, 'footer', 'tools', 'title', 'text', '工具', 1, '0', '页脚工具区标题', '2026-05-08 21:58:03', '2026-05-08 21:58:03');
INSERT INTO `sys_page` VALUES (14, 'footer', 'tools', 'links', 'json', '[{\"text\":\"在线体验\",\"url\":\"/\"},{\"text\":\"客户端下载\",\"url\":\"/client\"},{\"text\":\"API服务\",\"url\":\"/api\"}]', 2, '0', '页脚工具链接', '2026-05-08 21:58:03', '2026-05-08 21:58:03');
INSERT INTO `sys_page` VALUES (15, 'footer', 'legal', 'title', 'text', '法律协议', 3, '0', '页脚法律区标题', '2026-05-08 21:58:03', '2026-05-08 21:58:03');
INSERT INTO `sys_page` VALUES (16, 'footer', 'legal', 'links', 'json', '[{\"text\":\"服务协议\",\"url\":\"/agreement\"},{\"text\":\"隐私政策\",\"url\":\"/privacy\"}]', 4, '0', '页脚法律链接', '2026-05-08 21:58:03', '2026-05-08 21:58:03');
INSERT INTO `sys_page` VALUES (17, 'footer', 'contact', 'title', 'text', '联系我们', 5, '0', '页脚联系区标题', '2026-05-08 21:58:03', '2026-05-08 21:58:03');
INSERT INTO `sys_page` VALUES (18, 'footer', 'contact', 'content', 'text', '邮箱：contact@xinghuitec.com', 6, '0', '页脚联系方式', '2026-05-08 21:58:03', '2026-05-08 21:58:03');
INSERT INTO `sys_page` VALUES (19, 'footer', 'qrcode', 'image_url', 'image', '', 7, '0', '页脚二维码图片URL', '2026-05-08 21:58:03', '2026-05-08 21:58:03');
INSERT INTO `sys_page` VALUES (20, 'api', 'intro', 'title', 'text', 'API 服务', 1, '0', 'API页标题', '2026-05-08 21:58:03', '2026-05-08 21:58:03');
INSERT INTO `sys_page` VALUES (21, 'api', 'intro', 'description', 'text', '通过 RESTful API 将 MinerU 的文档解析能力集成到您的应用中', 2, '0', 'API页描述', '2026-05-08 21:58:03', '2026-05-08 21:58:03');
INSERT INTO `sys_page` VALUES (22, 'client', 'intro', 'title', 'text', '客户端下载', 1, '0', '下载页标题', '2026-05-08 21:58:03', '2026-05-08 21:58:03');
INSERT INTO `sys_page` VALUES (23, 'client', 'intro', 'description', 'text', '选择适合您平台的 MinerU 桌面客户端', 2, '0', '下载页描述', '2026-05-08 21:58:03', '2026-05-08 21:58:03');
INSERT INTO `sys_page` VALUES (24, 'ecosystem', 'intro', 'title', 'text', '生态集成', 1, '0', '生态页标题', '2026-05-08 21:58:03', '2026-05-08 21:58:03');
INSERT INTO `sys_page` VALUES (25, 'ecosystem', 'intro', 'description', 'text', 'MinerU 与主流 AI Agent 平台的深度集成', 2, '0', '生态页描述', '2026-05-08 21:58:03', '2026-05-08 21:58:03');

-- ----------------------------
-- Table structure for sys_role
-- ----------------------------
DROP TABLE IF EXISTS `sys_role`;
CREATE TABLE `sys_role`  (
  `role_id` bigint(20) NOT NULL AUTO_INCREMENT COMMENT '角色ID',
  `tenant_id` varchar(20) CHARACTER SET utf8mb4 COLLATE utf8mb4_0900_ai_ci NULL DEFAULT '000000' COMMENT '租户编号',
  `role_name` varchar(30) CHARACTER SET utf8mb4 COLLATE utf8mb4_0900_ai_ci NOT NULL COMMENT '角色名称',
  `role_key` varchar(50) CHARACTER SET utf8mb4 COLLATE utf8mb4_0900_ai_ci NOT NULL COMMENT '角色权限字符串(admin, common)',
  `data_scope` char(1) CHARACTER SET utf8mb4 COLLATE utf8mb4_0900_ai_ci NULL DEFAULT '1' COMMENT '数据范围（1：全部数据权限 2：自定数据权限 3：本部门数据权限 4：本部门及以下数据权限）',
  `status` tinyint(4) NULL DEFAULT 1 COMMENT '角色状态（1正常 0停用）',
  `create_time` datetime NULL DEFAULT CURRENT_TIMESTAMP,
  `create_by` varchar(64) CHARACTER SET utf8mb4 COLLATE utf8mb4_0900_ai_ci NULL DEFAULT NULL COMMENT '创建者ID',
  `update_time` datetime NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
  `update_by` varchar(64) CHARACTER SET utf8mb4 COLLATE utf8mb4_0900_ai_ci NULL DEFAULT NULL COMMENT '更新者ID',
  `is_deleted` tinyint(4) NULL DEFAULT 0,
  PRIMARY KEY (`role_id`) USING BTREE,
  INDEX `idx_tenant_id`(`tenant_id` ASC) USING BTREE
) ENGINE = InnoDB AUTO_INCREMENT = 2016956169284788227 CHARACTER SET = utf8mb4 COLLATE = utf8mb4_0900_ai_ci COMMENT = '角色信息表' ROW_FORMAT = Dynamic;

-- ----------------------------
-- Records of sys_role
-- ----------------------------
INSERT INTO `sys_role` VALUES (2016901862971731969, '000000', '管理员', 'admin', '1', 1, '2026-01-29 23:50:51', NULL, '2026-01-29 23:52:30', NULL, 0);
INSERT INTO `sys_role` VALUES (2016902236600291329, '000000', '超级管理员', 'super_admin', '1', 1, '2026-01-29 23:52:20', NULL, '2026-01-29 23:52:20', NULL, 0);
INSERT INTO `sys_role` VALUES (2016956169284788226, '000000', '用户', 'user', '1', 0, '2026-01-30 03:26:39', NULL, '2026-01-30 03:26:38', NULL, 0);

-- ----------------------------
-- Table structure for sys_role_menu
-- ----------------------------
DROP TABLE IF EXISTS `sys_role_menu`;
CREATE TABLE `sys_role_menu`  (
  `role_id` bigint(20) NOT NULL COMMENT '角色ID',
  `menu_id` bigint(20) NOT NULL COMMENT '菜单ID',
  PRIMARY KEY (`role_id`, `menu_id`) USING BTREE
) ENGINE = InnoDB CHARACTER SET = utf8mb4 COLLATE = utf8mb4_0900_ai_ci COMMENT = '角色和菜单关联表' ROW_FORMAT = Dynamic;

-- ----------------------------
-- Records of sys_role_menu
-- ----------------------------
INSERT INTO `sys_role_menu` VALUES (2016901862971731969, 1);
INSERT INTO `sys_role_menu` VALUES (2016901862971731969, 2);
INSERT INTO `sys_role_menu` VALUES (2016901862971731969, 3);
INSERT INTO `sys_role_menu` VALUES (2016901862971731969, 4);
INSERT INTO `sys_role_menu` VALUES (2016901862971731969, 101);
INSERT INTO `sys_role_menu` VALUES (2016901862971731969, 102);
INSERT INTO `sys_role_menu` VALUES (2016901862971731969, 103);
INSERT INTO `sys_role_menu` VALUES (2016901862971731969, 200);
INSERT INTO `sys_role_menu` VALUES (2016901862971731969, 201);
INSERT INTO `sys_role_menu` VALUES (2016901862971731969, 202);
INSERT INTO `sys_role_menu` VALUES (2016901862971731969, 300);
INSERT INTO `sys_role_menu` VALUES (2016901862971731969, 301);
INSERT INTO `sys_role_menu` VALUES (2016901862971731969, 400);
INSERT INTO `sys_role_menu` VALUES (2016901862971731969, 401);
INSERT INTO `sys_role_menu` VALUES (2016901862971731969, 1000);
INSERT INTO `sys_role_menu` VALUES (2016901862971731969, 1001);
INSERT INTO `sys_role_menu` VALUES (2016901862971731969, 1002);
INSERT INTO `sys_role_menu` VALUES (2016901862971731969, 1003);
INSERT INTO `sys_role_menu` VALUES (2016901862971731969, 1004);
INSERT INTO `sys_role_menu` VALUES (2016901862971731969, 1005);
INSERT INTO `sys_role_menu` VALUES (2016901862971731969, 1006);
INSERT INTO `sys_role_menu` VALUES (2016901862971731969, 1007);
INSERT INTO `sys_role_menu` VALUES (2016901862971731969, 1008);
INSERT INTO `sys_role_menu` VALUES (2016901862971731969, 1009);
INSERT INTO `sys_role_menu` VALUES (2016901862971731969, 1010);
INSERT INTO `sys_role_menu` VALUES (2016901862971731969, 1011);
INSERT INTO `sys_role_menu` VALUES (2016901862971731969, 1012);
INSERT INTO `sys_role_menu` VALUES (2016901862971731969, 1013);
INSERT INTO `sys_role_menu` VALUES (2016901862971731969, 1014);
INSERT INTO `sys_role_menu` VALUES (2016901862971731969, 1015);
INSERT INTO `sys_role_menu` VALUES (2016901862971731969, 1016);
INSERT INTO `sys_role_menu` VALUES (2016901862971731969, 1017);
INSERT INTO `sys_role_menu` VALUES (2016901862971731969, 1018);
INSERT INTO `sys_role_menu` VALUES (2016901862971731969, 1019);
INSERT INTO `sys_role_menu` VALUES (2016901862971731969, 1020);
INSERT INTO `sys_role_menu` VALUES (2016901862971731969, 1021);
INSERT INTO `sys_role_menu` VALUES (2016901862971731969, 2000);
INSERT INTO `sys_role_menu` VALUES (2016901862971731969, 2001);
INSERT INTO `sys_role_menu` VALUES (2016901862971731969, 2002);
INSERT INTO `sys_role_menu` VALUES (2016901862971731969, 2003);
INSERT INTO `sys_role_menu` VALUES (2016901862971731969, 2004);
INSERT INTO `sys_role_menu` VALUES (2016901862971731969, 2005);
INSERT INTO `sys_role_menu` VALUES (2016901862971731969, 2006);
INSERT INTO `sys_role_menu` VALUES (2016901862971731969, 2007);
INSERT INTO `sys_role_menu` VALUES (2016901862971731969, 2008);
INSERT INTO `sys_role_menu` VALUES (2016901862971731969, 2009);
INSERT INTO `sys_role_menu` VALUES (2016901862971731969, 2010);
INSERT INTO `sys_role_menu` VALUES (2016901862971731969, 2011);
INSERT INTO `sys_role_menu` VALUES (2016901862971731969, 2012);
INSERT INTO `sys_role_menu` VALUES (2016901862971731969, 2013);
INSERT INTO `sys_role_menu` VALUES (2016901862971731969, 2014);
INSERT INTO `sys_role_menu` VALUES (2016901862971731969, 3000);
INSERT INTO `sys_role_menu` VALUES (2016901862971731969, 3001);
INSERT INTO `sys_role_menu` VALUES (2016901862971731969, 3002);
INSERT INTO `sys_role_menu` VALUES (2016901862971731969, 3003);
INSERT INTO `sys_role_menu` VALUES (2016901862971731969, 3004);
INSERT INTO `sys_role_menu` VALUES (2016901862971731969, 3005);
INSERT INTO `sys_role_menu` VALUES (2016901862971731969, 3006);
INSERT INTO `sys_role_menu` VALUES (2016901862971731969, 4000);
INSERT INTO `sys_role_menu` VALUES (2016901862971731969, 4001);
INSERT INTO `sys_role_menu` VALUES (2016901862971731969, 4002);
INSERT INTO `sys_role_menu` VALUES (2016901862971731969, 2015147980830539782);
INSERT INTO `sys_role_menu` VALUES (2016901862971731969, 2015147980830539783);
INSERT INTO `sys_role_menu` VALUES (2016901862971731969, 2015147980830539784);
INSERT INTO `sys_role_menu` VALUES (2016901862971731969, 2015147980830539785);
INSERT INTO `sys_role_menu` VALUES (2016901862971731969, 2015147980830539786);
INSERT INTO `sys_role_menu` VALUES (2016901862971731969, 2015147980830539787);
INSERT INTO `sys_role_menu` VALUES (2016901862971731969, 2015147980830539788);
INSERT INTO `sys_role_menu` VALUES (2016901862971731969, 2015147980830539789);
INSERT INTO `sys_role_menu` VALUES (2016901862971731969, 2015147980830539790);
INSERT INTO `sys_role_menu` VALUES (2016901862971731969, 2015147980830539791);
INSERT INTO `sys_role_menu` VALUES (2016901862971731969, 2015147980830539792);
INSERT INTO `sys_role_menu` VALUES (2016901862971731969, 2015147980830539793);
INSERT INTO `sys_role_menu` VALUES (2016901862971731969, 2015147980830539794);
INSERT INTO `sys_role_menu` VALUES (2016901862971731969, 2015147980830539795);
INSERT INTO `sys_role_menu` VALUES (2016901862971731969, 2015147980830539796);
INSERT INTO `sys_role_menu` VALUES (2016901862971731969, 2015147980830539801);
INSERT INTO `sys_role_menu` VALUES (2016901862971731969, 2015147980830539802);
INSERT INTO `sys_role_menu` VALUES (2016901862971731969, 2015147980830539803);
INSERT INTO `sys_role_menu` VALUES (2016901862971731969, 2015147980830539805);
INSERT INTO `sys_role_menu` VALUES (2016901862971731969, 2015147980830539806);
INSERT INTO `sys_role_menu` VALUES (2016901862971731969, 2015147980830539807);
INSERT INTO `sys_role_menu` VALUES (2016901862971731969, 2015147980830539808);
INSERT INTO `sys_role_menu` VALUES (2016901862971731969, 2015147980830539809);
INSERT INTO `sys_role_menu` VALUES (2016901862971731969, 2015147980830539810);
INSERT INTO `sys_role_menu` VALUES (2016901862971731969, 2015147980830539811);
INSERT INTO `sys_role_menu` VALUES (2016901862971731969, 2015147980830539812);
INSERT INTO `sys_role_menu` VALUES (2016901862971731969, 2015147980830539813);
INSERT INTO `sys_role_menu` VALUES (2016901862971731969, 2015147980830539814);
INSERT INTO `sys_role_menu` VALUES (2016901862971731969, 2015147980830539815);
INSERT INTO `sys_role_menu` VALUES (2016901862971731969, 2015147980830539816);
INSERT INTO `sys_role_menu` VALUES (2016901862971731969, 2015147980830539817);
INSERT INTO `sys_role_menu` VALUES (2016901862971731969, 2015147980830539818);
INSERT INTO `sys_role_menu` VALUES (2016901862971731969, 2015147980830539819);
INSERT INTO `sys_role_menu` VALUES (2016901862971731969, 2015147980830539820);
INSERT INTO `sys_role_menu` VALUES (2016901862971731969, 2015147980830539821);
INSERT INTO `sys_role_menu` VALUES (2016901862971731969, 2015147980830539822);
INSERT INTO `sys_role_menu` VALUES (2016901862971731969, 2015147980830539823);
INSERT INTO `sys_role_menu` VALUES (2016901862971731969, 2015147980830539824);
INSERT INTO `sys_role_menu` VALUES (2016901862971731969, 2015147980830539825);
INSERT INTO `sys_role_menu` VALUES (2016902236600291329, 1);
INSERT INTO `sys_role_menu` VALUES (2016902236600291329, 2);
INSERT INTO `sys_role_menu` VALUES (2016902236600291329, 3);
INSERT INTO `sys_role_menu` VALUES (2016902236600291329, 4);
INSERT INTO `sys_role_menu` VALUES (2016902236600291329, 101);
INSERT INTO `sys_role_menu` VALUES (2016902236600291329, 102);
INSERT INTO `sys_role_menu` VALUES (2016902236600291329, 103);
INSERT INTO `sys_role_menu` VALUES (2016902236600291329, 200);
INSERT INTO `sys_role_menu` VALUES (2016902236600291329, 201);
INSERT INTO `sys_role_menu` VALUES (2016902236600291329, 202);
INSERT INTO `sys_role_menu` VALUES (2016902236600291329, 300);
INSERT INTO `sys_role_menu` VALUES (2016902236600291329, 301);
INSERT INTO `sys_role_menu` VALUES (2016902236600291329, 400);
INSERT INTO `sys_role_menu` VALUES (2016902236600291329, 401);
INSERT INTO `sys_role_menu` VALUES (2016902236600291329, 1000);
INSERT INTO `sys_role_menu` VALUES (2016902236600291329, 1001);
INSERT INTO `sys_role_menu` VALUES (2016902236600291329, 1002);
INSERT INTO `sys_role_menu` VALUES (2016902236600291329, 1003);
INSERT INTO `sys_role_menu` VALUES (2016902236600291329, 1004);
INSERT INTO `sys_role_menu` VALUES (2016902236600291329, 1005);
INSERT INTO `sys_role_menu` VALUES (2016902236600291329, 1006);
INSERT INTO `sys_role_menu` VALUES (2016902236600291329, 1007);
INSERT INTO `sys_role_menu` VALUES (2016902236600291329, 1008);
INSERT INTO `sys_role_menu` VALUES (2016902236600291329, 1009);
INSERT INTO `sys_role_menu` VALUES (2016902236600291329, 1010);
INSERT INTO `sys_role_menu` VALUES (2016902236600291329, 1011);
INSERT INTO `sys_role_menu` VALUES (2016902236600291329, 1012);
INSERT INTO `sys_role_menu` VALUES (2016902236600291329, 1013);
INSERT INTO `sys_role_menu` VALUES (2016902236600291329, 1014);
INSERT INTO `sys_role_menu` VALUES (2016902236600291329, 1015);
INSERT INTO `sys_role_menu` VALUES (2016902236600291329, 1016);
INSERT INTO `sys_role_menu` VALUES (2016902236600291329, 1017);
INSERT INTO `sys_role_menu` VALUES (2016902236600291329, 1018);
INSERT INTO `sys_role_menu` VALUES (2016902236600291329, 1019);
INSERT INTO `sys_role_menu` VALUES (2016902236600291329, 1020);
INSERT INTO `sys_role_menu` VALUES (2016902236600291329, 1021);
INSERT INTO `sys_role_menu` VALUES (2016902236600291329, 2000);
INSERT INTO `sys_role_menu` VALUES (2016902236600291329, 2001);
INSERT INTO `sys_role_menu` VALUES (2016902236600291329, 2002);
INSERT INTO `sys_role_menu` VALUES (2016902236600291329, 2003);
INSERT INTO `sys_role_menu` VALUES (2016902236600291329, 2004);
INSERT INTO `sys_role_menu` VALUES (2016902236600291329, 2005);
INSERT INTO `sys_role_menu` VALUES (2016902236600291329, 2006);
INSERT INTO `sys_role_menu` VALUES (2016902236600291329, 2007);
INSERT INTO `sys_role_menu` VALUES (2016902236600291329, 2008);
INSERT INTO `sys_role_menu` VALUES (2016902236600291329, 2009);
INSERT INTO `sys_role_menu` VALUES (2016902236600291329, 2010);
INSERT INTO `sys_role_menu` VALUES (2016902236600291329, 2011);
INSERT INTO `sys_role_menu` VALUES (2016902236600291329, 2012);
INSERT INTO `sys_role_menu` VALUES (2016902236600291329, 2013);
INSERT INTO `sys_role_menu` VALUES (2016902236600291329, 2014);
INSERT INTO `sys_role_menu` VALUES (2016902236600291329, 3000);
INSERT INTO `sys_role_menu` VALUES (2016902236600291329, 3001);
INSERT INTO `sys_role_menu` VALUES (2016902236600291329, 3002);
INSERT INTO `sys_role_menu` VALUES (2016902236600291329, 3003);
INSERT INTO `sys_role_menu` VALUES (2016902236600291329, 3004);
INSERT INTO `sys_role_menu` VALUES (2016902236600291329, 3005);
INSERT INTO `sys_role_menu` VALUES (2016902236600291329, 3006);
INSERT INTO `sys_role_menu` VALUES (2016902236600291329, 3007);
INSERT INTO `sys_role_menu` VALUES (2016902236600291329, 3008);
INSERT INTO `sys_role_menu` VALUES (2016902236600291329, 4000);
INSERT INTO `sys_role_menu` VALUES (2016902236600291329, 4001);
INSERT INTO `sys_role_menu` VALUES (2016902236600291329, 4002);
INSERT INTO `sys_role_menu` VALUES (2016902236600291329, 2015147980830539780);
INSERT INTO `sys_role_menu` VALUES (2016902236600291329, 2015147980830539781);
INSERT INTO `sys_role_menu` VALUES (2016902236600291329, 2015147980830539782);
INSERT INTO `sys_role_menu` VALUES (2016902236600291329, 2015147980830539783);
INSERT INTO `sys_role_menu` VALUES (2016902236600291329, 2015147980830539784);
INSERT INTO `sys_role_menu` VALUES (2016902236600291329, 2015147980830539785);
INSERT INTO `sys_role_menu` VALUES (2016902236600291329, 2015147980830539786);
INSERT INTO `sys_role_menu` VALUES (2016902236600291329, 2015147980830539787);
INSERT INTO `sys_role_menu` VALUES (2016902236600291329, 2015147980830539788);
INSERT INTO `sys_role_menu` VALUES (2016902236600291329, 2015147980830539789);
INSERT INTO `sys_role_menu` VALUES (2016902236600291329, 2015147980830539790);
INSERT INTO `sys_role_menu` VALUES (2016902236600291329, 2015147980830539791);
INSERT INTO `sys_role_menu` VALUES (2016902236600291329, 2015147980830539792);
INSERT INTO `sys_role_menu` VALUES (2016902236600291329, 2015147980830539793);
INSERT INTO `sys_role_menu` VALUES (2016902236600291329, 2015147980830539794);
INSERT INTO `sys_role_menu` VALUES (2016902236600291329, 2015147980830539795);
INSERT INTO `sys_role_menu` VALUES (2016902236600291329, 2015147980830539796);
INSERT INTO `sys_role_menu` VALUES (2016902236600291329, 2015147980830539801);
INSERT INTO `sys_role_menu` VALUES (2016902236600291329, 2015147980830539802);
INSERT INTO `sys_role_menu` VALUES (2016902236600291329, 2015147980830539803);
INSERT INTO `sys_role_menu` VALUES (2016902236600291329, 2015147980830539805);
INSERT INTO `sys_role_menu` VALUES (2016902236600291329, 2015147980830539806);
INSERT INTO `sys_role_menu` VALUES (2016902236600291329, 2015147980830539807);
INSERT INTO `sys_role_menu` VALUES (2016902236600291329, 2015147980830539808);
INSERT INTO `sys_role_menu` VALUES (2016902236600291329, 2015147980830539809);
INSERT INTO `sys_role_menu` VALUES (2016902236600291329, 2015147980830539810);
INSERT INTO `sys_role_menu` VALUES (2016902236600291329, 2015147980830539811);
INSERT INTO `sys_role_menu` VALUES (2016902236600291329, 2015147980830539812);
INSERT INTO `sys_role_menu` VALUES (2016902236600291329, 2015147980830539813);
INSERT INTO `sys_role_menu` VALUES (2016902236600291329, 2015147980830539814);
INSERT INTO `sys_role_menu` VALUES (2016902236600291329, 2015147980830539815);
INSERT INTO `sys_role_menu` VALUES (2016902236600291329, 2015147980830539816);
INSERT INTO `sys_role_menu` VALUES (2016902236600291329, 2015147980830539817);
INSERT INTO `sys_role_menu` VALUES (2016902236600291329, 2015147980830539818);
INSERT INTO `sys_role_menu` VALUES (2016902236600291329, 2015147980830539819);
INSERT INTO `sys_role_menu` VALUES (2016902236600291329, 2015147980830539820);
INSERT INTO `sys_role_menu` VALUES (2016902236600291329, 2015147980830539821);
INSERT INTO `sys_role_menu` VALUES (2016902236600291329, 2015147980830539822);
INSERT INTO `sys_role_menu` VALUES (2016902236600291329, 2015147980830539823);
INSERT INTO `sys_role_menu` VALUES (2016902236600291329, 2015147980830539824);
INSERT INTO `sys_role_menu` VALUES (2016902236600291329, 2015147980830539825);

-- ----------------------------
-- Table structure for sys_tenant
-- ----------------------------
DROP TABLE IF EXISTS `sys_tenant`;
CREATE TABLE `sys_tenant`  (
  `id` bigint(20) NOT NULL COMMENT '主键ID',
  `tenant_id` varchar(20) CHARACTER SET utf8mb4 COLLATE utf8mb4_0900_ai_ci NULL DEFAULT '000000' COMMENT '租户编号',
  `contact_user_name` varchar(30) CHARACTER SET utf8mb4 COLLATE utf8mb4_0900_ai_ci NULL DEFAULT '' COMMENT '联系人',
  `contact_phone` varchar(20) CHARACTER SET utf8mb4 COLLATE utf8mb4_0900_ai_ci NULL DEFAULT '' COMMENT '联系电话',
  `company_name` varchar(100) CHARACTER SET utf8mb4 COLLATE utf8mb4_0900_ai_ci NULL DEFAULT '' COMMENT '企业名称',
  `address` varchar(255) CHARACTER SET utf8mb4 COLLATE utf8mb4_0900_ai_ci NULL DEFAULT '' COMMENT '企业地址',
  `license_number` varchar(50) CHARACTER SET utf8mb4 COLLATE utf8mb4_0900_ai_ci NULL DEFAULT '' COMMENT '统一社会信用代码',
  `domain` varchar(255) CHARACTER SET utf8mb4 COLLATE utf8mb4_0900_ai_ci NULL DEFAULT '' COMMENT '域名',
  `intro` varchar(500) CHARACTER SET utf8mb4 COLLATE utf8mb4_0900_ai_ci NULL DEFAULT '' COMMENT '备注',
  `package_id` bigint(20) NULL DEFAULT NULL COMMENT '租户套餐ID',
  `expire_time` datetime NULL DEFAULT NULL COMMENT '过期时间',
  `account_count` bigint(20) NULL DEFAULT -1 COMMENT '用户数量（-1不限制）',
  `status` char(1) CHARACTER SET utf8mb4 COLLATE utf8mb4_0900_ai_ci NULL DEFAULT '0' COMMENT '租户状态（0正常 1停用）',
  `create_time` datetime NULL DEFAULT CURRENT_TIMESTAMP COMMENT '创建时间',
  `create_by` varchar(64) CHARACTER SET utf8mb4 COLLATE utf8mb4_0900_ai_ci NULL DEFAULT NULL COMMENT '创建者ID',
  `update_time` datetime NULL DEFAULT NULL ON UPDATE CURRENT_TIMESTAMP COMMENT '更新时间',
  `update_by` varchar(64) CHARACTER SET utf8mb4 COLLATE utf8mb4_0900_ai_ci NULL DEFAULT NULL COMMENT '更新者ID',
  `is_deleted` int(11) NULL DEFAULT 0 COMMENT '逻辑删除(0未删除 1已删除)',
  PRIMARY KEY (`id`) USING BTREE,
  UNIQUE INDEX `uk_tenant_id`(`tenant_id` ASC) USING BTREE
) ENGINE = InnoDB CHARACTER SET = utf8mb4 COLLATE = utf8mb4_0900_ai_ci COMMENT = '租户信息表' ROW_FORMAT = Dynamic;

-- ----------------------------
-- Records of sys_tenant
-- ----------------------------
INSERT INTO `sys_tenant` VALUES (1, '000000', '', '', '默认租户', '', '', '', '', NULL, NULL, -1, '0', '2026-05-17 22:39:42', NULL, NULL, NULL, 0);
INSERT INTO `sys_tenant` VALUES (2056028986928656385, 'TEST01', '张三', '13800001111', '已更新的测试企业', '北京市朝阳区', '91110000MA12345678', '', '测试用租户', 2056028985271906306, NULL, 100, '0', '2026-05-17 23:08:04', NULL, '2026-05-17 23:08:04', NULL, 1);

-- ----------------------------
-- Table structure for sys_tenant_package
-- ----------------------------
DROP TABLE IF EXISTS `sys_tenant_package`;
CREATE TABLE `sys_tenant_package`  (
  `package_id` bigint(20) NOT NULL COMMENT '套餐ID',
  `package_name` varchar(100) CHARACTER SET utf8mb4 COLLATE utf8mb4_0900_ai_ci NULL DEFAULT '' COMMENT '套餐名称',
  `menu_ids` text CHARACTER SET utf8mb4 COLLATE utf8mb4_0900_ai_ci NULL COMMENT '关联菜单ID列表（逗号分隔）',
  `remark` varchar(500) CHARACTER SET utf8mb4 COLLATE utf8mb4_0900_ai_ci NULL DEFAULT '' COMMENT '备注',
  `status` char(1) CHARACTER SET utf8mb4 COLLATE utf8mb4_0900_ai_ci NULL DEFAULT '0' COMMENT '套餐状态（0正常 1停用）',
  `create_time` datetime NULL DEFAULT CURRENT_TIMESTAMP COMMENT '创建时间',
  `create_by` varchar(64) CHARACTER SET utf8mb4 COLLATE utf8mb4_0900_ai_ci NULL DEFAULT NULL COMMENT '创建者ID',
  `update_time` datetime NULL DEFAULT NULL ON UPDATE CURRENT_TIMESTAMP COMMENT '更新时间',
  `update_by` varchar(64) CHARACTER SET utf8mb4 COLLATE utf8mb4_0900_ai_ci NULL DEFAULT NULL COMMENT '更新者ID',
  `is_deleted` int(11) NULL DEFAULT 0 COMMENT '逻辑删除(0未删除 1已删除)',
  PRIMARY KEY (`package_id`) USING BTREE
) ENGINE = InnoDB CHARACTER SET = utf8mb4 COLLATE = utf8mb4_0900_ai_ci COMMENT = '租户套餐表' ROW_FORMAT = Dynamic;

-- ----------------------------
-- Records of sys_tenant_package
-- ----------------------------
INSERT INTO `sys_tenant_package` VALUES (1, '默认套餐', NULL, '', '0', '2026-05-17 22:39:42', NULL, NULL, NULL, 0);
INSERT INTO `sys_tenant_package` VALUES (2056028932280975362, '测试套餐_1779030471258', '1,2,3,4,5', '测试用套餐', '0', '2026-05-17 23:07:51', NULL, NULL, NULL, 0);
INSERT INTO `sys_tenant_package` VALUES (2056028985271906306, '测试套餐_1779030483881', '1,2,3,4,5', '已更新的测试套餐', '0', '2026-05-17 23:08:04', NULL, '2026-05-17 23:08:04', NULL, 1);

-- ----------------------------
-- Table structure for sys_user
-- ----------------------------
DROP TABLE IF EXISTS `sys_user`;
CREATE TABLE `sys_user`  (
  `user_id` varchar(64) CHARACTER SET utf8mb4 COLLATE utf8mb4_0900_ai_ci NOT NULL COMMENT '用户ID(UUID)',
  `tenant_id` varchar(20) CHARACTER SET utf8mb4 COLLATE utf8mb4_0900_ai_ci NULL DEFAULT '000000' COMMENT '租户编号',
  `username` varchar(50) CHARACTER SET utf8mb4 COLLATE utf8mb4_0900_ai_ci NOT NULL COMMENT '用户账号',
  `password` varchar(100) CHARACTER SET utf8mb4 COLLATE utf8mb4_0900_ai_ci NOT NULL COMMENT '密码(BCrypt加密)',
  `nickname` varchar(50) CHARACTER SET utf8mb4 COLLATE utf8mb4_0900_ai_ci NULL DEFAULT '' COMMENT '用户昵称',
  `email` varchar(50) CHARACTER SET utf8mb4 COLLATE utf8mb4_0900_ai_ci NULL DEFAULT '' COMMENT '用户邮箱',
  `mobile` varchar(11) CHARACTER SET utf8mb4 COLLATE utf8mb4_0900_ai_ci NULL DEFAULT '' COMMENT '手机号码',
  `avatar` varchar(255) CHARACTER SET utf8mb4 COLLATE utf8mb4_0900_ai_ci NULL DEFAULT '' COMMENT '头像地址',
  `status` tinyint(4) NULL DEFAULT 1 COMMENT '帐号状态（1正常 0停用）',
  `login_ip` varchar(128) CHARACTER SET utf8mb4 COLLATE utf8mb4_0900_ai_ci NULL DEFAULT '' COMMENT '最后登录IP',
  `login_date` datetime NULL DEFAULT NULL COMMENT '最后登录时间',
  `create_time` datetime NULL DEFAULT CURRENT_TIMESTAMP COMMENT '创建时间',
  `create_by` varchar(64) CHARACTER SET utf8mb4 COLLATE utf8mb4_0900_ai_ci NULL DEFAULT NULL COMMENT '创建者ID',
  `update_time` datetime NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP COMMENT '更新时间',
  `update_by` varchar(64) CHARACTER SET utf8mb4 COLLATE utf8mb4_0900_ai_ci NULL DEFAULT NULL COMMENT '更新者ID',
  `is_deleted` tinyint(4) NULL DEFAULT 0 COMMENT '逻辑删除(0存在 1删除)',
  PRIMARY KEY (`user_id`) USING BTREE,
  UNIQUE INDEX `idx_username`(`username` ASC) USING BTREE,
  INDEX `idx_tenant_id`(`tenant_id` ASC) USING BTREE
) ENGINE = InnoDB CHARACTER SET = utf8mb4 COLLATE = utf8mb4_0900_ai_ci COMMENT = '用户信息表' ROW_FORMAT = Dynamic;

-- ----------------------------
-- Records of sys_user
-- ----------------------------
INSERT INTO `sys_user` VALUES ('0a53a57ec0814e72a50df3717980947b', '000000', 'test_junit_1779131521280', '$2a$10$wcdy4ibT15CoFC.VTtr/f.vYyyzHLQzhvknJMpMMhU59Z3ij6eqRq', '测试用户', 'test@test.com', '13900000001', '', 1, '', NULL, '2026-05-19 03:12:01', '-1', '2026-05-19 03:12:01', '-1', 0);
INSERT INTO `sys_user` VALUES ('1ccf8711ba3c405393a8ce10d25c6849', '000000', 'test_junit_1779127665752', '$2a$10$fZWDQTREM9v6fSwWw6bsQeQdcruKVCx/lCpt6BhlUGYHBIQjX/tqW', '测试用户', 'test@test.com', '13900000001', '', 1, '', NULL, '2026-05-19 02:07:46', '-1', '2026-05-19 02:07:46', '-1', 0);
INSERT INTO `sys_user` VALUES ('555976a8c9184586bffb9ac30662a3ac', '000000', 'test_junit_1779131456019', '$2a$10$FehLZqSWY2ZvlJ67R.vxIOoNovAx9AfYN8zJwacx0hX4M1kTPl8EK', '测试用户', 'test@test.com', '13900000001', '', 1, '', NULL, '2026-05-19 03:10:56', '-1', '2026-05-19 03:10:56', '-1', 0);
INSERT INTO `sys_user` VALUES ('74a011bd38e042228f92be4b94d65b25', '000000', 'test_junit_1779131880027', '$2a$10$Tztxg8dtgM3C5lrm1FGWn.geYQg0.rd24fMCYvTtTSGVUmJg1VH6y', '测试用户', 'test@test.com', '13900000001', '', 1, '', NULL, '2026-05-19 03:18:00', '-1', '2026-05-19 03:18:00', '-1', 0);
INSERT INTO `sys_user` VALUES ('78aa9741e1db4375b7284fe4870a72b5', '000000', 'phone_test_1779030393714', '$2a$10$CQS.jgMWptdu/MkyebBN3uXokWuUADpasXG1WEnMVs2FCLFeHHa/e', '手机号测试用户', '13900001111@test.com', '13900001111', '', 1, '', NULL, '2026-05-17 23:06:34', NULL, '2026-05-17 23:06:34', NULL, 1);
INSERT INTO `sys_user` VALUES ('853d4f744b544dfa941a7ba61d6f371b', '000000', 'beidaomitu', '$2a$10$FjsXH97MJw6fXmgoNRAFl.FA27YRkF81KENfj2pZySBkOc9C/8ZNu', '北岛', 'beidaomitu233@gmail.com', '', '', 1, '', NULL, '2026-01-30 00:08:04', NULL, '2026-01-30 00:08:04', NULL, 0);
INSERT INTO `sys_user` VALUES ('b4cac3e822664b889e1770375e21de63', '000000', 'test_junit_1779131994465', '$2a$10$.qv7Hjk5jCe1pCiaScRbyuDd7tQ47RHU09EHpiwGg5SLmJsom8KmW', '测试用户', 'test@test.com', '13900000001', '', 1, '', NULL, '2026-05-19 03:19:55', '-1', '2026-05-19 03:19:55', '-1', 0);
INSERT INTO `sys_user` VALUES ('e84ee658f0e1421892e2f69cd31fb3ef', '000000', 'user', '$2a$10$ZwpomIxdlVEVdVz4jFkFCugR9XhWhgmRILRUGcAS/hmJcNpcl.Khq', '演示账号', 'testuser@example.com', '13800138000', 'https://example.com/avatar.jpg', 1, '127.0.0.1', '2026-03-12 00:25:17', '2026-03-12 00:25:17', NULL, '2026-03-12 00:25:17', NULL, 0);
INSERT INTO `sys_user` VALUES ('fbff8c26b42c4ad6ab31c0f4c4eee853', '000000', 'test_junit_1779131261018', '$2a$10$Ghb.lRG9GBZwhuoOgp4UWuxGlEL2w7McZFFBnSI4e72dH2sFsFCwC', '测试用户', 'test@test.com', '13900000001', '', 1, '', NULL, '2026-05-19 03:07:41', '-1', '2026-05-19 03:07:41', '-1', 0);

-- ----------------------------
-- Table structure for sys_user_role
-- ----------------------------
DROP TABLE IF EXISTS `sys_user_role`;
CREATE TABLE `sys_user_role`  (
  `user_id` varchar(64) CHARACTER SET utf8mb4 COLLATE utf8mb4_0900_ai_ci NOT NULL COMMENT '用户ID(UUID)',
  `role_id` bigint(20) NOT NULL COMMENT '角色ID',
  PRIMARY KEY (`user_id`, `role_id`) USING BTREE
) ENGINE = InnoDB CHARACTER SET = utf8mb4 COLLATE = utf8mb4_0900_ai_ci COMMENT = '用户和角色关联表' ROW_FORMAT = Dynamic;

-- ----------------------------
-- Records of sys_user_role
-- ----------------------------
INSERT INTO `sys_user_role` VALUES ('853d4f744b544dfa941a7ba61d6f371b', 2016901862971731969);
INSERT INTO `sys_user_role` VALUES ('853d4f744b544dfa941a7ba61d6f371b', 2016902236600291329);
INSERT INTO `sys_user_role` VALUES ('e84ee658f0e1421892e2f69cd31fb3ef', 2016902236600291329);

SET FOREIGN_KEY_CHECKS = 1;
