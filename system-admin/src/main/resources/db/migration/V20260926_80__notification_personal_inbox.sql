-- NTF-02: durable claim state for committed NTF-01 requests; V70 must run first.
ALTER TABLE `sys_notification_event_request`
  ADD COLUMN `attempt_count` int NOT NULL DEFAULT 0,
  ADD COLUMN `claim_token` varchar(36) DEFAULT NULL,
  ADD COLUMN `lease_until` datetime(3) DEFAULT NULL,
  ADD COLUMN `next_attempt_at` datetime(3) DEFAULT NULL,
  ADD COLUMN `delivered_at` datetime(3) DEFAULT NULL,
  ADD COLUMN `inbox_id` bigint DEFAULT NULL,
  ADD COLUMN `delivery_outcome` varchar(16) DEFAULT NULL;

CREATE INDEX `idx_notification_due` ON `sys_notification_event_request`
  (`status`, `next_attempt_at`, `lease_until`, `request_id`);

CREATE TABLE `sys_notification_inbox` (
  `notification_id` bigint NOT NULL AUTO_INCREMENT,
  `source_request_id` bigint NOT NULL,
  `tenant_id` varchar(20) NOT NULL,
  `recipient_user_id` bigint NOT NULL,
  `event_code` varchar(100) COLLATE utf8mb4_bin NOT NULL,
  `event_key` varchar(128) COLLATE utf8mb4_bin NOT NULL,
  `title` varchar(200) NOT NULL,
  `body` varchar(4000) NOT NULL,
  `target` varchar(1024) DEFAULT NULL,
  `create_time` datetime(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3),
  `read_time` datetime(3) DEFAULT NULL,
  PRIMARY KEY (`notification_id`),
  UNIQUE KEY `uk_notification_event_recipient` (`tenant_id`,`event_code`,`event_key`,`recipient_user_id`),
  UNIQUE KEY `uk_notification_source_request` (`source_request_id`),
  KEY `idx_notification_owner_time` (`tenant_id`,`recipient_user_id`,`create_time`,`notification_id`),
  KEY `idx_notification_unread` (`tenant_id`,`recipient_user_id`,`read_time`,`create_time`,`notification_id`),
  CONSTRAINT `fk_notification_inbox_request` FOREIGN KEY (`source_request_id`)
    REFERENCES `sys_notification_event_request` (`request_id`) ON DELETE RESTRICT
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_general_ci;
