-- Database references are a separate capability, not an implicit scene-edit grant.
INSERT INTO sys_menu(menu_id,menu_name,parent_id,order_num,path,component,is_frame,menu_type,visible,status,perms,icon,is_deleted)
VALUES (202610180242000001,'AI数据库取数',107,32,'','',1,'F','0','0','ai:data:database:read','#',0);

-- Only existing active platform administrators receive this initial grant.
-- Tenant roles, scene editors and old approved-resource readers do not inherit it.
INSERT INTO sys_role_menu(role_id,menu_id)
SELECT r.role_id,202610180242000001 FROM sys_role r
WHERE r.tenant_id='000000' AND r.role_key IN ('admin','super_admin')
AND r.status=1 AND r.is_deleted=0
AND NOT EXISTS(SELECT 1 FROM (SELECT role_id,menu_id FROM sys_role_menu) existing_grant
 WHERE existing_grant.role_id=r.role_id AND existing_grant.menu_id=202610180242000001);
