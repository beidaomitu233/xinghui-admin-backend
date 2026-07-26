-- =====================================================================
-- XingHui Admin - Flyway V1 基线 Schema
-- 全部系统表结构（sys_*，共 19 张）
-- 由 sql/xinghui_admin.sql 历史全量脚本整理生成；此后所有变更须通过
-- 新增 V<yyyyMMdd>_<nn>__<描述>.sql 迁移脚本演进，禁止直接修改本文件。
-- =====================================================================

SET NAMES utf8mb4;

-- ----------------------------
-- sys_config
-- ----------------------------
CREATE TABLE `sys_config`  (
  `config_id` int(11) NOT NULL AUTO_INCREMENT COMMENT '参数主键',
  `tenant_id` varchar(20) NULL DEFAULT '000000' COMMENT '租户编号',
  `config_name` varchar(100) NULL DEFAULT '' COMMENT '参数名称',
  `config_key` varchar(100) NULL DEFAULT '' COMMENT '参数键名(sys.account.captchaOn)',
  `config_value` varchar(500) NULL DEFAULT '' COMMENT '参数键值(true/false)',
  `config_type` char(1) NULL DEFAULT 'N' COMMENT '系统内置（Y是 N否）',
  `remark` varchar(500) NULL DEFAULT NULL COMMENT '备注',
  `create_time` datetime NULL DEFAULT CURRENT_TIMESTAMP,
  `create_by` varchar(64) NULL DEFAULT NULL COMMENT '创建者ID',
  `update_time` datetime NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
  `update_by` varchar(64) NULL DEFAULT NULL COMMENT '更新者ID',
  `is_deleted` tinyint(4) NULL DEFAULT 0 COMMENT '逻辑删除(0存在 1删除)',
  PRIMARY KEY (`config_id`) USING BTREE,
  UNIQUE INDEX `idx_config_key`(`config_key` ASC) USING BTREE,
  INDEX `idx_tenant_id`(`tenant_id` ASC) USING BTREE
) ENGINE = InnoDB AUTO_INCREMENT = 6 CHARACTER SET = utf8mb4 COMMENT = '参数配置表' ROW_FORMAT = Dynamic;

-- ----------------------------
-- sys_download
-- ----------------------------
CREATE TABLE `sys_download`  (
  `id` bigint(20) NOT NULL AUTO_INCREMENT COMMENT 'ID',
  `name` varchar(255) NOT NULL COMMENT '软件名称',
  `version` varchar(50) NOT NULL COMMENT '版本号',
  `platform` varchar(50) NOT NULL COMMENT '平台(windows/macos/linux)',
  `file_url` varchar(500) NOT NULL COMMENT '下载地址',
  `file_size` bigint(20) NULL DEFAULT NULL COMMENT '文件大小(字节)',
  `description` varchar(1000) NULL DEFAULT NULL COMMENT '描述',
  `changelog` text NULL COMMENT '更新日志',
  `status` char(1) NULL DEFAULT '0' COMMENT '状态(0启用 1禁用)',
  `download_count` int(11) NULL DEFAULT 0 COMMENT '下载次数',
  `create_time` datetime NULL DEFAULT CURRENT_TIMESTAMP COMMENT '创建时间',
  `update_time` datetime NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP COMMENT '更新时间',
  PRIMARY KEY (`id`) USING BTREE,
  INDEX `idx_platform`(`platform` ASC) USING BTREE,
  INDEX `idx_status`(`status` ASC) USING BTREE
) ENGINE = InnoDB AUTO_INCREMENT = 2052733576420126722 CHARACTER SET = utf8mb4 COMMENT = '下载管理表' ROW_FORMAT = Dynamic;

