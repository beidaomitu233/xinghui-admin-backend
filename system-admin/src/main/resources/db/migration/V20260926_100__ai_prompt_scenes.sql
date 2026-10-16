-- AI-02: cross-application prompt scenes with isolated draft and published snapshots.
-- Apply only after the shared test DB window is assigned; no provider calls or business data are involved.
CREATE TABLE `sys_ai_prompt_scene` (
  `scene_id` bigint NOT NULL AUTO_INCREMENT,
  `app_key` varchar(64) COLLATE utf8mb4_bin NOT NULL,
  `scene_key` varchar(96) COLLATE utf8mb4_bin NOT NULL,
  `draft_model_id` bigint NOT NULL,
  `draft_prompt` longtext NOT NULL,
  `draft_variables_json` longtext NOT NULL,
  `draft_revision` int NOT NULL DEFAULT 1,
  `published_model_id` bigint DEFAULT NULL,
  `published_prompt` longtext,
  `published_variables_json` longtext,
  `published_revision` int DEFAULT NULL,
  `enabled` tinyint(1) NOT NULL DEFAULT 0,
  `revision` int NOT NULL DEFAULT 0 COMMENT 'Optimistic concurrency version',
  `create_time` datetime NOT NULL DEFAULT CURRENT_TIMESTAMP,
  `update_time` datetime NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
  PRIMARY KEY (`scene_id`),
  UNIQUE KEY `uk_ai_prompt_app_scene` (`app_key`,`scene_key`),
  KEY `idx_ai_prompt_enabled` (`enabled`,`app_key`,`scene_key`),
  CONSTRAINT `fk_ai_prompt_draft_model` FOREIGN KEY (`draft_model_id`)
    REFERENCES `sys_ai_model_config` (`model_id`) ON DELETE RESTRICT,
  CONSTRAINT `fk_ai_prompt_published_model` FOREIGN KEY (`published_model_id`)
    REFERENCES `sys_ai_model_config` (`model_id`) ON DELETE RESTRICT
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_general_ci;

INSERT INTO `sys_menu` (`menu_id`,`menu_name`,`parent_id`,`order_num`,`path`,`component`,`is_frame`,`menu_type`,`visible`,`status`,`perms`,`icon`,`is_deleted`) VALUES
  (107, '场景提示词', 4, 2, 'prompts', 'system/ai-prompt/index', 1, 'C', '0', '0', 'ai:prompt:list', 'carbon:chat-bot', 0),
  (1036, '场景查询', 107, 1, '', '', 1, 'F', '0', '0', 'ai:prompt:query', '#', 0),
  (1037, '场景新增', 107, 2, '', '', 1, 'F', '0', '0', 'ai:prompt:add', '#', 0),
  (1038, '草稿修改', 107, 3, '', '', 1, 'F', '0', '0', 'ai:prompt:edit', '#', 0),
  (1039, '场景发布', 107, 4, '', '', 1, 'F', '0', '0', 'ai:prompt:publish', '#', 0),
  (1040, '场景启停', 107, 5, '', '', 1, 'F', '0', '0', 'ai:prompt:enable', '#', 0),
  (1041, '场景删除', 107, 6, '', '', 1, 'F', '0', '0', 'ai:prompt:remove', '#', 0);

INSERT INTO `sys_role_menu` (`role_id`,`menu_id`) VALUES
  (2016901862971731969,107),(2016901862971731969,1036),(2016901862971731969,1037),
  (2016901862971731969,1038),(2016901862971731969,1039),(2016901862971731969,1040),
  (2016901862971731969,1041),
  (2016902236600291329,107),(2016902236600291329,1036),(2016902236600291329,1037),
  (2016902236600291329,1038),(2016902236600291329,1039),(2016902236600291329,1040),
  (2016902236600291329,1041);
