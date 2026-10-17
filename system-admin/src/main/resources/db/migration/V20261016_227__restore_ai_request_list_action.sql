-- V225 changed the old log page permission into navigation-only ai:monitor:enter.
-- Preserve its original read capability as an independent action, without deriving data access from navigation.
INSERT INTO sys_menu(menu_id,menu_name,parent_id,order_num,path,component,is_frame,menu_type,visible,status,perms,icon,is_deleted)
VALUES (202610160227000001,'AI日志列表',202610160180000003,0,'','',1,'F','0','0','ai:request:list','#',0);

-- Only an existing explicit grant of the former log page proves ownership of its old list permission.
-- Configuration/scene actions and dynamically derived monitor navigation are deliberately not grant sources.
INSERT INTO sys_role_menu(role_id,menu_id)
SELECT DISTINCT r.role_id,202610160227000001
FROM sys_role r
JOIN sys_role_menu original_grant ON original_grant.role_id=r.role_id AND original_grant.menu_id=202610160180000003
JOIN sys_menu original_menu ON original_menu.menu_id=original_grant.menu_id AND original_menu.status='0' AND original_menu.is_deleted=0
WHERE r.status=1 AND r.is_deleted=0
AND NOT EXISTS(SELECT 1 FROM (SELECT role_id,menu_id FROM sys_role_menu) existing_grant
 WHERE existing_grant.role_id=r.role_id AND existing_grant.menu_id=202610160227000001);