-- ----------------------------
-- sys_file
-- ----------------------------
CREATE TABLE `sys_file`  (
  `id` bigint(20) NOT NULL AUTO_INCREMENT COMMENT '文件id',
  `tenant_id` varchar(20) NULL DEFAULT '000000' COMMENT '租户编号',
  `url` varchar(500) NOT NULL COMMENT '文件访问地址',
  `size` bigint(20) NULL DEFAULT 0 COMMENT '文件大小(字节)',
  `filename` varchar(255) NULL DEFAULT '' COMMENT '原始文件名',
  `original_name` varchar(255) NULL DEFAULT '' COMMENT '存储文件名',
  `base_path` varchar(255) NULL DEFAULT '' COMMENT '基础存储路径',
  `path` varchar(255) NULL DEFAULT '' COMMENT '存储路径',
  `ext` varchar(32) NULL DEFAULT '' COMMENT '文件扩展名',
  `platform` varchar(32) NOT NULL COMMENT '存储平台(minio-local, aliyun-oss)',
  `th_url` varchar(500) NULL DEFAULT '' COMMENT '缩略图访问路径',
  `th_filename` varchar(255) NULL DEFAULT '' COMMENT '缩略图名称',
  `th_size` bigint(20) NULL DEFAULT 0 COMMENT '缩略图大小',
  `object_id` varchar(32) NULL DEFAULT '' COMMENT '文件对象ID(可选)',
  `create_time` datetime NULL DEFAULT CURRENT_TIMESTAMP,
  `create_by` varchar(64) NULL DEFAULT NULL COMMENT '创建者ID',
  `update_time` datetime NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP COMMENT '更新时间',
  `update_by` varchar(64) NULL DEFAULT NULL COMMENT '更新者ID',
  `is_deleted` tinyint(4) NULL DEFAULT 0 COMMENT '逻辑删除(0存在 1删除)',
  PRIMARY KEY (`id`) USING BTREE,
  INDEX `idx_tenant_id`(`tenant_id` ASC) USING BTREE
) ENGINE = InnoDB AUTO_INCREMENT = 1779132000598 CHARACTER SET = utf8mb4 COMMENT = '文件记录表' ROW_FORMAT = Dynamic;

-- ----------------------------
-- sys_file_part
-- ----------------------------
CREATE TABLE `sys_file_part`  (
  `id` varchar(64) NOT NULL COMMENT '分片ID',
  `platform` varchar(50) NOT NULL COMMENT '存储平台',
  `upload_id` varchar(255) NOT NULL COMMENT '上传ID',
  `e_tag` varchar(255) NULL DEFAULT NULL COMMENT 'ETag',
  `part_number` int(11) NOT NULL COMMENT '分片号',
  `part_size` bigint(20) NULL DEFAULT 0 COMMENT '分片大小',
  `hash` varchar(255) NULL DEFAULT NULL COMMENT 'Hash值',
  `create_time` datetime NULL DEFAULT CURRENT_TIMESTAMP COMMENT '创建时间',
  PRIMARY KEY (`id`) USING BTREE
) ENGINE = InnoDB CHARACTER SET = utf8mb4 COMMENT = '文件分片信息表' ROW_FORMAT = Dynamic;

-- ----------------------------
-- sys_job
-- ----------------------------
CREATE TABLE `sys_job`  (
  `job_id` bigint(20) NOT NULL AUTO_INCREMENT COMMENT '任务ID',
  `tenant_id` varchar(20) NULL DEFAULT '000000' COMMENT '租户编号',
  `job_name` varchar(64) NOT NULL COMMENT '任务名称',
  `job_group` varchar(64) NOT NULL DEFAULT 'DEFAULT' COMMENT '任务组名',
  `invoke_target` varchar(500) NOT NULL COMMENT '调用目标字符串',
  `cron_expression` varchar(255) NULL DEFAULT '' COMMENT 'cron执行表达式',
  `misfire_policy` varchar(20) NULL DEFAULT '3' COMMENT '计划执行错误策略（1立即执行 2执行一次 3放弃执行）',
  `concurrent` char(1) NULL DEFAULT '1' COMMENT '是否并发执行（0允许 1禁止）',
  `status` char(1) NULL DEFAULT '0' COMMENT '状态（0正常 1暂停）',
  `create_time` datetime NULL DEFAULT CURRENT_TIMESTAMP,
  `create_by` varchar(64) NULL DEFAULT NULL COMMENT '创建者ID',
  `update_time` datetime NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
  `update_by` varchar(64) NULL DEFAULT NULL COMMENT '更新者ID',
  `is_deleted` tinyint(4) NULL DEFAULT 0 COMMENT '逻辑删除(0存在 1删除)',
  `remark` varchar(500) NULL DEFAULT '' COMMENT '备注信息',
  PRIMARY KEY (`job_id`, `job_name`, `job_group`) USING BTREE,
  INDEX `idx_tenant_id`(`tenant_id` ASC) USING BTREE
) ENGINE = InnoDB AUTO_INCREMENT = 3 CHARACTER SET = utf8mb4 COMMENT = '定时任务调度表' ROW_FORMAT = Dynamic;

