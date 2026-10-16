-- AI-V04-A: separate navigation entry from template/data APIs. V180 remains immutable.
INSERT INTO sys_menu(menu_id,menu_name,parent_id,order_num,path,component,is_frame,menu_type,visible,status,perms,icon,is_deleted)
VALUES
 (202610160210000001,'AI平台日志审计',202610160180000003,3,'','',1,'F','0','0','ai:request:audit','#',0),
 (202610160210000010,'AI模板列表',107,10,'','',1,'F','0','0','ai:template:list','#',0),
 (202610160210000011,'AI模板查询',107,11,'','',1,'F','0','0','ai:template:query','#',0),
 (202610160210000012,'AI模板新增',107,12,'','',1,'F','0','0','ai:template:add','#',0),
 (202610160210000013,'AI模板编辑',107,13,'','',1,'F','0','0','ai:template:edit','#',0),
 (202610160210000014,'AI模板预览',107,14,'','',1,'F','0','0','ai:template:preview','#',0),
 (202610160210000015,'AI模板测试',107,15,'','',1,'F','0','0','ai:template:test','#',0),
 (202610160210000016,'AI模板发布',107,16,'','',1,'F','0','0','ai:template:publish','#',0),
 (202610160210000017,'AI模板启停',107,17,'','',1,'F','0','0','ai:template:disable','#',0),
 (202610160210000020,'AI数据列表',107,20,'','',1,'F','0','0','ai:data:list','#',0),
 (202610160210000021,'AI数据查询',107,21,'','',1,'F','0','0','ai:data:query','#',0),
 (202610160210000022,'AI数据新增',107,22,'','',1,'F','0','0','ai:data:add','#',0),
 (202610160210000023,'AI数据编辑',107,23,'','',1,'F','0','0','ai:data:edit','#',0),
 (202610160210000024,'AI数据测试',107,24,'','',1,'F','0','0','ai:data:test','#',0),
 (202610160210000025,'AI数据提交审批',107,25,'','',1,'F','0','0','ai:data:submit','#',0),
 (202610160210000026,'AI数据审批',107,26,'','',1,'F','0','0','ai:data:approve','#',0),
 (202610160210000027,'AI数据启停',107,27,'','',1,'F','0','0','ai:data:disable','#',0);

-- Preserve the template-list API capability formerly carried by the C entry itself.
-- Do this BEFORE turning that entry into a navigation-only capability.
INSERT INTO sys_role_menu(role_id,menu_id)
SELECT DISTINCT rm.role_id,202610160210000010 FROM sys_role_menu rm WHERE rm.menu_id=107;
UPDATE sys_menu SET perms='ai:workspace:enter' WHERE menu_id=107 AND menu_type='C';

-- Legacy prompt action holders retain the corresponding new template abilities, not data approval.
INSERT INTO sys_role_menu(role_id,menu_id)
SELECT DISTINCT old_grant.role_id,new_action.menu_id
FROM sys_role_menu old_grant JOIN sys_menu old_action ON old_action.menu_id=old_grant.menu_id
JOIN sys_menu new_action ON
 (old_action.perms='ai:prompt:list' AND new_action.perms='ai:template:list') OR
 (old_action.perms='ai:prompt:query' AND new_action.perms IN ('ai:template:query','ai:template:preview')) OR
 (old_action.perms='ai:prompt:add' AND new_action.perms='ai:template:add') OR
 (old_action.perms='ai:prompt:edit' AND new_action.perms='ai:template:edit') OR
 (old_action.perms='ai:prompt:publish' AND new_action.perms IN ('ai:template:test','ai:template:publish')) OR
 (old_action.perms='ai:prompt:enable' AND new_action.perms='ai:template:disable')
WHERE new_action.menu_id BETWEEN 202610160210000010 AND 202610160210000017
AND NOT EXISTS(SELECT 1 FROM (SELECT role_id,menu_id FROM sys_role_menu) x WHERE x.role_id=old_grant.role_id AND x.menu_id=new_action.menu_id);

-- An audit grant never follows list/query/export or a generic directory grant.
INSERT INTO sys_role_menu(role_id,menu_id)
SELECT r.role_id,m.menu_id FROM sys_role r JOIN sys_menu m ON m.menu_id BETWEEN 202610160210000001 AND 202610160210000027
WHERE r.tenant_id='000000' AND r.role_key IN ('admin','super_admin') AND r.status=1 AND r.is_deleted=0
AND NOT EXISTS(SELECT 1 FROM (SELECT role_id,menu_id FROM sys_role_menu) x WHERE x.role_id=r.role_id AND x.menu_id=m.menu_id);
