-- Independent business entry; prompt/model administration remains under AI management.
INSERT INTO sys_menu(menu_id,menu_name,parent_id,order_num,path,component,is_frame,menu_type,visible,status,perms,icon,is_deleted)
SELECT 202610240420000001,'AI工作台',0,2,'/ai/workbench','ai/workbench/index',1,'C','0','0','ai:agent:invoke','carbon:chat-bot',0
WHERE NOT EXISTS(SELECT 1 FROM sys_menu WHERE perms='ai:agent:invoke' AND menu_type='C' AND is_deleted=0);

-- Preserve the relative order of all other roots, including user-defined orders at INT_MAX.
CREATE TEMPORARY TABLE agent_root_order_v420(menu_id BIGINT NOT NULL PRIMARY KEY,order_num INT NOT NULL);
INSERT INTO agent_root_order_v420(menu_id,order_num)
SELECT m.menu_id,3+(
    SELECT COUNT(*) FROM sys_menu p
    WHERE p.parent_id=0 AND p.is_deleted=0
      AND COALESCE(p.component,'') NOT IN ('dashboard/workspace/index','dashboard/analytics/index','ai/workbench/index')
      AND ((p.order_num IS NULL AND m.order_num IS NOT NULL) OR p.order_num<m.order_num
        OR ((p.order_num=m.order_num OR (p.order_num IS NULL AND m.order_num IS NULL)) AND p.menu_id<m.menu_id))
) FROM sys_menu m
WHERE m.parent_id=0 AND m.is_deleted=0
  AND COALESCE(m.component,'') NOT IN ('dashboard/workspace/index','dashboard/analytics/index','ai/workbench/index');
UPDATE sys_menu SET order_num=(SELECT r.order_num FROM agent_root_order_v420 r WHERE r.menu_id=sys_menu.menu_id)
WHERE parent_id=0 AND is_deleted=0
  AND COALESCE(component,'') NOT IN ('dashboard/workspace/index','dashboard/analytics/index','ai/workbench/index');
DROP TABLE agent_root_order_v420;
UPDATE sys_menu SET order_num=0 WHERE parent_id=0 AND component='dashboard/workspace/index' AND is_deleted=0;
UPDATE sys_menu SET order_num=1 WHERE parent_id=0 AND component='dashboard/analytics/index' AND is_deleted=0;

-- New access is granted only to existing default-tenant administrators. Other roles opt in explicitly.
INSERT INTO sys_role_menu(role_id,menu_id)
SELECT r.role_id,m.menu_id FROM sys_role r JOIN sys_menu m ON m.perms='ai:agent:invoke' AND m.menu_type='C'
WHERE r.tenant_id='000000' AND r.role_key IN ('admin','super_admin') AND r.status=1 AND r.is_deleted=0 AND m.is_deleted=0
AND NOT EXISTS(SELECT 1 FROM sys_role_menu rm WHERE rm.role_id=r.role_id AND rm.menu_id=m.menu_id);