-- ----------------------------
-- sys_job_log
-- ----------------------------
CREATE TABLE `sys_job_log`  (
  `job_log_id` bigint(20) NOT NULL AUTO_INCREMENT COMMENT '任务日志ID',
  `job_name` varchar(64) NOT NULL COMMENT '任务名称',
  `job_group` varchar(64) NOT NULL COMMENT '任务组名',
  `invoke_target` varchar(500) NOT NULL COMMENT '调用目标字符串',
  `status` char(1) NULL DEFAULT '0' COMMENT '执行状态（0正常 1失败）',
  `exception_info` varchar(2000) NULL DEFAULT '' COMMENT '异常信息',
  `create_time` datetime NULL DEFAULT CURRENT_TIMESTAMP COMMENT '执行时间',
  `cost_time` bigint(20) NULL DEFAULT 0 COMMENT '耗时(毫秒)',
  PRIMARY KEY (`job_log_id`) USING BTREE
) ENGINE = InnoDB CHARACTER SET = utf8mb4 COMMENT = '定时任务调度日志表' ROW_FORMAT = Dynamic;

-- ----------------------------
-- sys_menu
-- ----------------------------
CREATE TABLE `sys_menu`  (
  `menu_id` bigint(20) NOT NULL AUTO_INCREMENT COMMENT '菜单ID',
  `menu_name` varchar(50) NOT NULL COMMENT '菜单名称',
  `parent_id` bigint(20) NULL DEFAULT 0 COMMENT '父菜单ID',
  `order_num` int(11) NULL DEFAULT 0 COMMENT '显示顺序',
  `path` varchar(200) NULL DEFAULT '' COMMENT '路由地址',
  `component` varchar(255) NULL DEFAULT NULL COMMENT '组件路径(views/system/user/index)',
  `is_frame` tinyint(4) NULL DEFAULT 0 COMMENT '是否为外链（0是 1否）',
  `menu_type` char(1) NULL DEFAULT '' COMMENT '菜单类型（M目录 C菜单 F按钮）',
  `visible` char(1) NULL DEFAULT '0' COMMENT '菜单状态（0显示 1隐藏）',
  `status` char(1) NULL DEFAULT '0' COMMENT '菜单状态（0正常 1停用）',
  `perms` varchar(100) NULL DEFAULT NULL COMMENT '权限标识(system:user:list)',
  `icon` varchar(100) NULL DEFAULT '#' COMMENT '菜单图标',
  `create_time` datetime NULL DEFAULT CURRENT_TIMESTAMP,
  `create_by` varchar(64) NULL DEFAULT NULL COMMENT '创建者ID',
  `update_time` datetime NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
  `update_by` varchar(64) NULL DEFAULT NULL COMMENT '更新者ID',
  `is_deleted` tinyint(4) NULL DEFAULT 0,
  PRIMARY KEY (`menu_id`) USING BTREE
) ENGINE = InnoDB AUTO_INCREMENT = 2015147980830539826 CHARACTER SET = utf8mb4 COMMENT = '菜单权限表' ROW_FORMAT = Dynamic;

-- ----------------------------
-- sys_news
-- ----------------------------
CREATE TABLE `sys_news`  (
  `news_id` bigint(20) NOT NULL AUTO_INCREMENT COMMENT '资讯ID',
  `title` varchar(255) NOT NULL COMMENT '标题',
  `summary` varchar(500) NULL DEFAULT NULL COMMENT '摘要',
  `content` longtext NULL COMMENT '内容(Markdown/HTML)',
  `cover_image` varchar(500) NULL DEFAULT NULL COMMENT '封面图',
  `category` varchar(50) NULL DEFAULT 'general' COMMENT '分类',
  `tags` varchar(500) NULL DEFAULT NULL COMMENT '标签(JSON数组)',
  `status` char(1) NULL DEFAULT '0' COMMENT '状态(0发布 1草稿 2下架)',
  `view_count` int(11) NULL DEFAULT 0 COMMENT '浏览次数',
  `create_by` varchar(64) NULL DEFAULT NULL COMMENT '创建者',
  `create_time` datetime NULL DEFAULT CURRENT_TIMESTAMP COMMENT '创建时间',
  `update_time` datetime NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP COMMENT '更新时间',
  `publish_time` datetime NULL DEFAULT NULL COMMENT '发布时间',
  PRIMARY KEY (`news_id`) USING BTREE,
  INDEX `idx_category`(`category` ASC) USING BTREE,
  INDEX `idx_status`(`status` ASC) USING BTREE,
  INDEX `idx_publish_time`(`publish_time` ASC) USING BTREE
) ENGINE = InnoDB AUTO_INCREMENT = 2052731472993370115 CHARACTER SET = utf8mb4 COMMENT = '资讯表' ROW_FORMAT = Dynamic;

