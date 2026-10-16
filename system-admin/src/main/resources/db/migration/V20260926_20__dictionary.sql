-- Global reference data. Codes and values are immutable after creation; published rows are not deleted.
CREATE TABLE `sys_dict_type` (
  `type_id` bigint NOT NULL AUTO_INCREMENT,
  `type_code` varchar(64) COLLATE utf8mb4_bin NOT NULL,
  `type_name` varchar(100) NOT NULL,
  `enabled` tinyint(1) NOT NULL DEFAULT 1,
  `remark` varchar(500) DEFAULT NULL,
  `revision` bigint NOT NULL DEFAULT 1,
  `create_time` datetime NOT NULL DEFAULT CURRENT_TIMESTAMP,
  `update_time` datetime NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
  PRIMARY KEY (`type_id`),
  UNIQUE KEY `uk_dict_type_code` (`type_code`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_general_ci;

CREATE TABLE `sys_dict_item` (
  `item_id` bigint NOT NULL AUTO_INCREMENT,
  `type_id` bigint NOT NULL,
  `item_value` varchar(128) COLLATE utf8mb4_bin NOT NULL,
  `item_label` varchar(128) NOT NULL,
  `sort_order` int NOT NULL DEFAULT 0,
  `enabled` tinyint(1) NOT NULL DEFAULT 1,
  `remark` varchar(500) DEFAULT NULL,
  `create_time` datetime NOT NULL DEFAULT CURRENT_TIMESTAMP,
  `update_time` datetime NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
  PRIMARY KEY (`item_id`),
  UNIQUE KEY `uk_dict_item_value` (`type_id`, `item_value`),
  KEY `idx_dict_item_order` (`type_id`, `sort_order`, `item_id`),
  CONSTRAINT `fk_dict_item_type` FOREIGN KEY (`type_id`) REFERENCES `sys_dict_type` (`type_id`) ON DELETE RESTRICT
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_general_ci;

INSERT INTO `sys_menu` (`menu_id`,`menu_name`,`parent_id`,`order_num`,`path`,`component`,`is_frame`,`menu_type`,`visible`,`status`,`perms`,`icon`,`is_deleted`) VALUES
  (104, '字典管理', 1, 5, 'dict', 'system/dict/index', 1, 'C', '0', '0', 'system:dict:list', 'carbon:dictionary', 0),
  (1022, '字典类型新增', 104, 1, '', '', 1, 'F', '0', '0', 'system:dict:type:add', '#', 0),
  (1023, '字典类型修改', 104, 2, '', '', 1, 'F', '0', '0', 'system:dict:type:edit', '#', 0),
  (1024, '字典条目新增', 104, 3, '', '', 1, 'F', '0', '0', 'system:dict:item:add', '#', 0),
  (1025, '字典条目修改', 104, 4, '', '', 1, 'F', '0', '0', 'system:dict:item:edit', '#', 0);

INSERT INTO `sys_role_menu` (`role_id`,`menu_id`) VALUES
  (2016901862971731969,104),(2016901862971731969,1022),(2016901862971731969,1023),(2016901862971731969,1024),(2016901862971731969,1025),
  (2016902236600291329,104),(2016902236600291329,1022),(2016902236600291329,1023),(2016902236600291329,1024),(2016902236600291329,1025);
