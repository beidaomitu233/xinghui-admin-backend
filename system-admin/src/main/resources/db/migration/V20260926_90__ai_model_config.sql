-- AI-01: model configuration only. Apply after NTF-02 V80; no provider calls or secret values are stored.
CREATE TABLE `sys_ai_model_config` (
  `model_id` bigint NOT NULL AUTO_INCREMENT,
  `provider_code` varchar(64) COLLATE utf8mb4_bin NOT NULL,
  `model_code` varchar(128) COLLATE utf8mb4_bin NOT NULL,
  `endpoint_url` varchar(2048) NOT NULL,
  `credential_env_name` varchar(128) DEFAULT NULL COMMENT 'Environment variable name only; never a secret value',
  `timeout_seconds` smallint NOT NULL DEFAULT 30,
  `enabled` tinyint(1) NOT NULL DEFAULT 1,
  `revision` int NOT NULL DEFAULT 0 COMMENT 'Optimistic concurrency version',
  `create_time` datetime NOT NULL DEFAULT CURRENT_TIMESTAMP,
  `update_time` datetime NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
  PRIMARY KEY (`model_id`),
  UNIQUE KEY `uk_ai_model_provider_code` (`provider_code`, `model_code`),
  KEY `idx_ai_model_enabled_provider` (`enabled`, `provider_code`, `model_id`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_general_ci;

INSERT INTO `sys_menu` (`menu_id`,`menu_name`,`parent_id`,`order_num`,`path`,`component`,`is_frame`,`menu_type`,`visible`,`status`,`perms`,`icon`,`is_deleted`) VALUES
  (106, 'AI模型配置', 4, 1, 'model', 'system/ai-model/index', 1, 'C', '0', '0', 'ai:model:list', 'carbon:machine-learning', 0),
  (1032, 'AI模型查询', 106, 1, '', '', 1, 'F', '0', '0', 'ai:model:query', '#', 0),
  (1033, 'AI模型新增', 106, 2, '', '', 1, 'F', '0', '0', 'ai:model:add', '#', 0),
  (1034, 'AI模型修改', 106, 3, '', '', 1, 'F', '0', '0', 'ai:model:edit', '#', 0),
  (1035, 'AI模型删除', 106, 4, '', '', 1, 'F', '0', '0', 'ai:model:remove', '#', 0);

INSERT INTO `sys_role_menu` (`role_id`,`menu_id`) VALUES
  (2016901862971731969,106),(2016901862971731969,1032),(2016901862971731969,1033),(2016901862971731969,1034),(2016901862971731969,1035),
  (2016902236600291329,106),(2016902236600291329,1032),(2016902236600291329,1033),(2016902236600291329,1034),(2016902236600291329,1035);

-- The AI navigation group was reserved in the baseline and is now used by its first managed feature.
UPDATE `sys_menu` SET `visible`='0', `status`='0' WHERE `menu_id`=4 AND `menu_type`='M' AND `is_deleted`=0;