-- ----------------------------
-- sys_notice
-- ----------------------------
CREATE TABLE `sys_notice`  (
  `notice_id` int(11) NOT NULL AUTO_INCREMENT COMMENT '公告ID',
  `tenant_id` varchar(20) NULL DEFAULT '000000' COMMENT '租户编号',
  `notice_title` varchar(50) NOT NULL COMMENT '公告标题',
  `notice_type` char(1) NOT NULL COMMENT '公告类型（1通知卡片 2强弹窗）',
  `notice_content` longblob NULL COMMENT '公告内容(HTML/Text)',
  `status` char(1) NULL DEFAULT '0' COMMENT '公告状态（0正常 1关闭）',
  `start_time` datetime NULL DEFAULT NULL COMMENT '生效开始时间',
  `end_time` datetime NULL DEFAULT NULL COMMENT '生效结束时间',
  `create_by` varchar(64) NULL DEFAULT '' COMMENT '创建者',
  `create_time` datetime NULL DEFAULT CURRENT_TIMESTAMP,
  `update_time` datetime NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
  `update_by` varchar(64) NULL DEFAULT NULL COMMENT '更新者ID',
  `is_deleted` tinyint(4) NULL DEFAULT 0,
  PRIMARY KEY (`notice_id`) USING BTREE,
  INDEX `idx_tenant_id`(`tenant_id` ASC) USING BTREE
) ENGINE = InnoDB AUTO_INCREMENT = 2009116675 CHARACTER SET = utf8mb4 COMMENT = '通知公告表' ROW_FORMAT = Dynamic;

-- ----------------------------
-- sys_oper_log
-- ----------------------------
CREATE TABLE `sys_oper_log`  (
  `oper_id` bigint(20) NOT NULL AUTO_INCREMENT COMMENT '日志主键',
  `title` varchar(50) NULL DEFAULT '' COMMENT '模块标题(如: 用户管理)',
  `business_type` int(11) NULL DEFAULT 0 COMMENT '业务类型(0其它 1新增 2修改 3删除 4授权 5导出 6导入)',
  `method` varchar(100) NULL DEFAULT '' COMMENT '方法名称',
  `request_method` varchar(10) NULL DEFAULT '' COMMENT '请求方式(GET/POST)',
  `oper_name` varchar(50) NULL DEFAULT '' COMMENT '操作人员',
  `oper_url` varchar(255) NULL DEFAULT '' COMMENT '请求URL',
  `oper_ip` varchar(128) NULL DEFAULT '' COMMENT '主机地址',
  `oper_param` varchar(2000) NULL DEFAULT '' COMMENT '请求参数',
  `json_result` varchar(2000) NULL DEFAULT '' COMMENT '返回参数',
  `status` int(11) NULL DEFAULT 0 COMMENT '操作状态(0正常 1异常)',
  `error_msg` varchar(2000) NULL DEFAULT '' COMMENT '错误消息',
  `cost_time` bigint(20) NULL DEFAULT 0 COMMENT '消耗时间(毫秒)',
  `oper_time` datetime NULL DEFAULT CURRENT_TIMESTAMP COMMENT '操作时间',
  PRIMARY KEY (`oper_id`) USING BTREE,
  INDEX `idx_oper_time`(`oper_time` ASC) USING BTREE
) ENGINE = InnoDB AUTO_INCREMENT = 2056454752513933314 CHARACTER SET = utf8mb4 COMMENT = '操作日志记录' ROW_FORMAT = Dynamic;

