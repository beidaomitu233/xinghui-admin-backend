-- V1 content tables and existing permission nodes remain untouched.
-- Only missing page creation/removal nodes are added; ordinary users get no new grant.
INSERT INTO sys_menu(menu_id,menu_name,parent_id,order_num,path,component,is_frame,menu_type,visible,status,perms,icon,create_time,is_deleted)
SELECT 202610120120000001,'页面新增',m.menu_id,3,'','',1,'F','0','0','system:page:add','#',CURRENT_TIMESTAMP,0
FROM sys_menu m WHERE m.perms='system:page:list' AND m.menu_type='C' AND m.is_deleted=0
AND NOT EXISTS(SELECT 1 FROM (SELECT perms FROM sys_menu WHERE is_deleted=0) existing WHERE existing.perms='system:page:add') LIMIT 1;
INSERT INTO sys_menu(menu_id,menu_name,parent_id,order_num,path,component,is_frame,menu_type,visible,status,perms,icon,create_time,is_deleted)
SELECT 202610120120000002,'页面删除',m.menu_id,4,'','',1,'F','0','0','system:page:remove','#',CURRENT_TIMESTAMP,0
FROM sys_menu m WHERE m.perms='system:page:list' AND m.menu_type='C' AND m.is_deleted=0
AND NOT EXISTS(SELECT 1 FROM (SELECT perms FROM sys_menu WHERE is_deleted=0) existing WHERE existing.perms='system:page:remove') LIMIT 1;
INSERT INTO sys_role_menu(role_id,menu_id)
SELECT r.role_id,m.menu_id FROM sys_role r JOIN sys_menu m
ON m.perms IN ('system:news:list','system:news:query','system:news:add','system:news:edit','system:news:remove',
              'system:download:list','system:download:query','system:download:add','system:download:edit','system:download:remove',
              'system:page:list','system:page:query','system:page:add','system:page:edit','system:page:remove')
WHERE r.role_key IN ('admin','super_admin') AND r.tenant_id='000000' AND r.status=1 AND r.is_deleted=0 AND m.is_deleted=0
AND NOT EXISTS(SELECT 1 FROM (SELECT role_id,menu_id FROM sys_role_menu) existing WHERE existing.role_id=r.role_id AND existing.menu_id=m.menu_id);
