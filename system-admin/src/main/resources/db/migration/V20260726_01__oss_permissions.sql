-- =====================================================================
-- OSS 权限修正（深度审查确认的 P1 缺陷）
-- 种子按钮的 perms 是旧 SysFileController 时代的 system:file:*，
-- 而现行 SysOssController 全部守卫 system:oss:*，且授权链无超管通配符兜底，
-- 导致全新库上文件管理接口对所有角色一律 403。
-- 本迁移将按钮权限对齐控制器实际使用的权限串，并补齐缺失的授权与配置管理按钮。
-- =====================================================================

-- 1. 文件管理按钮 perms: system:file:* -> system:oss:*（按 menu_id 精确定位）
UPDATE `sys_menu` SET `perms` = 'system:oss:query'    WHERE `menu_id` = 3000;
UPDATE `sys_menu` SET `perms` = 'system:oss:upload'   WHERE `menu_id` = 3001;
UPDATE `sys_menu` SET `perms` = 'system:oss:remove'   WHERE `menu_id` = 3002;
UPDATE `sys_menu` SET `perms` = 'system:oss:list'     WHERE `menu_id` = 3007;
UPDATE `sys_menu` SET `perms` = 'system:oss:download' WHERE `menu_id` = 3008;

-- 2. 文件管理页面菜单(300)的路由权限同步（原 infra:file:list 无任何代码使用）
UPDATE `sys_menu` SET `perms` = 'system:oss:list' WHERE `menu_id` = 300;

-- 3. 管理员角色补齐 文件列表/文件下载 授权（原种子仅超级管理员持有）
INSERT INTO `sys_role_menu` (`role_id`, `menu_id`) VALUES (2016901862971731969, 3007);
INSERT INTO `sys_role_menu` (`role_id`, `menu_id`) VALUES (2016901862971731969, 3008);

-- 4. OSS 存储配置管理按钮（SysOssConfigController: system:oss:config:*，原完全未种子化）
INSERT INTO `sys_menu`
    (`menu_id`, `menu_name`, `parent_id`, `order_num`, `path`, `component`, `is_frame`, `menu_type`,
     `visible`, `status`, `perms`, `icon`, `create_time`, `update_time`, `is_deleted`)
VALUES
    (3009, 'OSS配置列表', 300, 5, '', '', 1, 'F', '0', '0', 'system:oss:config:list',   '#', '2026-07-26 00:00:00', '2026-07-26 00:00:00', 0),
    (3010, 'OSS配置查询', 300, 6, '', '', 1, 'F', '0', '0', 'system:oss:config:query',  '#', '2026-07-26 00:00:00', '2026-07-26 00:00:00', 0),
    (3011, 'OSS配置新增', 300, 7, '', '', 1, 'F', '0', '0', 'system:oss:config:add',    '#', '2026-07-26 00:00:00', '2026-07-26 00:00:00', 0),
    (3012, 'OSS配置修改', 300, 8, '', '', 1, 'F', '0', '0', 'system:oss:config:edit',   '#', '2026-07-26 00:00:00', '2026-07-26 00:00:00', 0),
    (3013, 'OSS配置删除', 300, 9, '', '', 1, 'F', '0', '0', 'system:oss:config:remove', '#', '2026-07-26 00:00:00', '2026-07-26 00:00:00', 0);

INSERT INTO `sys_role_menu` (`role_id`, `menu_id`) VALUES
    (2016901862971731969, 3009), (2016901862971731969, 3010), (2016901862971731969, 3011),
    (2016901862971731969, 3012), (2016901862971731969, 3013),
    (2016902236600291329, 3009), (2016902236600291329, 3010), (2016902236600291329, 3011),
    (2016902236600291329, 3012), (2016902236600291329, 3013);