-- ----------------------------
-- sys_oss
-- ----------------------------
CREATE TABLE `sys_oss`  (
  `oss_id` bigint(20) NOT NULL AUTO_INCREMENT COMMENT '主键',
  `original_name` varchar(255) NOT NULL COMMENT '原始文件名',
  `file_name` varchar(255) NOT NULL COMMENT '存储文件名',
  `file_suffix` varchar(20) NULL DEFAULT '' COMMENT '文件后缀',
  `url` varchar(500) NOT NULL COMMENT '访问URL',
  `size` bigint(20) NULL DEFAULT 0 COMMENT '文件大小(字节)',
  `platform` varchar(50) NULL DEFAULT 'minio-1' COMMENT '存储平台',
  `create_time` datetime NULL DEFAULT CURRENT_TIMESTAMP COMMENT '创建时间',
  `update_time` datetime NULL DEFAULT NULL ON UPDATE CURRENT_TIMESTAMP COMMENT '更新时间',
  `create_by` varchar(64) NULL DEFAULT NULL COMMENT '创建者',
  `update_by` varchar(64) NULL DEFAULT NULL COMMENT '更新者',
  `tenant_id` varchar(20) NULL DEFAULT '000000' COMMENT '租户编号',
  `is_deleted` int(11) NULL DEFAULT 0 COMMENT '逻辑删除',
  PRIMARY KEY (`oss_id`) USING BTREE
) ENGINE = InnoDB AUTO_INCREMENT = 23 CHARACTER SET = utf8mb4 COMMENT = 'OSS文件记录表' ROW_FORMAT = Dynamic;

-- ----------------------------
-- sys_oss_config
-- ----------------------------
CREATE TABLE `sys_oss_config`  (
  `oss_config_id` bigint(20) NOT NULL AUTO_INCREMENT COMMENT '主键',
  `config_key` varchar(50) NOT NULL COMMENT '配置标识(minio-1/aliyun-oss-1/tencent-cos-1)',
  `access_key` varchar(255) NULL DEFAULT '' COMMENT '访问密钥',
  `secret_key` varchar(255) NULL DEFAULT '' COMMENT '密钥',
  `bucket_name` varchar(255) NULL DEFAULT '' COMMENT '存储桶名称',
  `endpoint` varchar(255) NULL DEFAULT '' COMMENT '访问端点',
  `domain` varchar(255) NULL DEFAULT '' COMMENT '自定义域名',
  `is_https` char(1) NULL DEFAULT 'Y' COMMENT '是否HTTPS(Y/N)',
  `access_policy` char(1) NULL DEFAULT '1' COMMENT '桶权限(0=私有 1=公开)',
  `remark` varchar(500) NULL DEFAULT '' COMMENT '备注',
  `status` char(1) NULL DEFAULT '0' COMMENT '状态(0正常 1停用)',
  `create_time` datetime NULL DEFAULT CURRENT_TIMESTAMP COMMENT '创建时间',
  `update_time` datetime NULL DEFAULT NULL ON UPDATE CURRENT_TIMESTAMP COMMENT '更新时间',
  `create_by` varchar(64) NULL DEFAULT NULL COMMENT '创建者',
  `update_by` varchar(64) NULL DEFAULT NULL COMMENT '更新者',
  `tenant_id` varchar(20) NULL DEFAULT '000000' COMMENT '租户编号',
  `is_deleted` tinyint(4) NOT NULL DEFAULT 0 COMMENT '逻辑删除(0存在1删除)',
  PRIMARY KEY (`oss_config_id`) USING BTREE,
  UNIQUE INDEX `uk_config_key`(`config_key` ASC) USING BTREE
) ENGINE = InnoDB CHARACTER SET = utf8mb4 COMMENT = 'OSS配置表' ROW_FORMAT = Dynamic;

-- ----------------------------
-- sys_page
-- ----------------------------
CREATE TABLE `sys_page`  (
  `id` bigint(20) NOT NULL AUTO_INCREMENT COMMENT 'ID',
  `page_key` varchar(100) NOT NULL COMMENT '页面标识(home/api/client/ecosystem/footer)',
  `section_key` varchar(100) NOT NULL COMMENT '区块标识(hero/features/extraction/community/intro)',
  `item_key` varchar(100) NOT NULL COMMENT '内容项标识(title/subtitle/button_text/...)',
  `content_type` varchar(30) NULL DEFAULT 'text' COMMENT '内容类型(text/image/html/json)',
  `content_value` text NULL COMMENT '内容值',
  `sort_order` int(11) NULL DEFAULT 0 COMMENT '排序',
  `status` char(1) NULL DEFAULT '0' COMMENT '状态(0启用 1禁用)',
  `remark` varchar(500) NULL DEFAULT NULL COMMENT '备注说明',
  `create_time` datetime NULL DEFAULT CURRENT_TIMESTAMP COMMENT '创建时间',
  `update_time` datetime NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP COMMENT '更新时间',
  PRIMARY KEY (`id`) USING BTREE,
  UNIQUE INDEX `uk_page_section_item`(`page_key` ASC, `section_key` ASC, `item_key` ASC) USING BTREE
) ENGINE = InnoDB AUTO_INCREMENT = 26 CHARACTER SET = utf8mb4 COMMENT = '页面内容管理表' ROW_FORMAT = Dynamic;

