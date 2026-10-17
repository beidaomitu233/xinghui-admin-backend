-- Configuration is a complete independent page; preserve existing action grants.
UPDATE sys_menu SET menu_name='AI配置',parent_id=202610160180000001,order_num=1,
 path='/ai/configurations',component='system/ai-config/index',perms='ai:config:list',visible='0'
 WHERE menu_id=106 AND is_deleted=0;
UPDATE sys_menu SET order_num=2 WHERE menu_id=107 AND is_deleted=0;
UPDATE sys_menu SET order_num=3 WHERE menu_id=202610160180000003 AND is_deleted=0;
-- Navigation is derived from its current source actions by SysMenuMapper.
-- No new role/API/data grants and no changes to operator-disabled status.
