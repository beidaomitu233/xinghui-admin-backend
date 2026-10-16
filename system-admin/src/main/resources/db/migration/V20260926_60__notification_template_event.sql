-- NTF-01 owns global templates and a durable event request contract. No user inbox or delivery claim here.
CREATE TABLE `sys_notification_template` (
  `template_id` bigint NOT NULL AUTO_INCREMENT,
  `event_code` varchar(100) COLLATE utf8mb4_bin NOT NULL,
  `template_name` varchar(100) NOT NULL,
  `title_template` varchar(200) NOT NULL,
  `body_template` varchar(2000) NOT NULL,
  `target_template` varchar(512) DEFAULT NULL,
  `allowed_variables` varchar(1000) NOT NULL,
  `enabled` tinyint(1) NOT NULL DEFAULT 0,
  `create_time` datetime NOT NULL DEFAULT CURRENT_TIMESTAMP,
  `update_time` datetime NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
  PRIMARY KEY (`template_id`),
  UNIQUE KEY `uk_notification_event_code` (`event_code`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_general_ci;

CREATE TABLE `sys_notification_event_request` (
  `request_id` bigint NOT NULL AUTO_INCREMENT,
  `tenant_id` varchar(20) NOT NULL,
  `recipient_user_id` bigint NOT NULL,
  `event_code` varchar(100) COLLATE utf8mb4_bin NOT NULL,
  `event_key` varchar(128) COLLATE utf8mb4_bin NOT NULL,
  `template_id` bigint DEFAULT NULL,
  `title` varchar(200) DEFAULT NULL,
  `body` varchar(4000) DEFAULT NULL,
  `target` varchar(1024) DEFAULT NULL,
  `status` varchar(16) NOT NULL COMMENT 'PENDING, REJECTED, FAILED; no delivered state in NTF-01',
  `failure_code` varchar(40) DEFAULT NULL,
  `create_time` datetime NOT NULL DEFAULT CURRENT_TIMESTAMP,
  `update_time` datetime NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
  PRIMARY KEY (`request_id`),
  KEY `idx_notification_pending` (`status`, `request_id`),
  KEY `idx_notification_event_key` (`tenant_id`, `event_code`, `event_key`, `recipient_user_id`),
  CONSTRAINT `fk_notification_template` FOREIGN KEY (`template_id`) REFERENCES `sys_notification_template` (`template_id`) ON DELETE RESTRICT
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_general_ci;

INSERT INTO `sys_menu` (`menu_id`,`menu_name`,`parent_id`,`order_num`,`path`,`component`,`is_frame`,`menu_type`,`visible`,`status`,`perms`,`icon`,`is_deleted`) VALUES
  (105, '通知模板', 1, 6, 'notification-template', 'system/notification-template/index', 1, 'C', '0', '0', 'system:notificationTemplate:list', 'carbon:notification', 0),
  (1030, '通知模板新增', 105, 1, '', '', 1, 'F', '0', '0', 'system:notificationTemplate:add', '#', 0),
  (1031, '通知模板修改', 105, 2, '', '', 1, 'F', '0', '0', 'system:notificationTemplate:edit', '#', 0);

INSERT INTO `sys_role_menu` (`role_id`,`menu_id`) VALUES
  (2016901862971731969,105),(2016901862971731969,1030),(2016901862971731969,1031),
  (2016902236600291329,105),(2016902236600291329,1030),(2016902236600291329,1031);