-- ----------------------------
-- sys_role
-- ----------------------------
CREATE TABLE `sys_role`  (
  `role_id` bigint(20) NOT NULL AUTO_INCREMENT COMMENT '角色ID',
  `tenant_id` varchar(20) NULL DEFAULT '000000' COMMENT '租户编号',
  `role_name` varchar(30) NOT NULL COMMENT '角色名称',
  `role_key` varchar(50) NOT NULL COMMENT '角色权限字符串(admin, common)',
  `data_scope` char(1) NULL DEFAULT '1' COMMENT '数据范围（1：全部数据权限 2：自定数据权限 3：本部门数据权限 4：本部门及以下数据权限）',
  `status` tinyint(4) NULL DEFAULT 1 COMMENT '角色状态（1正常 0停用）',
  `create_time` datetime NULL DEFAULT CURRENT_TIMESTAMP,
  `create_by` varchar(64) NULL DEFAULT NULL COMMENT '创建者ID',
  `update_time` datetime NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
  `update_by` varchar(64) NULL DEFAULT NULL COMMENT '更新者ID',
  `is_deleted` tinyint(4) NULL DEFAULT 0,
  PRIMARY KEY (`role_id`) USING BTREE,
  INDEX `idx_tenant_id`(`tenant_id` ASC) USING BTREE
) ENGINE = InnoDB AUTO_INCREMENT = 2016956169284788227 CHARACTER SET = utf8mb4 COMMENT = '角色信息表' ROW_FORMAT = Dynamic;

-- ----------------------------
-- sys_role_menu
-- ----------------------------
CREATE TABLE `sys_role_menu`  (
  `role_id` bigint(20) NOT NULL COMMENT '角色ID',
  `menu_id` bigint(20) NOT NULL COMMENT '菜单ID',
  PRIMARY KEY (`role_id`, `menu_id`) USING BTREE
) ENGINE = InnoDB CHARACTER SET = utf8mb4 COMMENT = '角色和菜单关联表' ROW_FORMAT = Dynamic;

-- ----------------------------
-- sys_tenant
-- ----------------------------
CREATE TABLE `sys_tenant`  (
  `id` bigint(20) NOT NULL COMMENT '主键ID',
  `tenant_id` varchar(20) NULL DEFAULT '000000' COMMENT '租户编号',
  `contact_user_name` varchar(30) NULL DEFAULT '' COMMENT '联系人',
  `contact_phone` varchar(20) NULL DEFAULT '' COMMENT '联系电话',
  `company_name` varchar(100) NULL DEFAULT '' COMMENT '企业名称',
  `address` varchar(255) NULL DEFAULT '' COMMENT '企业地址',
  `license_number` varchar(50) NULL DEFAULT '' COMMENT '统一社会信用代码',
  `domain` varchar(255) NULL DEFAULT '' COMMENT '域名',
  `intro` varchar(500) NULL DEFAULT '' COMMENT '备注',
  `package_id` bigint(20) NULL DEFAULT NULL COMMENT '租户套餐ID',
  `expire_time` datetime NULL DEFAULT NULL COMMENT '过期时间',
  `account_count` bigint(20) NULL DEFAULT -1 COMMENT '用户数量（-1不限制）',
  `status` char(1) NULL DEFAULT '0' COMMENT '租户状态（0正常 1停用）',
  `create_time` datetime NULL DEFAULT CURRENT_TIMESTAMP COMMENT '创建时间',
  `create_by` varchar(64) NULL DEFAULT NULL COMMENT '创建者ID',
  `update_time` datetime NULL DEFAULT NULL ON UPDATE CURRENT_TIMESTAMP COMMENT '更新时间',
  `update_by` varchar(64) NULL DEFAULT NULL COMMENT '更新者ID',
  `is_deleted` int(11) NULL DEFAULT 0 COMMENT '逻辑删除(0未删除 1已删除)',
  PRIMARY KEY (`id`) USING BTREE,
  UNIQUE INDEX `uk_tenant_id`(`tenant_id` ASC) USING BTREE
) ENGINE = InnoDB CHARACTER SET = utf8mb4 COMMENT = '租户信息表' ROW_FORMAT = Dynamic;

