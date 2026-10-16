-- CFG-02 owns this migration. Secret values are supplied through authenticated APIs only.
CREATE TABLE sys_business_config (
 section VARCHAR(32) NOT NULL PRIMARY KEY,
 enabled TINYINT(1) NOT NULL DEFAULT 0,
 parameters_json TEXT NOT NULL,
 secrets_ciphertext MEDIUMTEXT NULL,
 revision INT NOT NULL DEFAULT 0,
 update_time DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;
CREATE TABLE sys_business_config_history (
 section VARCHAR(32) NOT NULL,
 revision INT NOT NULL,
 enabled TINYINT(1) NOT NULL,
 parameters_json TEXT NOT NULL,
 secrets_ciphertext MEDIUMTEXT NULL,
 PRIMARY KEY(section,revision)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;
ALTER TABLE sys_wechat_website_config ADD COLUMN credential_mode VARCHAR(16) NOT NULL DEFAULT 'ENV',
 ADD COLUMN secret_ciphertext TEXT NULL;
ALTER TABLE sys_ai_model_config ADD COLUMN credential_mode VARCHAR(16) NOT NULL DEFAULT 'ENV',
 ADD COLUMN credential_ciphertext TEXT NULL;
INSERT INTO sys_menu(menu_id,menu_name,parent_id,order_num,path,component,is_frame,menu_type,visible,status,perms,icon,is_deleted)
VALUES
 (202610120150000001,'系统业务配置',1,6,'/system/business-config','system/business-config/index',1,'C','0','0','system:businessConfig:query','carbon:settings',0),
 (202610120150000002,'业务配置保存',202610120150000001,1,'','',1,'F','0','0','system:businessConfig:edit','#',0);
INSERT INTO sys_menu(menu_id,menu_name,parent_id,order_num,path,component,is_frame,menu_type,visible,status,perms,icon,is_deleted)
SELECT 202610120150000003,'模型测试',m.menu_id,60,'','',1,'F','0','0','ai:model:test','#',0
FROM sys_menu m WHERE m.perms='ai:model:list' AND m.menu_type='C' AND m.is_deleted=0 LIMIT 1;
INSERT INTO sys_role_menu(role_id,menu_id)
SELECT r.role_id,m.menu_id FROM sys_role r JOIN sys_menu m
 ON m.perms IN ('system:businessConfig:query','system:businessConfig:edit','ai:model:test')
WHERE r.tenant_id='000000' AND r.role_key IN ('admin','super_admin') AND r.status=1 AND r.is_deleted=0 AND m.is_deleted=0
AND NOT EXISTS(SELECT 1 FROM (SELECT role_id,menu_id FROM sys_role_menu) existing WHERE existing.role_id=r.role_id AND existing.menu_id=m.menu_id);
DELETE FROM sys_role_menu WHERE menu_id IN (SELECT menu_id FROM sys_menu WHERE perms IN ('system:wechat:query','system:wechat:edit'));
DELETE FROM sys_menu WHERE perms IN ('system:wechat:query','system:wechat:edit');
-- PM serially adds the platform visibility prefix. Legacy deep links still redirect.