-- ----------------------------
-- sys_tenant_package
-- ----------------------------
CREATE TABLE `sys_tenant_package`  (
  `package_id` bigint(20) NOT NULL COMMENT '套餐ID',
  `package_name` varchar(100) NULL DEFAULT '' COMMENT '套餐名称',
  `menu_ids` text NULL COMMENT '关联菜单ID列表（逗号分隔）',
  `remark` varchar(500) NULL DEFAULT '' COMMENT '备注',
  `status` char(1) NULL DEFAULT '0' COMMENT '套餐状态（0正常 1停用）',
  `create_time` datetime NULL DEFAULT CURRENT_TIMESTAMP COMMENT '创建时间',
  `create_by` varchar(64) NULL DEFAULT NULL COMMENT '创建者ID',
  `update_time` datetime NULL DEFAULT NULL ON UPDATE CURRENT_TIMESTAMP COMMENT '更新时间',
  `update_by` varchar(64) NULL DEFAULT NULL COMMENT '更新者ID',
  `is_deleted` int(11) NULL DEFAULT 0 COMMENT '逻辑删除(0未删除 1已删除)',
  PRIMARY KEY (`package_id`) USING BTREE
) ENGINE = InnoDB CHARACTER SET = utf8mb4 COMMENT = '租户套餐表' ROW_FORMAT = Dynamic;

-- ----------------------------
-- sys_user
-- ----------------------------
CREATE TABLE `sys_user`  (
  `user_id` bigint(20) NOT NULL COMMENT '用户ID(雪花ID)',
  `tenant_id` varchar(20) NULL DEFAULT '000000' COMMENT '租户编号',
  `username` varchar(50) NOT NULL COMMENT '用户账号',
  `password` varchar(100) NOT NULL COMMENT '密码(BCrypt加密)',
  `nickname` varchar(50) NULL DEFAULT '' COMMENT '用户昵称',
  `email` varchar(50) NULL DEFAULT '' COMMENT '用户邮箱',
  `mobile` varchar(11) NULL DEFAULT '' COMMENT '手机号码',
  `avatar` varchar(255) NULL DEFAULT '' COMMENT '头像地址',
  `status` tinyint(4) NULL DEFAULT 1 COMMENT '帐号状态（1正常 0停用）',
  `login_ip` varchar(128) NULL DEFAULT '' COMMENT '最后登录IP',
  `login_date` datetime NULL DEFAULT NULL COMMENT '最后登录时间',
  `create_time` datetime NULL DEFAULT CURRENT_TIMESTAMP COMMENT '创建时间',
  `create_by` varchar(64) NULL DEFAULT NULL COMMENT '创建者ID',
  `update_time` datetime NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP COMMENT '更新时间',
  `update_by` varchar(64) NULL DEFAULT NULL COMMENT '更新者ID',
  `is_deleted` tinyint(4) NULL DEFAULT 0 COMMENT '逻辑删除(0存在 1删除)',
  PRIMARY KEY (`user_id`) USING BTREE,
  UNIQUE INDEX `idx_username`(`username` ASC) USING BTREE,
  INDEX `idx_tenant_id`(`tenant_id` ASC) USING BTREE
) ENGINE = InnoDB CHARACTER SET = utf8mb4 COMMENT = '用户信息表' ROW_FORMAT = Dynamic;

-- ----------------------------
-- sys_user_role
-- ----------------------------
CREATE TABLE `sys_user_role`  (
  `user_id` bigint(20) NOT NULL COMMENT '用户ID(雪花ID)',
  `role_id` bigint(20) NOT NULL COMMENT '角色ID',
  PRIMARY KEY (`user_id`, `role_id`) USING BTREE
) ENGINE = InnoDB CHARACTER SET = utf8mb4 COMMENT = '用户和角色关联表' ROW_FORMAT = Dynamic;

